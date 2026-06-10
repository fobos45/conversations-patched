// Package yggmobile provides a gomobile-compatible API for an embedded
// Yggdrasil node with a SOCKS5 proxy.
package yggmobile

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"

	golog "github.com/gologme/log"
	"github.com/yggdrasil-network/yggdrasil-go/src/config"
	"github.com/yggdrasil-network/yggdrasil-go/src/core"
	"github.com/yggdrasil-network/yggdrasil-go/src/ipv6rwc"

	"github.com/sagernet/gvisor/pkg/buffer"
	"github.com/sagernet/gvisor/pkg/tcpip"
	"github.com/sagernet/gvisor/pkg/tcpip/adapters/gonet"
	"github.com/sagernet/gvisor/pkg/tcpip/header"
	"github.com/sagernet/gvisor/pkg/tcpip/link/channel"
	"github.com/sagernet/gvisor/pkg/tcpip/network/ipv6"
	"github.com/sagernet/gvisor/pkg/tcpip/stack"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/tcp"
	"github.com/sagernet/gvisor/pkg/tcpip/transport/udp"
)

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

func Start(peers string, socksPort int) (retErr error) {
	mu.Lock()
	defer mu.Unlock()
	if running.Load() {
		return nil
	}

	// Recover from any panic (e.g. SELinux blocking somaxconn)
	defer func() {
		if r := recover(); r != nil {
			retErr = fmt.Errorf("panic: %v", r)
		}
	}()

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
		return fmt.Errorf("yggdrasil core: %w", err)
	}
	address = yggCore.Address().String()
	log.Printf("yggmobile: address=%s", address)

	iprwc = ipv6rwc.NewReadWriteCloser(yggCore)

	// gVisor network stack
	netStack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})

	mtu := uint32(iprwc.MaxMTU())
	ep = channel.New(512, mtu, "")
	if tcpErr := netStack.CreateNIC(nicID, ep); tcpErr != nil {
		return fmt.Errorf("create NIC: %v", tcpErr)
	}

	yggAddr := tcpip.AddrFromSlice(yggCore.Address().To16())
	protoAddr := tcpip.ProtocolAddress{
		Protocol:          ipv6.ProtocolNumber,
		AddressWithPrefix: yggAddr.WithPrefix(),
	}
	if tcpErr := netStack.AddProtocolAddress(nicID, protoAddr, stack.AddressProperties{}); tcpErr != nil {
		return fmt.Errorf("add address: %v", tcpErr)
	}
	netStack.AddRoute(tcpip.Route{
		Destination: header.IPv6EmptySubnet,
		NIC:         nicID,
	})

	go pumpYggToStack(iprwc, ep, stopCh)
	go pumpStackToYgg(ep, iprwc, stopCh)

	// Use ListenConfig with explicit backlog to avoid reading somaxconn
	lc := net.ListenConfig{}
	ln, err := lc.Listen(context.Background(), "tcp", fmt.Sprintf("127.0.0.1:%d", socksPort))
	if err != nil {
		yggCore.Stop()
		return fmt.Errorf("socks5 listen: %w", err)
	}
	socksLn = ln
	running.Store(true)

	go serveSocks5(ln, netStack)
	log.Printf("yggmobile: SOCKS5 on 127.0.0.1:%d", socksPort)
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
	if socksLn != nil { socksLn.Close(); socksLn = nil }
	if netStack != nil { netStack.Close(); netStack = nil }
	if iprwc != nil { iprwc.Close(); iprwc = nil }
	if yggCore != nil { yggCore.Stop(); yggCore = nil }
	address = ""
}

func GetAddress() string { return address }
func IsRunning() bool    { return running.Load() }

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

	hdr := make([]byte, 2)
	if _, err := io.ReadFull(client, hdr); err != nil || hdr[0] != 5 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(client, methods); err != nil {
		return
	}
	client.Write([]byte{5, 0})

	req := make([]byte, 4)
	if _, err := io.ReadFull(client, req); err != nil || req[1] != 1 {
		client.Write([]byte{5, 7, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}

	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		io.ReadFull(client, b)
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		io.ReadFull(client, b)
		host = net.IP(b).String()
	case 3:
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

	addr := tcpip.AddrFromSlice(ip)
	fullAddr := tcpip.FullAddress{NIC: nicID, Addr: addr, Port: port}
	remote, err := gonet.DialContextTCP(context.Background(), s, fullAddr, ipv6.ProtocolNumber)
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
