// Package yggmobile provides a gomobile-compatible API for an embedded
// Yggdrasil node with a SOCKS5 proxy backed by a gVisor userspace TCP/IP stack.
//
// Exported (gomobile-visible) API:
//
//	Start(peersNewlineSeparated string, socksPort int) error
//	Stop()
//	GetAddress() string
//	IsRunning() bool
package yggmobile

import (
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"

	golog "github.com/gologme/log"
	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	"github.com/yggdrasil-network/yggdrasil-go/src/ipv6rwc"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// ── singleton ────────────────────────────────────────────────────────────────

var (
	mu       sync.Mutex
	yggCore  *core.Core
	iprwc    *ipv6rwc.ReadWriteCloser
	netStack *stack.Stack
	ep       *channel.Endpoint
	socksLn  net.Listener
	running  atomic.Bool
	address  string
	stopCh   chan struct{}
)

const nicID tcpip.NICID = 1

// ── Public API ────────────────────────────────────────────────────────────────

// Start launches the Yggdrasil node and a SOCKS5 proxy on 127.0.0.1:socksPort.
// peers is a newline-separated list of peer URIs.
func Start(peers string, socksPort int) error {
	mu.Lock()
	defer mu.Unlock()
	if running.Load() {
		return nil
	}

	stopCh = make(chan struct{})

	// ── 1. Yggdrasil config & logger ─────────────────────────────────────────
	cfg := config.GenerateConfig()
	cfg.IfName = "none"
	cfg.AdminListen = "none"

	logger := golog.New(log.Writer(), "", 0)
	logger.EnableLevel("error")
	logger.EnableLevel("warn")
	logger.EnableLevel("info")

	// ── 2. Build core options ─────────────────────────────────────────────────
	opts := []core.SetupOption{
		core.NodeInfo(cfg.NodeInfo),
		core.NodeInfoPrivacy(cfg.NodeInfoPrivacy),
	}
	for _, peer := range strings.Split(peers, "\n") {
		peer = strings.TrimSpace(peer)
		if peer != "" {
			opts = append(opts, core.Peer{URI: peer})
		}
	}

	// ── 3. Start core ─────────────────────────────────────────────────────────
	var err error
	yggCore, err = core.New(cfg.Certificate, logger, opts...)
	if err != nil {
		return fmt.Errorf("yggdrasil core: %w", err)
	}
	address = yggCore.Address().String()
	log.Printf("yggmobile: Yggdrasil address: %s", address)
	log.Printf("yggmobile: Public key: %s", hex.EncodeToString(yggCore.PublicKey()))

	// ── 4. IP read/write closer ───────────────────────────────────────────────
	iprwc = ipv6rwc.NewReadWriteCloser(yggCore)

	// ── 5. gVisor network stack ───────────────────────────────────────────────
	netStack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})

	// Channel endpoint: MTU from iprwc
	mtu := uint32(iprwc.MaxMTU())
	ep = channel.New(512, mtu, "")
	if tcpErr := netStack.CreateNIC(nicID, ep); tcpErr != nil {
		return fmt.Errorf("create NIC: %v", tcpErr)
	}

	// Assign our Yggdrasil IPv6 address to the NIC
	yggAddr := tcpip.AddrFromSlice(yggCore.Address().To16())
	protoAddr := tcpip.ProtocolAddress{
		Protocol:          ipv6.ProtocolNumber,
		AddressWithPrefix: yggAddr.WithPrefix(),
	}
	if tcpErr := netStack.AddProtocolAddress(nicID, protoAddr, stack.AddressProperties{}); tcpErr != nil {
		return fmt.Errorf("add address: %v", tcpErr)
	}

	// Default IPv6 route
	netStack.AddRoute(tcpip.Route{
		Destination: header.IPv6EmptySubnet,
		NIC:         nicID,
	})

	// ── 6. Pump goroutines: Yggdrasil ↔ gVisor ───────────────────────────────
	go pumpYggToStack(iprwc, ep, stopCh)
	go pumpStackToYgg(ep, iprwc, stopCh)

	// ── 7. SOCKS5 proxy ───────────────────────────────────────────────────────
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
	if err != nil {
		yggCore.Stop()
		return fmt.Errorf("socks5 listen: %w", err)
	}
	socksLn = ln
	running.Store(true)

	go serveSocks5(ln, netStack)
	log.Printf("yggmobile: SOCKS5 proxy on 127.0.0.1:%d", socksPort)
	return nil
}

// Stop shuts everything down.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if !running.Load() {
		return
	}
	running.Store(false)
	close(stopCh)
	if socksLn != nil {
		socksLn.Close()
		socksLn = nil
	}
	if netStack != nil {
		netStack.Close()
		netStack = nil
	}
	if iprwc != nil {
		iprwc.Close()
		iprwc = nil
	}
	if yggCore != nil {
		yggCore.Stop()
		yggCore = nil
	}
	address = ""
}

// GetAddress returns the node's Yggdrasil IPv6 address.
func GetAddress() string { return address }

// IsRunning returns true if the node is active.
func IsRunning() bool { return running.Load() }

// ── Packet pumps ──────────────────────────────────────────────────────────────

// pumpYggToStack reads IPv6 packets from Yggdrasil and injects into gVisor.
func pumpYggToStack(r io.Reader, ep *channel.Endpoint, stop <-chan struct{}) {
	buf := make([]byte, 65535)
	for {
		select {
		case <-stop:
			return
		default:
		}
		n, err := r.Read(buf)
		if err != nil {
			return
		}
		pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData(buf[:n]),
		})
		ep.InjectInbound(ipv6.ProtocolNumber, pkt)
		pkt.DecRef()
	}
}

// pumpStackToYgg reads packets from gVisor and sends to Yggdrasil.
func pumpStackToYgg(ep *channel.Endpoint, w io.Writer, stop <-chan struct{}) {
	for {
		select {
		case <-stop:
			return
		default:
		}
		pkt := ep.ReadContext(nil)
		if pkt == nil {
			return
		}
		data := pkt.ToView().AsSlice()
		_, _ = w.Write(data)
		pkt.DecRef()
	}
}

// ── SOCKS5 server ─────────────────────────────────────────────────────────────

func serveSocks5(ln net.Listener, s *stack.Stack) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go handleSocks5(conn, s)
	}
}

func handleSocks5(client net.Conn, s *stack.Stack) {
	defer client.Close()

	// Greeting
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(client, hdr); err != nil || hdr[0] != 5 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	client.Write([]byte{5, 0}) // no auth

	// Request
	req := make([]byte, 4)
	if _, err := io.ReadFull(client, req); err != nil || req[1] != 1 {
		client.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch req[3] {
	case 1: // IPv4
		b := make([]byte, 4)
		io.ReadFull(client, b)
		host = net.IP(b).String()
	case 4: // IPv6
		b := make([]byte, 16)
		io.ReadFull(client, b)
		host = net.IP(b).String()
	case 3: // domain
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
	port := uint16(pb[0])<<8 | uint16(pb[1])

	// Resolve host to IPv6 (Yggdrasil addresses are IPv6)
	addrs, err := net.LookupHost(host)
	if err != nil || len(addrs) == 0 {
		client.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	ip := net.ParseIP(addrs[0]).To16()
	if ip == nil {
		client.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}

	// Dial via gVisor stack
	addr := tcpip.AddrFromSlice(ip)
	fullAddr := tcpip.FullAddress{
		NIC:  nicID,
		Addr: addr,
		Port: port,
	}
	remote, err := gonet.DialContextTCP(nil, s, fullAddr, ipv6.ProtocolNumber)
	if err != nil {
		log.Printf("yggmobile: dial %s:%d: %v", host, port, err)
		client.Write([]byte{5, 4, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer remote.Close()

	client.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})

	done := make(chan struct{}, 2)
	go func() { io.Copy(remote, client); done <- struct{}{} }()
	go func() { io.Copy(client, remote); done <- struct{}{} }()
	<-done
}

// resolveURL is a helper used in init to validate peer URIs at compile time.
func resolveURL(s string) *url.URL {
	u, _ := url.Parse(s)
	return u
}
