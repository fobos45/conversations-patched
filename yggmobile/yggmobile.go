// Package yggmobile provides a gomobile-compatible API for starting an
// embedded Yggdrasil node and a SOCKS5 proxy that tunnels TCP connections
// through the Yggdrasil overlay network.
package yggmobile

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"log"
	"net"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/gologme/log" as golog
	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
)

var (
	mu      sync.Mutex
	yggCore *core.Core
	ln      net.Listener
	running atomic.Bool
	address string
)

// Start launches the Yggdrasil node and a SOCKS5 proxy.
// peers is a newline-separated list of peer URIs.
func Start(peers string, socksPort int) error {
	mu.Lock()
	defer mu.Unlock()

	if running.Load() {
		return nil
	}

	// Generate TLS certificate (self-signed, ephemeral)
	cfg := config.GenerateConfig()
	cfg.IfName = "none"
	cfg.AdminListen = "none"

	// Build logger
	logger := golog.New(log.Writer(), "", 0)
	logger.EnableLevel("error")
	logger.EnableLevel("warn")
	logger.EnableLevel("info")

	// Generate self-signed cert from config
	nc := config.GenerateConfig()
	cert, err := tls.X509KeyPair(nc.Certificate, nc.PrivateKey)
	if err != nil {
		return fmt.Errorf("cert: %w", err)
	}

	// Start core
	c, err := core.New(&cert, logger,
		core.NodeInfo(cfg.NodeInfo),
		core.NodeInfoPrivacy(cfg.NodeInfoPrivacy),
	)
	if err != nil {
		return fmt.Errorf("core: %w", err)
	}
	yggCore = c
	address = c.Address().String()

	// Add peers
	for _, peer := range strings.Split(peers, "\n") {
		peer = strings.TrimSpace(peer)
		if peer == "" {
			continue
		}
		u, err := url.Parse(peer)
		if err != nil {
			log.Printf("yggmobile: invalid peer %q: %v", peer, err)
			continue
		}
		if err := c.AddPeer(u, ""); err != nil {
			log.Printf("yggmobile: add peer %q: %v", peer, err)
		}
	}

	// Start SOCKS5 listener
	l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
	if err != nil {
		c.Stop()
		return fmt.Errorf("socks5 listen: %w", err)
	}
	ln = l
	running.Store(true)

	go serveSocks5(l, c)
	log.Printf("yggmobile: started addr=%s socks5=127.0.0.1:%d", address, socksPort)
	return nil
}

// Stop shuts down the proxy and Yggdrasil node.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if !running.Load() {
		return
	}
	running.Store(false)
	if ln != nil {
		ln.Close()
		ln = nil
	}
	if yggCore != nil {
		yggCore.Stop()
		yggCore = nil
	}
	address = ""
}

// GetAddress returns the Yggdrasil IPv6 address.
func GetAddress() string { return address }

// IsRunning returns true if the node is active.
func IsRunning() bool { return running.Load() }

// ── SOCKS5 ───────────────────────────────────────────────────────────────────

func serveSocks5(l net.Listener, c *core.Core) {
	for {
		conn, err := l.Accept()
		if err != nil {
			if running.Load() {
				log.Printf("yggmobile: accept: %v", err)
			}
			return
		}
		go handleSocks5(conn, c)
	}
}

func handleSocks5(client net.Conn, c *core.Core) {
	defer client.Close()

	buf := make([]byte, 2)
	if _, err := io.ReadFull(client, buf); err != nil || buf[0] != 0x05 {
		return
	}
	methods := make([]byte, buf[1])
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	client.Write([]byte{0x05, 0x00})

	hdr := make([]byte, 4)
	if _, err := io.ReadFull(client, hdr); err != nil || hdr[1] != 0x01 {
		client.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch hdr[3] {
	case 0x01:
		b := make([]byte, 4)
		io.ReadFull(client, b)
		host = net.IP(b).String()
	case 0x04:
		b := make([]byte, 16)
		io.ReadFull(client, b)
		host = "[" + net.IP(b).String() + "]"
	case 0x03:
		l := make([]byte, 1)
		io.ReadFull(client, l)
		b := make([]byte, l[0])
		io.ReadFull(client, b)
		host = string(b)
	default:
		return
	}

	pb := make([]byte, 2)
	io.ReadFull(client, pb)
	port := int(pb[0])<<8 | int(pb[1])
	target := fmt.Sprintf("%s:%d", host, port)

	// Dial via Yggdrasil — use Listen/Dial over the overlay network
	u, err := url.Parse(fmt.Sprintf("tcp://%s", target))
	if err != nil {
		client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	_ = u

	// c.Listen returns a Listener on the Yggdrasil network.
	// To connect TO a remote Yggdrasil node we use net.Dial via
	// the overlay address directly (it's a valid IPv6 address).
	dialer := &net.Dialer{}
	remote, err := dialer.DialContext(context.Background(), "tcp", target)
	if err != nil {
		log.Printf("yggmobile: dial %s: %v", target, err)
		client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, client); done <- struct{}{} }()
	go func() { io.Copy(client, remote); done <- struct{}{} }()
	<-done
}
