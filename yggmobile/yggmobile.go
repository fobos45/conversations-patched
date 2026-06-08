// Package yggmobile provides a gomobile-compatible API for starting an
// embedded Yggdrasil node and a SOCKS5 proxy that tunnels TCP connections
// through the Yggdrasil overlay network.
//
// Exported API (gomobile-visible):
//
//	Start(peersJSON string, socksPort int) error
//	Stop()
//	GetAddress() string
//	IsRunning() bool
package yggmobile

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"

	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	"github.com/yggdrasil-network/yggdrasil-go/src/multicast"
)

// ── singleton state ──────────────────────────────────────────────────────────

var (
	mu       sync.Mutex
	yggCore  *core.Core
	yggMcast *multicast.Multicast
	listener net.Listener
	running  atomic.Bool
	address  string
)

// ── Public API (exported to gomobile) ────────────────────────────────────────

// Start launches the Yggdrasil node and a SOCKS5 proxy.
//
//	peersJSON – JSON array of peer URIs, e.g. ["tcp://de1.mimir.im:7743?key=..."]
//	socksPort – localhost port for the SOCKS5 proxy, e.g. 1080
func Start(peersJSON string, socksPort int) error {
	mu.Lock()
	defer mu.Unlock()

	if running.Load() {
		return nil
	}

	// Parse peers
	var peers []string
	if err := json.Unmarshal([]byte(peersJSON), &peers); err != nil {
		return fmt.Errorf("invalid peersJSON: %w", err)
	}

	// Build config
	cfg := config.GenerateConfig()
	cfg.Peers = peers
	cfg.IfName = "none" // no TUN/VPN interface
	cfg.AdminListen = "none"

	// Start core
	c, err := core.New(cfg, nil)
	if err != nil {
		return fmt.Errorf("yggdrasil core: %w", err)
	}
	yggCore = c
	address = c.Address().String()

	// Start multicast discovery (optional, best-effort)
	m, err := multicast.New(c, cfg, nil)
	if err == nil {
		yggMcast = m
	}

	// Start SOCKS5 server
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
	if err != nil {
		_ = c.Stop()
		return fmt.Errorf("socks5 listen: %w", err)
	}
	listener = ln

	running.Store(true)
	go serveSocks5(ln, c)

	log.Printf("yggmobile: started, address=%s socks5=127.0.0.1:%d", address, socksPort)
	return nil
}

// Stop shuts down the SOCKS5 proxy and the Yggdrasil node.
func Stop() {
	mu.Lock()
	defer mu.Unlock()

	if !running.Load() {
		return
	}
	running.Store(false)

	if listener != nil {
		_ = listener.Close()
		listener = nil
	}
	if yggMcast != nil {
		_ = yggMcast.Stop()
		yggMcast = nil
	}
	if yggCore != nil {
		_ = yggCore.Stop()
		yggCore = nil
	}
	address = ""
	log.Println("yggmobile: stopped")
}

// GetAddress returns the Yggdrasil IPv6 address of this node.
func GetAddress() string {
	return address
}

// IsRunning returns true if the node is currently active.
func IsRunning() bool {
	return running.Load()
}

// ── SOCKS5 server ─────────────────────────────────────────────────────────────

func serveSocks5(ln net.Listener, c *core.Core) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			if running.Load() {
				log.Printf("yggmobile: socks5 accept error: %v", err)
			}
			return
		}
		go handleSocks5(conn, c)
	}
}

func handleSocks5(client net.Conn, c *core.Core) {
	defer client.Close()

	// Greeting
	buf := make([]byte, 2)
	if _, err := io.ReadFull(client, buf); err != nil {
		return
	}
	if buf[0] != 0x05 {
		return
	}
	nMethods := int(buf[1])
	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	// No auth
	if _, err := client.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// Request
	header := make([]byte, 4)
	if _, err := io.ReadFull(client, header); err != nil {
		return
	}
	if header[0] != 0x05 || header[1] != 0x01 { // only CONNECT
		_, _ = client.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch header[3] {
	case 0x01: // IPv4
		addr := make([]byte, 4)
		if _, err := io.ReadFull(client, addr); err != nil {
			return
		}
		host = net.IP(addr).String()
	case 0x04: // IPv6
		addr := make([]byte, 16)
		if _, err := io.ReadFull(client, addr); err != nil {
			return
		}
		host = "[" + net.IP(addr).String() + "]"
	case 0x03: // domain
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(client, lenBuf); err != nil {
			return
		}
		domain := make([]byte, lenBuf[0])
		if _, err := io.ReadFull(client, domain); err != nil {
			return
		}
		host = string(domain)
	default:
		return
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(client, portBuf); err != nil {
		return
	}
	port := int(portBuf[0])<<8 | int(portBuf[1])
	target := fmt.Sprintf("%s:%d", host, port)

	// Dial via Yggdrasil
	dialer := c.Dialer()
	remote, err := dialer.DialContext(context.Background(), "tcp", target)
	if err != nil {
		log.Printf("yggmobile: dial %s failed: %v", target, err)
		_, _ = client.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	// Success
	_, _ = client.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// Pipe
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, client)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, remote)
		done <- struct{}{}
	}()
	<-done
}
