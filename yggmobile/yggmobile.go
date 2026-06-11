// Package yggmobile - Yggdrasil node with TCP via netstack (lwip-based).
// Uses github.com/songgao/water and golang.org/x/net/ipv6 approach
// but without any system calls that SELinux blocks.
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

var (
	mu      sync.Mutex
	yggCore *core.Core
	iprwc   *ipv6rwc.ReadWriteCloser
	running atomic.Bool
	address string
	stopCh  chan struct{}
)

// Start launches the Yggdrasil node.
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
	address = ""
}

func GetAddress() string { return address }
func IsRunning() bool    { return running.Load() }

// ── Minimal userspace TCP over ipv6rwc ───────────────────────────────────────
// We implement a minimal TCP handshake directly over IPv6 raw packets.
// This avoids any system socket calls that SELinux might block.

// DialTCP establishes a TCP connection to dst:port via Yggdrasil overlay.
func DialTCP(dst string, port int) (*YggConn, error) {
	if !running.Load() {
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
		sendBuf: make([]byte, 0, 65535),
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := conn.handshake(ctx); err != nil {
		return nil, fmt.Errorf("handshake: %w", err)
	}

	return &YggConn{conn: conn}, nil
}

// ── YggConn ───────────────────────────────────────────────────────────────────

type YggConn struct {
	conn *tcpConn
}

func (c *YggConn) Read(buf []byte) (int, error)  { return c.conn.Read(buf) }
func (c *YggConn) Write(buf []byte) (int, error) { return c.conn.Write(buf) }
func (c *YggConn) Close() error                  { return c.conn.Close() }

// ── Minimal TCP state machine ─────────────────────────────────────────────────

const (
	tcpFlagFIN = 0x01
	tcpFlagSYN = 0x02
	tcpFlagRST = 0x04
	tcpFlagACK = 0x10
)

type tcpConn struct {
	src, dst       []byte
	srcPort, dstPort uint16
	rw             io.ReadWriter
	mu             sync.Mutex
	seq, ack       uint32
	recvBuf        []byte
	sendBuf        []byte
	closed         bool
	recvCh         chan []byte
	once           sync.Once
}

func (c *tcpConn) handshake(ctx context.Context) error {
	c.recvCh = make(chan []byte, 64)
	c.seq = rand.Uint32()

	// Send SYN
	syn := c.buildTCP(tcpFlagSYN, c.seq, 0, nil)
	pkt := c.buildIPv6(syn)
	if _, err := c.rw.Write(pkt); err != nil {
		return fmt.Errorf("send SYN: %w", err)
	}
	c.seq++

	// Start reader goroutine
	go c.readLoop()

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
			if len(seg) < 20 { continue }
			flags := seg[13]
			if flags&(tcpFlagSYN|tcpFlagACK) == (tcpFlagSYN|tcpFlagACK) {
				c.ack = binary.BigEndian.Uint32(seg[4:8]) + 1
				// Send ACK
				ack := c.buildTCP(tcpFlagACK, c.seq, c.ack, nil)
				c.rw.Write(c.buildIPv6(ack))
				return nil
			}
		case <-time.After(100 * time.Millisecond):
		}
	}
}

func (c *tcpConn) readLoop() {
	buf := make([]byte, 65535)
	for {
		n, err := c.rw.Read(buf)
		if err != nil { return }
		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		// Parse IPv6 + TCP
		if len(pkt) < 40 { continue }
		// Check IPv6 next header = TCP (6)
		if pkt[6] != 6 { continue }
		// Check src/dst match
		if !net.IP(pkt[8:24]).Equal(c.dst) { continue }
		if !net.IP(pkt[24:40]).Equal(c.src) { continue }
		tcp := pkt[40:]
		if len(tcp) < 20 { continue }
		sport := binary.BigEndian.Uint16(tcp[0:2])
		dport := binary.BigEndian.Uint16(tcp[2:4])
		if sport != c.dstPort || dport != c.srcPort { continue }

		flags := tcp[13]
		dataOffset := int((tcp[12] >> 4) * 4)
		payload := tcp[dataOffset:]

		if flags&tcpFlagRST != 0 {
			c.closed = true
			return
		}

		if len(payload) > 0 {
			c.mu.Lock()
			c.recvBuf = append(c.recvBuf, payload...)
			c.ack += uint32(len(payload))
			c.mu.Unlock()
			// Send ACK
			ack := c.buildTCP(tcpFlagACK, c.seq, c.ack, nil)
			c.rw.Write(c.buildIPv6(ack))
		}

		select {
		case c.recvCh <- tcp:
		default:
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
		time.Sleep(5 * time.Millisecond)
	}
}

func (c *tcpConn) Write(buf []byte) (int, error) {
	if c.closed { return 0, io.EOF }
	seg := c.buildTCP(tcpFlagACK, c.seq, c.ack, buf)
	pkt := c.buildIPv6(seg)
	n, err := c.rw.Write(pkt)
	if err != nil { return 0, err }
	c.seq += uint32(len(buf))
	return n, nil
}

func (c *tcpConn) Close() error {
	c.once.Do(func() {
		c.closed = true
		fin := c.buildTCP(tcpFlagFIN|tcpFlagACK, c.seq, c.ack, nil)
		c.rw.Write(c.buildIPv6(fin))
	})
	return nil
}

func (c *tcpConn) buildTCP(flags uint8, seq, ack uint32, data []byte) []byte {
	hdr := make([]byte, 20)
	binary.BigEndian.PutUint16(hdr[0:2], c.srcPort)
	binary.BigEndian.PutUint16(hdr[2:4], c.dstPort)
	binary.BigEndian.PutUint32(hdr[4:8], seq)
	binary.BigEndian.PutUint32(hdr[8:12], ack)
	hdr[12] = 0x50 // data offset = 5 (20 bytes)
	hdr[13] = flags
	binary.BigEndian.PutUint16(hdr[14:16], 65535) // window
	// checksum placeholder
	seg := append(hdr, data...)
	// compute checksum
	cs := tcpChecksum(c.src, c.dst, seg)
	binary.BigEndian.PutUint16(seg[16:18], cs)
	return seg
}

func (c *tcpConn) buildIPv6(payload []byte) []byte {
	hdr := make([]byte, 40)
	hdr[0] = 0x60 // version=6
	binary.BigEndian.PutUint16(hdr[4:6], uint16(len(payload)))
	hdr[6] = 6    // next header = TCP
	hdr[7] = 64   // hop limit
	copy(hdr[8:24], c.src)
	copy(hdr[24:40], c.dst)
	return append(hdr, payload...)
}

func tcpChecksum(src, dst, seg []byte) uint16 {
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], src)
	copy(pseudo[16:32], dst)
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(len(seg)))
	pseudo[39] = 6 // TCP
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
