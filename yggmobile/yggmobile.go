// Package yggmobile - Yggdrasil node only, no TCP sockets in Go.
// SOCKS5 proxy is handled entirely in Java.
package yggmobile

import (
	"fmt"
	"log"
	"strings"
	"sync"
	"sync/atomic"

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
)

// Start launches the Yggdrasil node only.
// peers is newline-separated list of peer URIs.
func Start(peers string) error {
	mu.Lock()
	defer mu.Unlock()
	if running.Load() {
		return nil
	}

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
	if iprwc != nil { iprwc.Close(); iprwc = nil }
	if yggCore != nil { yggCore.Stop(); yggCore = nil }
	address = ""
}

// ReadPacket reads one IPv6 packet from Yggdrasil. Blocks until a packet arrives.
func ReadPacket() ([]byte, error) {
	if iprwc == nil {
		return nil, fmt.Errorf("not started")
	}
	buf := make([]byte, 65535)
	n, err := iprwc.Read(buf)
	if err != nil {
		return nil, err
	}
	return buf[:n], nil
}

// WritePacket sends one IPv6 packet into Yggdrasil.
func WritePacket(data []byte) error {
	if iprwc == nil {
		return fmt.Errorf("not started")
	}
	_, err := iprwc.Write(data)
	return err
}

func GetAddress() string { return address }
func IsRunning() bool    { return running.Load() }
