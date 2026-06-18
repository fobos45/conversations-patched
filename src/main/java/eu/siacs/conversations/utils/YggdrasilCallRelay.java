package eu.siacs.conversations.utils;

import android.util.Log;
import eu.siacs.conversations.Config;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.webrtc.PeerConnection;

/**
 * Bridges WebRTC's native ICE/STUN/TURN traffic (plain UDP, opened directly
 * on the system network stack) to the embedded, app-internal Yggdrasil
 * overlay used by {@link YggdrasilManager}.
 *
 * <p>The embedded Yggdrasil node has no TUN interface — it only exposes a
 * userspace TCP socket (see {@code yggmobile.DialTCP}, used for the XMPP
 * connection itself via the SOCKS5 proxy). WebRTC's ICE agent does not go
 * through that proxy: it opens its own UDP sockets via the OS, so it can
 * never reach a STUN/TURN server whose address lives inside 200::/7.
 *
 * <p>To fix that without creating a VpnService/TUN interface, every
 * UDP-transport STUN/TURN server discovered via XEP-0215 is rewritten to
 * point at {@code 127.0.0.1:<localPort>} instead of its real Yggdrasil
 * address. A tiny relay thread pair listens on that loopback port and
 * shuttles raw datagrams to/from the real server using {@code
 * yggmobile.DialUDP}. The relay never parses STUN/TURN itself — it is a
 * transparent UDP forwarder, so authentication and protocol semantics
 * between WebRTC and the real server are completely unaffected.
 */
public class YggdrasilCallRelay {

    private static final String TAG = "YggdrasilCallRelay";

    // Matches: stun:host:port | turn:host:port?transport=udp (host optionally
    // bracketed, e.g. [200:f28e:...]). This is exactly the shape produced by
    // im.conversations.android.xmpp.model.disco.external.Services.
    private static final Pattern URI_PATTERN =
            Pattern.compile(
                    "^(stun|stuns|turn|turns):(\\[[^\\]]+\\]|[^:?]+):(\\d+)(?:\\?transport=(udp|tcp))?$");

    private static final YggdrasilCallRelay INSTANCE = new YggdrasilCallRelay();

    public static YggdrasilCallRelay getInstance() {
        return INSTANCE;
    }

    // keyed by "remoteHost:remotePort" so STUN and TURN entries pointing at
    // the same server (the common case) share a single relay/bridge.
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();

    private YggdrasilCallRelay() {}

    /**
     * Returns a new collection of ICE servers where every parseable
     * UDP-transport url has been replaced with a loopback address backed by
     * a live relay bridge into the Yggdrasil overlay. Entries that can't be
     * parsed (e.g. tcp/tls transports, which this app doesn't use for ICE
     * candidates anyway) are passed through unchanged.
     */
    public Collection<PeerConnection.IceServer> remapIceServers(
            final Collection<PeerConnection.IceServer> original) {
        final List<PeerConnection.IceServer> result = new ArrayList<>();
        for (final PeerConnection.IceServer server : original) {
            if (server == null || server.urls == null || server.urls.isEmpty()) {
                continue;
            }
            final PeerConnection.IceServer remapped = remapServer(server);
            result.add(remapped != null ? remapped : server);
        }
        return result;
    }

    private PeerConnection.IceServer remapServer(final PeerConnection.IceServer server) {
        // Every IceServer built by Services.getIceServers() has exactly one
        // url; we keep this method simple instead of trying to support a
        // hypothetical multi-url list.
        final String url = server.urls.get(0);
        final Matcher matcher = URI_PATTERN.matcher(url);
        if (!matcher.find()) {
            Log.w(TAG, "could not parse ICE server url for relay: " + url);
            return null;
        }
        final String scheme = matcher.group(1);
        final String rawHost = matcher.group(2);
        final int port;
        try {
            port = Integer.parseInt(matcher.group(3));
        } catch (final NumberFormatException e) {
            return null;
        }
        final String transport = matcher.group(4);
        if (transport != null && !"udp".equals(transport)) {
            // tcp/tls candidates are disabled app-wide (XEP-0176 doesn't
            // support tcp transport), so there is nothing useful to relay.
            return null;
        }
        final String host = IP.unwrapIPv6(rawHost);

        final Bridge bridge;
        try {
            bridge = getOrCreateBridge(host, port);
        } catch (final Exception e) {
            Log.e(TAG, "could not start relay bridge for " + host + ":" + port, e);
            return null;
        }

        // At this point transport is either null (stun/stuns, which never
        // carry a transport query param) or "udp" (the only transport we
        // support relaying and the only one this app uses for turn/turns).
        final String localUrl = buildLocalUrl(scheme, bridge.localPort, transport);

        final PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(localUrl);
        builder.setTlsCertPolicy(server.tlsCertPolicy);
        if (server.username != null) {
            builder.setUsername(server.username);
        }
        if (server.password != null) {
            builder.setPassword(server.password);
        }
        return builder.createIceServer();
    }

    private static String buildLocalUrl(
            final String scheme, final int localPort, final String transport) {
        if (transport == null) {
            // stun/stuns originally had no transport query parameter
            return String.format("%s:127.0.0.1:%d", scheme, localPort);
        }
        return String.format("%s:127.0.0.1:%d?transport=%s", scheme, localPort, transport);
    }

    private synchronized Bridge getOrCreateBridge(final String remoteHost, final int remotePort)
            throws Exception {
        final String key = remoteHost + ":" + remotePort;
        final Bridge existing = bridges.get(key);
        if (existing != null && !existing.closed) {
            return existing;
        }
        final Bridge bridge = new Bridge(remoteHost, remotePort);
        bridges.put(key, bridge);
        return bridge;
    }

    /** Tears down every relay bridge. Call when the Yggdrasil node stops. */
    public synchronized void shutdownAll() {
        for (final Bridge bridge : bridges.values()) {
            bridge.close();
        }
        bridges.clear();
    }

    private static final class Bridge {

        final int localPort;
        private final DatagramSocket localSocket;
        private final yggmobile.YggUDPConn yggConn;
        private volatile InetSocketAddress lastWebRtcEndpoint;
        private volatile boolean closed = false;

        Bridge(final String remoteHost, final int remotePort) throws Exception {
            this.yggConn = yggmobile.Yggmobile.dialUDP(remoteHost, remotePort);
            this.localSocket =
                    new DatagramSocket(
                            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            this.localPort = localSocket.getLocalPort();
            final Thread fromWebRtc =
                    new Thread(this::pumpFromWebRtc, "ygg-relay-in-" + localPort);
            final Thread fromYgg = new Thread(this::pumpFromYggdrasil, "ygg-relay-out-" + localPort);
            fromWebRtc.setDaemon(true);
            fromYgg.setDaemon(true);
            fromWebRtc.start();
            fromYgg.start();
            Log.d(
                    Config.LOGTAG,
                    TAG
                            + ": relay 127.0.0.1:"
                            + localPort
                            + " <-> "
                            + remoteHost
                            + ":"
                            + remotePort
                            + " (via Yggdrasil)");
        }

        private void pumpFromWebRtc() {
            final byte[] buf = new byte[2048];
            while (!closed) {
                try {
                    final DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    localSocket.receive(packet);
                    lastWebRtcEndpoint =
                            new InetSocketAddress(packet.getAddress(), packet.getPort());
                    final byte[] payload = Arrays.copyOf(packet.getData(), packet.getLength());
                    yggConn.write(payload);
                } catch (final Exception e) {
                    if (!closed) {
                        Log.w(TAG, "relay(webrtc->ygg) error on port " + localPort, e);
                    }
                }
            }
        }

        private void pumpFromYggdrasil() {
            final byte[] buf = new byte[2048];
            while (!closed) {
                try {
                    final long n = yggConn.read(buf);
                    if (n <= 0) {
                        continue;
                    }
                    final InetSocketAddress dest = lastWebRtcEndpoint;
                    if (dest == null) {
                        // Nothing has asked us anything yet; drop.
                        continue;
                    }
                    final DatagramPacket packet =
                            new DatagramPacket(buf, (int) n, dest.getAddress(), dest.getPort());
                    localSocket.send(packet);
                } catch (final Exception e) {
                    if (!closed) {
                        Log.w(TAG, "relay(ygg->webrtc) error on port " + localPort, e);
                    }
                }
            }
        }

        void close() {
            closed = true;
            try {
                localSocket.close();
            } catch (final Exception ignored) {
            }
            try {
                yggConn.close();
            } catch (final Exception ignored) {
            }
        }
    }
}
