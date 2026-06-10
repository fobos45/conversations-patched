// Package yggmobile - Yggdrasil node with userspace TCP via gvisor.
// No system sockets needed for Yggdrasil traffic.
package yggmobile

import (
	"context"
	"fmt"
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

const nicID tcpip.NICID = 1

var (
	mu       sync.Mutex
	yggCore  *core.Core
	iprwc    *ipv6rwc.ReadWriteCloser
	netStack *stack.Stack
	ep       *channel.Endpoint
	running  atomic.Bool
	address  string
	stopCh   chan struct{}
)

// Start launches the Yggdrasil node with a gVisor userspace TCP/IP stack.
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

	// Build gVisor stack
	netStack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})

	mtu := uint32(iprwc.MaxMTU())
	ep = channel.New(512, mtu, "")

	if tcpErr := netStack.CreateNIC(nicID, ep); tcpErr != nil {
		yggCore.Stop()
		return fmt.Errorf("create NIC: %v", tcpErr)
	}

	yggAddr := tcpip.AddrFromSlice(yggCore.Address().To16())
	protoAddr := tcpip.ProtocolAddress{
		Protocol:          ipv6.ProtocolNumber,
		AddressWithPrefix: yggAddr.WithPrefix(),
	}
	if tcpErr := netStack.AddProtocolAddress(nicID, protoAddr, stack.AddressProperties{}); tcpErr != nil {
		yggCore.Stop()
		return fmt.Errorf("add addr: %v", tcpErr)
	}

	netStack.AddRoute(tcpip.Route{
		Destination: header.IPv6EmptySubnet,
		NIC:         nicID,
	})

	// Start packet pumps
	go pumpYggToStack(stopCh)
	go pumpStackToYgg(stopCh)

	running.Store(true)
	log.Printf("yggmobile: started addr=%s mtu=%d", address, mtu)
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
	if netStack != nil { netStack.Close(); netStack = nil }
	if iprwc != nil { iprwc.Close(); iprwc = nil }
	if yggCore != nil { yggCore.Stop(); yggCore = nil }
	address = ""
}

// DialTCP connects to a Yggdrasil IPv6 address via the userspace stack.
func DialTCP(host string, port int) (*YggConn, error) {
	if !running.Load() || netStack == nil {
		return nil, fmt.Errorf("not running")
	}

	ip := net.ParseIP(host)
	if ip == nil {
		addrs, err := net.LookupHost(host)
		if err != nil || len(addrs) == 0 {
			return nil, fmt.Errorf("resolve %s: %w", host, err)
		}
		ip = net.ParseIP(addrs[0])
	}

	ip6 := ip.To16()
	if ip6 == nil {
		return nil, fmt.Errorf("not IPv6: %s", host)
	}

	addr := tcpip.AddrFromSlice(ip6)
	fullAddr := tcpip.FullAddress{
		NIC:  nicID,
		Addr: addr,
		Port: uint16(port),
	}

	conn, err := gonet.DialContextTCP(context.Background(), netStack, fullAddr, ipv6.ProtocolNumber)
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d: %w", host, port, err)
	}
	return &YggConn{conn: conn}, nil
}

func GetAddress() string { return address }
func IsRunning() bool    { return running.Load() }

// ── Packet pumps ────────────────────────────────────────────────────────────

func pumpYggToStack(stop <-chan struct{}) {
	buf := make([]byte, 65535)
	for {
		select {
		case <-stop:
			return
		default:
		}
		n, err := iprwc.Read(buf)
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

func pumpStackToYgg(stop <-chan struct{}) {
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
		_, _ = iprwc.Write(data)
		pkt.DecRef()
	}
}

// ── YggConn ──────────────────────────────────────────────────────────────────

type YggConn struct {
	conn net.Conn
}

func (c *YggConn) Read(buf []byte) (int, error)  { return c.conn.Read(buf) }
func (c *YggConn) Write(buf []byte) (int, error) { return c.conn.Write(buf) }
func (c *YggConn) Close() error                  { return c.conn.Close() }
