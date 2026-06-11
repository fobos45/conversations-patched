// Package yggmobile - Yggdrasil node with multiplexed userspace TCP.
package yggmobile

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	golog "github.com/gologme/log"
	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	"github.com/yggdrasil-network/yggdrasil-go/src/ipv6rwc"
)

// ── Global state ─────────────────────────────────────────────────────────────

var (
	mu      sync.Mutex
	yggCore *core.Core
	iprwc   *ipv6rwc.ReadWriteCloser
	running atomic.Bool
	address string
	stopCh  chan struct{}
	mux     *packetMux
)

// ── Public API ────────────────────────────────────────────────────────────────

func Start(peers string) error {
	mu.Lock()
	defer mu.Unlock()
	if running.Load() {
		return nil
	}
	stopCh = make(chan struct{})

	logger := golog.New(log.Writer(), "", 0)
	logger.EnableLevel("error")
	logger.EnableLevel("warn")
	logger.EnableLevel("info")

	cfg := config.GenerateConfig()
	opts := []core.SetupOption{}
	for _, peer := range strings.Split(peers, "\n") {
		peer = strings.TrimSpace(peer)
		if peer != "" {
			opts = append(opts, core.Peer{URI: peer})
		}
	}

	var err error
	yggCore, err = core.New(cfg.Certificate, logger, opts...)
	if err != nil {
		return fmt.Errorf("core: %w", err)
	}
	address = yggCore.Address().String()
	iprwc = ipv6rwc.NewReadWriteCloser(yggCore)

	// Start central packet multiplexer
	mux = newPacketMux(iprwc)
	go mux.run(stopCh)

	running.Store(true)
	log.Printf("yggmobile: started addr=%s", address)
	return nil
}

func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if !running.Load() {
		return
	}
	running.Store(false)
	close(stopCh)
	if iprwc != nil { iprwc.Close(); iprwc = nil }
	if yggCore != nil { yggCore.Stop(); yggCore = nil }
	mux = nil
	address = ""
}

func GetAddress() string { return address }
func IsRunning() bool    { return running.Load() }

// DialTCP connects to dst:port via the Yggdrasil overlay.
func DialTCP(dst string, port int) (*YggConn, error) {
	if !running.Load() || mux == nil {
		return nil, fmt.Errorf("not running")
	}

	dstIP := net.ParseIP(dst).To16()
	if dstIP == nil {
		return nil, fmt.Errorf("invalid IPv6: %s", dst)
	}
	srcIP := net.ParseIP(address).To16()
	if srcIP == nil {
		return nil, fmt.Errorf("bad local addr: %s", address)
	}

	srcPort := uint16(40000 + rand.Intn(20000))
	dstPort := uint16(port)

	conn := &tcpConn{
		src:     srcIP,
		dst:     dstIP,
		srcPort: srcPort,
		dstPort: dstPort,
		rw:      iprwc,
		recvBuf: make([]byte, 0, 65535),
		recvCh:  make(chan tcpSegment, 64),
	}

	// Register with mux before handshake
	connKey := connKey{srcPort: srcPort, dstPort: dstPort}
	mux.register(connKey, conn.recvCh)
	defer func() {
		if !conn.established {
			mux.unregister(connKey)
		}
	}()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := conn.handshake(ctx); err != nil {
		return nil, fmt.Errorf("handshake: %w", err)
	}
	conn.established = true
	conn.muxKey = connKey
	conn.mux = mux

	return &YggConn{conn: conn}, nil
}

// ── Packet multiplexer ────────────────────────────────────────────────────────

type connKey struct {
	srcPort, dstPort uint16
}

type tcpSegment struct {
	flags   uint8
	seq     uint32
	ack     uint32
	payload []byte
}

type packetMux struct {
	rw      io.ReadWriter
	mu      sync.RWMutex
	conns   map[connKey]chan<- tcpSegment
}

func newPacketMux(rw io.ReadWriter) *packetMux {
	return &packetMux{
		rw:    rw,
		conns: make(map[connKey]chan<- tcpSegment),
	}
}

func (m *packetMux) register(key connKey, ch chan<- tcpSegment) {
	m.mu.Lock()
	m.conns[key] = ch
	m.mu.Unlock()
}

func (m *packetMux) unregister(key connKey) {
	m.mu.Lock()
	delete(m.conns, key)
	m.mu.Unlock()
}

func (m *packetMux) run(stop <-chan struct{}) {
	buf := make([]byte, 65535)
	for {
		select {
		case <-stop:
			return
		default:
		}

		n, err := m.rw.Read(buf)
		if err != nil {
			return
		}
		pkt := buf[:n]

		// Must be IPv6 (version=6) with TCP (next header=6)
		if len(pkt) < 54 || pkt[0]>>4 != 6 || pkt[6] != 6 {
			continue
		}

		tcp := pkt[40:]
		if len(tcp) < 20 {
			continue
		}

		dport := binary.BigEndian.Uint16(tcp[0:2]) // remote src → our dst
		sport := binary.BigEndian.Uint16(tcp[2:4]) // remote dst → our src

		key := connKey{srcPort: sport, dstPort: dport}

		dataOffset := int((tcp[12] >> 4) * 4)
		var payload []byte
		if dataOffset < len(tcp) {
			payload = make([]byte, len(tcp)-dataOffset)
			copy(payload, tcp[dataOffset:])
		}

		seg := tcpSegment{
			flags:   tcp[13],
			seq:     binary.BigEndian.Uint32(tcp[4:8]),
			ack:     binary.BigEndian.Uint32(tcp[8:12]),
			payload: payload,
		}

		m.mu.RLock()
		ch, ok := m.conns[key]
		m.mu.RUnlock()

		if ok {
			select {
			case ch <- seg:
			default:
			}
		}
	}
}

// ── TCP connection ────────────────────────────────────────────────────────────

type tcpConn struct {
	src, dst        []byte
	srcPort, dstPort uint16
	rw              io.Writer
	mu              sync.Mutex
	seq, ack        uint32
	recvBuf         []byte
	recvCh          chan tcpSegment
	closed          bool
	established     bool
	muxKey          connKey
	mux             *packetMux
	once            sync.Once
}

func (c *tcpConn) handshake(ctx context.Context) error {
	c.seq = rand.Uint32()

	// Send SYN
	if err := c.sendTCP(tcpFlagSYN, c.seq, 0, nil); err != nil {
		return fmt.Errorf("send SYN: %w", err)
	}
	synSeq := c.seq
	c.seq++

	// Wait for SYN-ACK
	deadline := time.Now().Add(10 * time.Second)
	for {
		if time.Now().After(deadline) {
			return fmt.Errorf("timeout waiting for SYN-ACK")
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case seg := <-c.recvCh:
			if seg.flags&(tcpFlagSYN|tcpFlagACK) == (tcpFlagSYN|tcpFlagACK) {
				if seg.ack != synSeq+1 { continue }
				c.ack = seg.seq + 1
				// Send ACK
				c.sendTCP(tcpFlagACK, c.seq, c.ack, nil)
				return nil
			}
		case <-time.After(2 * time.Second):
			// Retransmit SYN
			log.Printf("yggmobile: retransmit SYN to %s:%d", net.IP(c.dst), c.dstPort)
			c.sendTCP(tcpFlagSYN, synSeq, 0, nil)
		}
	}
}

func (c *tcpConn) Read(buf []byte) (int, error) {
	for {
		c.mu.Lock()
		if len(c.recvBuf) > 0 {
			n := copy(buf, c.recvBuf)
			c.recvBuf = c.recvBuf[n:]
			c.mu.Unlock()
			return n, nil
		}
		if c.closed {
			c.mu.Unlock()
			return 0, io.EOF
		}
		c.mu.Unlock()

		// Wait for data from mux
		select {
		case seg, ok := <-c.recvCh:
			if !ok { return 0, io.EOF }
			if seg.flags&tcpFlagRST != 0 {
				c.closed = true
				return 0, io.EOF
			}
			if seg.flags&tcpFlagFIN != 0 {
				c.closed = true
				c.ack++
				c.sendTCP(tcpFlagACK, c.seq, c.ack, nil)
				return 0, io.EOF
			}
			if len(seg.payload) > 0 {
				c.mu.Lock()
				c.recvBuf = append(c.recvBuf, seg.payload...)
				c.ack += uint32(len(seg.payload))
				c.mu.Unlock()
				c.sendTCP(tcpFlagACK, c.seq, c.ack, nil)
			}
		case <-time.After(100 * time.Millisecond):
		}
	}
}

func (c *tcpConn) Write(data []byte) (int, error) {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return 0, io.EOF
	}
	seq := c.seq
	c.seq += uint32(len(data))
	ack := c.ack
	c.mu.Unlock()

	// Send in chunks of 1400 bytes
	const maxSeg = 1400
	sent := 0
	for sent < len(data) {
		end := sent + maxSeg
		if end > len(data) { end = len(data) }
		if err := c.sendTCP(tcpFlagACK, seq+uint32(sent), ack, data[sent:end]); err != nil {
			return sent, err
		}
		sent = end
	}
	return len(data), nil
}

func (c *tcpConn) Close() error {
	c.once.Do(func() {
		c.mu.Lock()
		c.closed = true
		seq := c.seq
		ack := c.ack
		c.mu.Unlock()
		c.sendTCP(tcpFlagFIN|tcpFlagACK, seq, ack, nil)
		if c.mux != nil {
			c.mux.unregister(c.muxKey)
		}
	})
	return nil
}

func (c *tcpConn) sendTCP(flags uint8, seq, ack uint32, data []byte) error {
	seg := buildTCP(c.src, c.dst, c.srcPort, c.dstPort, flags, seq, ack, data)
	pkt := buildIPv6(c.src, c.dst, seg)
	_, err := c.rw.Write(pkt)
	return err
}

// ── Packet builders ───────────────────────────────────────────────────────────

const (
	tcpFlagFIN = 0x01
	tcpFlagSYN = 0x02
	tcpFlagRST = 0x04
	tcpFlagACK = 0x10
)

func buildTCP(src, dst []byte, srcPort, dstPort uint16, flags uint8, seq, ack uint32, data []byte) []byte {
	hdr := make([]byte, 20)
	binary.BigEndian.PutUint16(hdr[0:2], srcPort)
	binary.BigEndian.PutUint16(hdr[2:4], dstPort)
	binary.BigEndian.PutUint32(hdr[4:8], seq)
	binary.BigEndian.PutUint32(hdr[8:12], ack)
	hdr[12] = 0x50
	hdr[13] = flags
	binary.BigEndian.PutUint16(hdr[14:16], 65535)
	seg := append(hdr, data...)
	cs := tcpChecksum(src, dst, seg)
	binary.BigEndian.PutUint16(seg[16:18], cs)
	return seg
}

func buildIPv6(src, dst []byte, payload []byte) []byte {
	hdr := make([]byte, 40)
	hdr[0] = 0x60
	binary.BigEndian.PutUint16(hdr[4:6], uint16(len(payload)))
	hdr[6] = 6
	hdr[7] = 64
	copy(hdr[8:24], src)
	copy(hdr[24:40], dst)
	return append(hdr, payload...)
}

func tcpChecksum(src, dst, seg []byte) uint16 {
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], src)
	copy(pseudo[16:32], dst)
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(len(seg)))
	pseudo[39] = 6
	data := append(pseudo, seg...)
	var sum uint32
	for i := 0; i+1 < len(data); i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if len(data)%2 != 0 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for sum>>16 != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

// ── YggConn ───────────────────────────────────────────────────────────────────

type YggConn struct {
	conn *tcpConn
}

func (c *YggConn) Read(buf []byte) (int, error)  { return c.conn.Read(buf) }
func (c *YggConn) Write(buf []byte) (int, error) { return c.conn.Write(buf) }
func (c *YggConn) Close() error                  { return c.conn.Close() }
