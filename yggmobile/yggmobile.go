// Package yggmobile - Yggdrasil node with multiplexed userspace TCP.
package yggmobile

import (
	"context"
	cryptorand "crypto/rand"
	"crypto/ed25519"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"math/big"
	"math/rand"
	"net"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	golog "github.com/gologme/log"
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

// Start brings up the embedded Yggdrasil node using the given newline-
// separated peer URI list, and the given keyFilePath for a persisted
// node identity.
//
// keyFilePath should be a stable, writable path (e.g. inside the app's
// internal files directory). On first run no key exists there yet, so a
// fresh ed25519 identity is generated and saved to that path. On every
// subsequent call (including every peer-list-triggered restart) the same
// key is loaded back, so the node's Yggdrasil address stays stable across
// restarts instead of changing every time. Pass an empty string to opt
// out of persistence (keeps the old random-identity-per-call behaviour).
func Start(peers string, keyFilePath string) error {
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

	priv, loadedExisting, err := loadOrCreateIdentity(keyFilePath)
	if err != nil {
		return fmt.Errorf("identity: %w", err)
	}
	cert, err := selfSignedCertFromKey(priv)
	if err != nil {
		return fmt.Errorf("identity cert: %w", err)
	}
	if !loadedExisting && keyFilePath != "" {
		if werr := os.WriteFile(keyFilePath, priv, 0600); werr != nil {
			// Not fatal: the node can still run, it'll just generate a new
			// identity again next time since persistence failed to write.
			log.Printf("yggmobile: warning: could not persist identity key: %v", werr)
		}
	}

	opts := []core.SetupOption{}
	for _, peer := range strings.Split(peers, "\n") {
		peer = strings.TrimSpace(peer)
		if peer != "" {
			opts = append(opts, core.Peer{URI: peer})
		}
	}

	yggCore, err = core.New(cert, logger, opts...)
	if err != nil {
		return fmt.Errorf("core: %w", err)
	}
	address = yggCore.Address().String()
	iprwc = ipv6rwc.NewReadWriteCloser(yggCore)

	// Start central packet multiplexer
	mux = newPacketMux(iprwc)
	go mux.run(stopCh)

	running.Store(true)
	identitySrc := "freshly generated"
	if loadedExisting {
		identitySrc = "persisted"
	}
	log.Printf("yggmobile: started addr=%s (identity %s)", address, identitySrc)
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

// ── Persisted node identity ──────────────────────────────────────────────────
//
// yggdrasil-go derives a node's overlay address deterministically from its
// public key. config.GenerateConfig() (the old approach) generates a brand
// new random keypair on every call, which meant the device's Yggdrasil
// address changed on every single restart (e.g. every peer-list edit).
// These helpers persist the raw ed25519 private key to a small file and
// reuse it, giving the node a stable identity/address across restarts,
// while only depending on Go's standard library (crypto/ed25519,
// crypto/tls, crypto/x509) rather than yggdrasil-go's internal config
// struct layout.

// loadOrCreateIdentity returns (privateKey, loadedFromDisk, error).
func loadOrCreateIdentity(keyFilePath string) (ed25519.PrivateKey, bool, error) {
	if keyFilePath != "" {
		if data, err := os.ReadFile(keyFilePath); err == nil {
			if len(data) == ed25519.PrivateKeySize {
				return ed25519.PrivateKey(data), true, nil
			}
			log.Printf("yggmobile: identity file has unexpected size %d, regenerating", len(data))
		}
	}
	_, priv, err := ed25519.GenerateKey(cryptorand.Reader)
	if err != nil {
		return nil, false, err
	}
	return priv, false, nil
}

// selfSignedCertFromKey builds a long-lived self-signed TLS certificate
// bound to the given ed25519 key, exactly the shape yggdrasil-go's core
// expects from config.GenerateConfig().Certificate, but reproducible from
// a stable key instead of always-random.
func selfSignedCertFromKey(priv ed25519.PrivateKey) (tls.Certificate, error) {
	pub, ok := priv.Public().(ed25519.PublicKey)
	if !ok {
		return tls.Certificate{}, fmt.Errorf("unexpected public key type")
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "yggdrasil"},
		NotBefore:             time.Now().Add(-24 * time.Hour),
		NotAfter:              time.Now().AddDate(100, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		IsCA:                  true,
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(cryptorand.Reader, template, template, pub, priv)
	if err != nil {
		return tls.Certificate{}, err
	}
	return tls.Certificate{
		Certificate: [][]byte{der},
		PrivateKey:  priv,
	}, nil
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
		src:        srcIP,
		dst:        dstIP,
		srcPort:    srcPort,
		dstPort:    dstPort,
		rw:         iprwc,
		recvCh:     make(chan tcpSegment, 64),
		stopReader: make(chan struct{}),
	}
	conn.cond = sync.NewCond(&conn.mu)

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
	conn.sendUnacked = conn.seq

	go conn.backgroundReader()

	return &YggConn{conn: conn}, nil
}

// DialUDP opens a UDP "connection" to dst:port via the Yggdrasil overlay.
// Unlike DialTCP there is no handshake: this just allocates a local
// ephemeral port and lets the caller Write/Read datagrams to/from it.
func DialUDP(dst string, port int) (*YggUDPConn, error) {
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

	conn := &udpConn{
		src:     srcIP,
		dst:     dstIP,
		srcPort: srcPort,
		dstPort: dstPort,
		rw:      iprwc,
		recvCh:  make(chan []byte, 64),
	}

	mux.registerUDP(srcPort, conn.recvCh)
	conn.mux = mux

	return &YggUDPConn{conn: conn}, nil
}

// ── TCP listener (for peer-to-peer testing, no external server needed) ────────

// YggListener accepts inbound TCP connections over the Yggdrasil overlay,
// addressed to a fixed local port on this node's own Yggdrasil address.
// There is no NAT/firewall traversal concern: any other Yggdrasil node can
// reach this port directly via the mesh, exactly like the existing DialTCP
// path used in reverse.
type YggListener struct {
	port   uint16
	ch     chan inboundSyn
	closed atomic.Bool
}

// ListenTCP starts listening for inbound connections on the given port.
// Call Accept() in a loop to receive connections, and Close() when done.
func ListenTCP(port int) (*YggListener, error) {
	if !running.Load() || mux == nil {
		return nil, fmt.Errorf("not running")
	}
	l := &YggListener{port: uint16(port), ch: make(chan inboundSyn, 8)}
	mux.registerListener(l.port, l.ch)
	return l, nil
}

// Accept blocks until a remote peer connects and the handshake completes.
// Returns an error if the listener was closed or Yggdrasil was stopped.
func (l *YggListener) Accept() (*YggConn, error) {
	for {
		if l.closed.Load() {
			return nil, fmt.Errorf("listener closed")
		}
		if !running.Load() {
			return nil, fmt.Errorf("yggdrasil not running")
		}
		select {
		case syn := <-l.ch:
			conn, err := l.completeHandshake(syn)
			if err != nil {
				continue // malformed/incomplete attempt; keep listening
			}
			return conn, nil
		case <-time.After(500 * time.Millisecond):
			// loop to re-check closed/running
		}
	}
}

func (l *YggListener) completeHandshake(syn inboundSyn) (*YggConn, error) {
	srcIP := net.ParseIP(address).To16()
	if srcIP == nil {
		return nil, fmt.Errorf("bad local addr")
	}

	key := connKey{srcPort: l.port, dstPort: syn.remotePort}
	recvCh := make(chan tcpSegment, 64)

	conn := &tcpConn{
		src:        srcIP,
		dst:        syn.remoteIP,
		srcPort:    l.port,
		dstPort:    syn.remotePort,
		rw:         iprwc,
		recvCh:     recvCh,
		stopReader: make(chan struct{}),
	}
	conn.cond = sync.NewCond(&conn.mu)
	conn.ack = syn.seg.seq + 1
	conn.seq = rand.Uint32()

	mux.register(key, recvCh)
	abort := func() { mux.unregister(key) }

	synAckSeq := conn.seq
	if err := conn.sendTCP(tcpFlagSYN|tcpFlagACK, conn.seq, conn.ack, nil); err != nil {
		abort()
		return nil, err
	}
	conn.seq++

	deadline := time.Now().Add(10 * time.Second)
	for {
		if time.Now().After(deadline) {
			abort()
			return nil, fmt.Errorf("timeout waiting for final ACK")
		}
		select {
		case seg := <-recvCh:
			if seg.flags&tcpFlagACK != 0 && seg.ack == synAckSeq+1 {
				conn.established = true
				conn.muxKey = key
				conn.mux = mux
				conn.sendUnacked = conn.seq
				go conn.backgroundReader()
				return &YggConn{conn: conn}, nil
			}
			if seg.flags&tcpFlagSYN != 0 {
				conn.sendTCP(tcpFlagSYN|tcpFlagACK, synAckSeq, conn.ack, nil)
			}
		case <-time.After(2 * time.Second):
			conn.sendTCP(tcpFlagSYN|tcpFlagACK, synAckSeq, conn.ack, nil)
		}
	}
}

// Close stops the listener. A goroutine blocked in Accept() returns an
// error within ~500ms.
func (l *YggListener) Close() error {
	l.closed.Store(true)
	if mux != nil {
		mux.unregisterListener(l.port)
	}
	return nil
}

// Port returns the local port this listener is bound to.
func (l *YggListener) Port() int { return int(l.port) }

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

// inboundSyn carries enough information about a fresh inbound SYN packet
// (one that doesn't match any already-registered connKey) for a listener's
// Accept() to complete the handshake and learn the remote's full address.
type inboundSyn struct {
	remoteIP   []byte // 16-byte Yggdrasil IPv6 address of the connecting peer
	remotePort uint16
	seg        tcpSegment
}

type packetMux struct {
	rw       io.ReadWriter
	mu       sync.RWMutex
	conns    map[connKey]chan<- tcpSegment
	udpMu    sync.RWMutex
	udpConns map[uint16]chan<- []byte // keyed by our local (dst) port only — UDP is connectionless

	listenMu  sync.RWMutex
	listeners map[uint16]chan inboundSyn // keyed by our local listening port
}

func newPacketMux(rw io.ReadWriter) *packetMux {
	return &packetMux{
		rw:        rw,
		conns:     make(map[connKey]chan<- tcpSegment),
		udpConns:  make(map[uint16]chan<- []byte),
		listeners: make(map[uint16]chan inboundSyn),
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

func (m *packetMux) registerUDP(localPort uint16, ch chan<- []byte) {
	m.udpMu.Lock()
	m.udpConns[localPort] = ch
	m.udpMu.Unlock()
}

func (m *packetMux) unregisterUDP(localPort uint16) {
	m.udpMu.Lock()
	delete(m.udpConns, localPort)
	m.udpMu.Unlock()
}

func (m *packetMux) registerListener(port uint16, ch chan inboundSyn) {
	m.listenMu.Lock()
	m.listeners[port] = ch
	m.listenMu.Unlock()
}

func (m *packetMux) unregisterListener(port uint16) {
	m.listenMu.Lock()
	delete(m.listeners, port)
	m.listenMu.Unlock()
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

		// Must be IPv6 (version=6)
		if len(pkt) < 48 || pkt[0]>>4 != 6 {
			continue
		}

		switch pkt[6] {
		case protoTCP:
			m.handleTCP(pkt)
		case protoUDP:
			m.handleUDP(pkt)
		default:
			continue
		}
	}
}

func (m *packetMux) handleTCP(pkt []byte) {
	if len(pkt) < 60 {
		return
	}
	tcp := pkt[40:]
	if len(tcp) < 20 {
		return
	}

	// Per RFC 793, bytes 0-1 of the TCP header are the SENDER's ("source")
	// port and bytes 2-3 are the port this packet is addressed TO
	// ("destination"). Since we're the receiver here, "source" always means
	// the remote peer's port, and "destination" always means our own port.
	remoteSrcPort := binary.BigEndian.Uint16(tcp[0:2])
	ourDstPort := binary.BigEndian.Uint16(tcp[2:4])

	key := connKey{srcPort: ourDstPort, dstPort: remoteSrcPort}

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
		return
	}

	// No established connection for this key. If it's a fresh SYN (not a
	// SYN-ACK, which would be a reply to something we never sent), check
	// whether a listener is bound to the port it's addressed to.
	if seg.flags&tcpFlagSYN != 0 && seg.flags&tcpFlagACK == 0 {
		m.listenMu.RLock()
		lch, lok := m.listeners[ourDstPort]
		m.listenMu.RUnlock()
		if lok {
			remoteIP := make([]byte, 16)
			copy(remoteIP, pkt[8:24]) // IPv6 header source address
			select {
			case lch <- inboundSyn{remoteIP: remoteIP, remotePort: remoteSrcPort, seg: seg}:
			default:
			}
		}
	}
}

func (m *packetMux) handleUDP(pkt []byte) {
	if len(pkt) < 48 {
		return
	}
	udp := pkt[40:]
	if len(udp) < 8 {
		return
	}

	// dport here is from the remote peer's perspective (its dst == our src,
	// i.e. our local ephemeral port); that's the only thing we key on.
	ourPort := binary.BigEndian.Uint16(udp[2:4])

	payload := make([]byte, len(udp)-8)
	copy(payload, udp[8:])

	m.udpMu.RLock()
	ch, ok := m.udpConns[ourPort]
	m.udpMu.RUnlock()

	if ok {
		select {
		case ch <- payload:
		default:
		}
	}
}

// ── TCP connection ────────────────────────────────────────────────────────────
//
// This is a minimal userspace TCP implementation. Unlike a real TCP stack it
// has no retransmission of data segments and no out-of-order/SACK handling —
// loss on a data segment is not recovered (acceptable for this app's use:
// short-lived SOCKS/XMPP/speedtest connections over a generally-reliable
// mesh transport). It DOES implement basic sliding-window flow control
// (see sendUnacked/cond below): Write() blocks until previously-sent bytes
// are acknowledged by the remote, which is essential both for correctness
// under a slow/throttled link and for producing honest throughput numbers
// in the peer-to-peer speed test.

const sendWindow = 256 * 1024 // bytes-in-flight cap

type tcpConn struct {
	src, dst         []byte
	srcPort, dstPort uint16
	rw               io.Writer

	mu          sync.Mutex
	cond        *sync.Cond // guards/wakes on: recvBuf, sendUnacked, closed
	seq, ack    uint32
	sendUnacked uint32 // seq of the oldest byte we've sent but isn't yet ACKed
	recvBuf     []byte
	closed      bool
	established bool
	muxKey      connKey
	mux         *packetMux
	once        sync.Once
	recvCh      chan tcpSegment // fed by mux; drained exclusively by backgroundReader
	stopReader  chan struct{}
}

// ackAdvanced reports whether newAck is strictly ahead of oldAck in TCP
// sequence-number space (handles the rare wraparound case correctly).
func ackAdvanced(newAck, oldAck uint32) bool {
	return int32(newAck-oldAck) > 0
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

// backgroundReader continuously drains recvCh — the SOLE consumer of that
// channel — for the lifetime of the connection. It updates recvBuf (for
// Read() to pick up), advances sendUnacked on incoming ACKs (to unblock a
// pending Write()), and answers data segments with our own ACK. Splitting
// this from Read() is what lets Write() learn about ACK progress even when
// nothing is calling Read() concurrently (e.g. during a pure upload test).
func (c *tcpConn) backgroundReader() {
	for {
		select {
		case seg, ok := <-c.recvCh:
			if !ok {
				return
			}
			if c.handleSegment(seg) {
				return
			}
		case <-c.stopReader:
			return
		}
	}
}

// handleSegment processes one incoming segment. Returns true if the
// connection has reached a terminal state and backgroundReader should stop.
func (c *tcpConn) handleSegment(seg tcpSegment) (stop bool) {
	c.mu.Lock()

	if seg.flags&tcpFlagRST != 0 {
		c.closed = true
		c.cond.Broadcast()
		c.mu.Unlock()
		return true
	}

	if seg.flags&tcpFlagACK != 0 && ackAdvanced(seg.ack, c.sendUnacked) {
		c.sendUnacked = seg.ack
		c.cond.Broadcast() // wake a Write() blocked on window space
	}

	if seg.flags&tcpFlagFIN != 0 {
		c.closed = true
		c.ack++
		seq := c.seq
		ack := c.ack
		c.cond.Broadcast()
		c.mu.Unlock()
		c.sendTCP(tcpFlagACK, seq, ack, nil)
		if c.mux != nil {
			c.mux.unregister(c.muxKey)
		}
		return true
	}

	if len(seg.payload) > 0 {
		c.recvBuf = append(c.recvBuf, seg.payload...)
		c.ack += uint32(len(seg.payload))
		seq := c.seq
		ack := c.ack
		c.cond.Broadcast() // wake a Read() blocked waiting for data
		c.mu.Unlock()
		c.sendTCP(tcpFlagACK, seq, ack, nil)
		return false
	}

	c.mu.Unlock()
	return false
}

func (c *tcpConn) Read(buf []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for len(c.recvBuf) == 0 && !c.closed {
		c.cond.Wait()
	}
	if len(c.recvBuf) > 0 {
		n := copy(buf, c.recvBuf)
		c.recvBuf = c.recvBuf[n:]
		return n, nil
	}
	return 0, io.EOF
}

// Write sends data, splitting it into segments of at most 1400 bytes and
// pacing them with a sliding window: it blocks until enough previously-sent
// bytes have been ACKed by the remote before sending more, so the call
// genuinely reflects the achievable network throughput instead of just the
// rate of handing packets to the local Yggdrasil core's internal queue.
func (c *tcpConn) Write(data []byte) (int, error) {
	const maxSeg = 1400
	sent := 0
	for sent < len(data) {
		c.mu.Lock()
		for !c.closed {
			inFlight := c.seq - c.sendUnacked
			if inFlight < sendWindow {
				break
			}
			c.cond.Wait()
		}
		if c.closed {
			c.mu.Unlock()
			return sent, io.EOF
		}

		avail := uint32(sendWindow) - (c.seq - c.sendUnacked)
		chunk := uint32(maxSeg)
		if avail < chunk {
			chunk = avail
		}
		remaining := uint32(len(data) - sent)
		if chunk > remaining {
			chunk = remaining
		}

		seq := c.seq
		ack := c.ack
		c.seq += chunk
		c.mu.Unlock()

		if err := c.sendTCP(tcpFlagACK, seq, ack, data[sent:sent+int(chunk)]); err != nil {
			return sent, err
		}
		sent += int(chunk)
	}
	return sent, nil
}

func (c *tcpConn) Close() error {
	c.once.Do(func() {
		c.mu.Lock()
		c.closed = true
		seq := c.seq
		ack := c.ack
		c.cond.Broadcast()
		c.mu.Unlock()
		c.sendTCP(tcpFlagFIN|tcpFlagACK, seq, ack, nil)
		if c.mux != nil {
			c.mux.unregister(c.muxKey)
		}
		close(c.stopReader)
	})
	return nil
}

func (c *tcpConn) sendTCP(flags uint8, seq, ack uint32, data []byte) error {
	seg := buildTCP(c.src, c.dst, c.srcPort, c.dstPort, flags, seq, ack, data)
	pkt := buildIPv6Proto(c.src, c.dst, protoTCP, seg)
	_, err := c.rw.Write(pkt)
	return err
}

// ── Packet builders ───────────────────────────────────────────────────────────

const (
	tcpFlagFIN = 0x01
	tcpFlagSYN = 0x02
	tcpFlagRST = 0x04
	tcpFlagACK = 0x10

	protoTCP = 6
	protoUDP = 17
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
	cs := pseudoChecksum(src, dst, seg, protoTCP)
	binary.BigEndian.PutUint16(seg[16:18], cs)
	return seg
}

func buildUDP(src, dst []byte, srcPort, dstPort uint16, data []byte) []byte {
	hdr := make([]byte, 8)
	binary.BigEndian.PutUint16(hdr[0:2], srcPort)
	binary.BigEndian.PutUint16(hdr[2:4], dstPort)
	binary.BigEndian.PutUint16(hdr[4:6], uint16(8+len(data)))
	seg := append(hdr, data...)
	// UDP checksum is mandatory under IPv6 (RFC 8200), unlike IPv4.
	cs := pseudoChecksum(src, dst, seg, protoUDP)
	if cs == 0 {
		cs = 0xffff
	}
	binary.BigEndian.PutUint16(seg[6:8], cs)
	return seg
}

func buildIPv6Proto(src, dst []byte, proto byte, payload []byte) []byte {
	hdr := make([]byte, 40)
	hdr[0] = 0x60
	binary.BigEndian.PutUint16(hdr[4:6], uint16(len(payload)))
	hdr[6] = proto
	hdr[7] = 64
	copy(hdr[8:24], src)
	copy(hdr[24:40], dst)
	return append(hdr, payload...)
}

func pseudoChecksum(src, dst, seg []byte, proto byte) uint16 {
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], src)
	copy(pseudo[16:32], dst)
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(len(seg)))
	pseudo[39] = proto
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

// ── UDP connection ────────────────────────────────────────────────────────────

type udpConn struct {
	src, dst         []byte
	srcPort, dstPort uint16
	rw               io.Writer
	recvCh           chan []byte
	mux              *packetMux
	closed           atomic.Bool
	once             sync.Once
}

func (c *udpConn) Write(data []byte) (int, error) {
	if c.closed.Load() {
		return 0, fmt.Errorf("closed")
	}
	pkt := buildIPv6Proto(c.src, c.dst, protoUDP, buildUDP(c.src, c.dst, c.srcPort, c.dstPort, data))
	if _, err := c.rw.Write(pkt); err != nil {
		return 0, err
	}
	return len(data), nil
}

func (c *udpConn) Read(buf []byte) (int, error) {
	for {
		if c.closed.Load() {
			return 0, io.EOF
		}
		select {
		case payload, ok := <-c.recvCh:
			if !ok {
				return 0, io.EOF
			}
			n := copy(buf, payload)
			return n, nil
		case <-time.After(200 * time.Millisecond):
			// Just a periodic wakeup to re-check c.closed; not an error.
		}
	}
}

func (c *udpConn) Close() error {
	c.once.Do(func() {
		c.closed.Store(true)
		if c.mux != nil {
			c.mux.unregisterUDP(c.srcPort)
		}
	})
	return nil
}

// YggUDPConn ───────────────────────────────────────────────────────────────────

type YggUDPConn struct {
	conn *udpConn
}

func (c *YggUDPConn) Read(buf []byte) (int, error)  { return c.conn.Read(buf) }
func (c *YggUDPConn) Write(buf []byte) (int, error) { return c.conn.Write(buf) }
func (c *YggUDPConn) Close() error                  { return c.conn.Close() }

// ── YggConn ───────────────────────────────────────────────────────────────────

type YggConn struct {
	conn *tcpConn
}

func (c *YggConn) Read(buf []byte) (int, error)  { return c.conn.Read(buf) }
func (c *YggConn) Write(buf []byte) (int, error) { return c.conn.Write(buf) }
func (c *YggConn) Close() error                  { return c.conn.Close() }

// GetPeersJSON returns JSON array of peers with their online status.
func GetPeersJSON() string {
	if !running.Load() || yggCore == nil {
		return "[]"
	}
	type peerInfo struct {
		URI string `json:"uri"`
		Up  bool   `json:"up"`
	}
	peers := yggCore.GetPeers()
	result := make([]peerInfo, 0, len(peers))
	for _, p := range peers {
		result = append(result, peerInfo{URI: p.URI, Up: p.Up})
	}
	data, err := json.Marshal(result)
	if err != nil {
		return "[]"
	}
	return string(data)
}
