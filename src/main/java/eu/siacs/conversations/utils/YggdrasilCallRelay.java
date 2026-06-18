package eu.siacs.conversations.utils;

import android.util.Log;
import eu.siacs.conversations.Config;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
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
 * Bridges WebRTC's ICE/TURN traffic (plain UDP opened directly on the system
 * network stack) to the embedded, app-internal Yggdrasil overlay used by
 * {@link YggdrasilManager}.
 *
 * <h3>Why this is needed</h3>
 * The embedded Yggdrasil client has no TUN interface, so the OS has no route
 * to 200::/7 addresses. WebRTC opens its own UDP sockets via the OS and can
 * therefore never reach a TURN server living in the Yggdrasil overlay. This
 * relay rewrites every TURN ICE server to point at {@code 127.0.0.1:<port>}
 * and transparently forwards datagrams between WebRTC and the real TURN server
 * via {@code yggmobile.DialUDP}.
 *
 * <h3>STUN servers are intentionally excluded</h3>
 * When ICE transport policy is RELAY (forced for Yggdrasil accounts), STUN
 * servers only produce srflx candidates which are immediately discarded.
 * Including them in the list creates a second WebRTC ICE socket that sends
 * to the same loopback bridge port as the TURN socket; with two concurrent
 * writers the naive single-endpoint tracking breaks. Excluding stun/stuns
 * entries leaves only the TURN server, which is the only one needed.
 *
 * <h3>Transaction-ID routing</h3>
 * Even with a single TURN server, WebRTC may send STUN/TURN requests from
 * slightly different timing contexts. We parse the STUN Magic Cookie (bytes
 * 4–7 == 0x2112A442) and the 12-byte Transaction ID (bytes 8–19) from every
 * outbound datagram. When a response arrives from Yggdrasil we look up the
 * Transaction ID in a map to find the exact WebRTC source endpoint that sent
 * the corresponding request. For non-STUN datagrams (e.g. TURN ChannelData
 * frames which lack the magic cookie) we fall back to the most recently seen
 * WebRTC endpoint.
 */
public class YggdrasilCallRelay {

    private static final String TAG = "YggdrasilCallRelay";

    private static final Pattern URI_PATTERN =
            Pattern.compile(
                    "^(stun|stuns|turn|turns):(\\[[^\\]]+\\]|[^:?]+):(\\d+)"
                            + "(?:\\?transport=(udp|tcp))?$");

    private static final YggdrasilCallRelay INSTANCE = new YggdrasilCallRelay();

    public static YggdrasilCallRelay getInstance() {
        return INSTANCE;
    }

    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();

    private YggdrasilCallRelay() {}

    /**
     * Returns a new list containing only TURN (relay-capable) servers,
     * each rewritten to a local loopback bridge address backed by a live
     * UDP relay into the Yggdrasil overlay.
     */
    public Collection<PeerConnection.IceServer> remapIceServers(
            final Collection<PeerConnection.IceServer> original) {
        final List<PeerConnection.IceServer> result = new ArrayList<>();
        for (final PeerConnection.IceServer server : original) {
            if (server == null || server.urls == null || server.urls.isEmpty()) {
                continue;
            }
            final PeerConnection.IceServer remapped = remapServer(server);
            // null means "skip this server" (stun/stuns or unrelayable)
            if (remapped != null) {
                result.add(remapped);
            }
        }
        Log.d(Config.LOGTAG, TAG + ": remapped " + result.size()
                + " TURN server(s) from " + original.size() + " ICE server(s)");
        return result;
    }

    /**
     * Returns a remapped IceServer for TURN/TURNS entries, or null to skip
     * the server. STUN/STUNS servers are always skipped: with RELAY-only ICE
     * policy they produce no usable candidates and their presence creates a
     * second WebRTC socket writing to the same bridge, breaking reply routing.
     */
    private PeerConnection.IceServer remapServer(final PeerConnection.IceServer server) {
        final String url = server.urls.get(0);
        final Matcher matcher = URI_PATTERN.matcher(url);
        if (!matcher.find()) {
            Log.w(TAG, "skipping unparseable ICE server: " + url);
            return null;
        }
        final String scheme = matcher.group(1);

        // Skip STUN-only servers: useless under RELAY policy, cause bridge conflicts.
        if ("stun".equals(scheme) || "stuns".equals(scheme)) {
            Log.d(Config.LOGTAG, TAG + ": skipping stun server (relay-only mode): " + url);
            return null;
        }

        final String rawHost = matcher.group(2);
        final int port;
        try {
            port = Integer.parseInt(matcher.group(3));
        } catch (final NumberFormatException e) {
            return null;
        }
        final String transport = matcher.group(4);
        if (transport != null && !"udp".equals(transport)) {
            // tcp/tls: not used for RTP/ICE in this app.
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

        final String localUrl = transport == null
                ? String.format("turn:127.0.0.1:%d", bridge.localPort)
                : String.format("turn:127.0.0.1:%d?transport=%s", bridge.localPort, transport);

        final PeerConnection.IceServer.Builder builder =
                PeerConnection.IceServer.builder(localUrl);
        builder.setTlsCertPolicy(server.tlsCertPolicy);
        if (server.username != null) builder.setUsername(server.username);
        if (server.password != null) builder.setPassword(server.password);
        return builder.createIceServer();
    }

    private synchronized Bridge getOrCreateBridge(
            final String remoteHost, final int remotePort) throws Exception {
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

    // ── Bridge ────────────────────────────────────────────────────────────────

    private static final class Bridge {

        // STUN magic cookie per RFC 5389 §6.
        private static final int STUN_MAGIC = 0x2112A442;
        // Minimum STUN message length: 20-byte header.
        private static final int STUN_HEADER_LEN = 20;
        // Transaction ID length in bytes.
        private static final int STUN_TXID_LEN = 12;

        final int localPort;
        private final DatagramSocket localSocket;
        private final yggmobile.YggUDPConn yggConn;
        private volatile boolean closed = false;

        /**
         * Maps STUN Transaction ID (as a hex string) to the WebRTC-side
         * endpoint that sent the corresponding request. Lets us route
         * responses back to the exact socket that asked, even if multiple
         * WebRTC sockets share the same bridge (shouldn't happen with
         * stun/stuns filtered out, but kept for robustness).
         *
         * <p>Entries are cleaned up in pumpFromYggdrasil after first use.
         * The map is bounded: each pending request adds one entry, and each
         * response removes it. Worst-case size equals the number of
         * simultaneously outstanding STUN/TURN requests, typically < 10.
         */
        private final ConcurrentHashMap<String, InetSocketAddress> txMap =
                new ConcurrentHashMap<>();

        /** Fallback for non-STUN frames (TURN ChannelData). */
        private volatile InetSocketAddress lastWebRtcEndpoint;

        Bridge(final String remoteHost, final int remotePort) throws Exception {
            this.yggConn = yggmobile.Yggmobile.dialUDP(remoteHost, remotePort);
            this.localSocket = new DatagramSocket(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            this.localPort = localSocket.getLocalPort();

            final Thread toYgg = new Thread(this::pumpFromWebRtc, "ygg-relay-in-" + localPort);
            final Thread fromYgg = new Thread(this::pumpFromYggdrasil, "ygg-relay-out-" + localPort);
            toYgg.setDaemon(true);
            fromYgg.setDaemon(true);
            toYgg.start();
            fromYgg.start();

            Log.d(Config.LOGTAG, TAG + ": bridge 127.0.0.1:" + localPort
                    + " <-> " + remoteHost + ":" + remotePort + " (Yggdrasil UDP)");
        }

        // ── WebRTC → Yggdrasil ────────────────────────────────────────────────

        private void pumpFromWebRtc() {
            final byte[] buf = new byte[4096];
            while (!closed) {
                try {
                    final DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    localSocket.receive(pkt);
                    final InetSocketAddress src =
                            new InetSocketAddress(pkt.getAddress(), pkt.getPort());
                    final byte[] data = Arrays.copyOf(pkt.getData(), pkt.getLength());

                    // Track STUN request → sender mapping so the response can
                    // be routed back to the correct WebRTC socket.
                    final String txId = extractStunRequestTxId(data);
                    if (txId != null) {
                        txMap.put(txId, src);
                    }
                    lastWebRtcEndpoint = src;

                    yggConn.write(data);
                } catch (final Exception e) {
                    if (!closed) {
                        Log.w(TAG, "relay(webrtc→ygg) port " + localPort + ": " + e.getMessage());
                    }
                }
            }
        }

        // ── Yggdrasil → WebRTC ────────────────────────────────────────────────

        private void pumpFromYggdrasil() {
            final byte[] buf = new byte[4096];
            while (!closed) {
                try {
                    final long n = yggConn.read(buf);
                    if (n <= 0) {
                        continue;
                    }
                    final byte[] data = Arrays.copyOf(buf, (int) n);

                    // Try to route by STUN transaction ID; fall back to last
                    // seen WebRTC endpoint (for ChannelData frames, etc.).
                    InetSocketAddress dest = resolveDestination(data);
                    if (dest == null) {
                        dest = lastWebRtcEndpoint;
                    }
                    if (dest == null) {
                        Log.w(TAG, "relay(ygg→webrtc) no dest yet, dropping " + n + " bytes");
                        continue;
                    }

                    localSocket.send(new DatagramPacket(
                            data, data.length, dest.getAddress(), dest.getPort()));
                } catch (final Exception e) {
                    if (!closed) {
                        Log.w(TAG, "relay(ygg→webrtc) port " + localPort + ": " + e.getMessage());
                    }
                }
            }
        }

        // ── STUN transaction ID helpers ───────────────────────────────────────

        /**
         * If {@code data} is a STUN request (magic cookie present, message
         * class = Request (bits 8,4 of type = 00)), returns its transaction
         * ID as a hex string. Returns null for non-STUN or non-Request frames.
         */
        private static String extractStunRequestTxId(final byte[] data) {
            if (data.length < STUN_HEADER_LEN) return null;
            final ByteBuffer bb = ByteBuffer.wrap(data);
            // bytes 4-7: magic cookie
            final int magic = bb.getInt(4);
            if (magic != STUN_MAGIC) return null;
            // bytes 0-1: message type; class is encoded in bits C1(bit8) and C0(bit4)
            final int msgType = bb.getShort(0) & 0xFFFF;
            final int msgClass = ((msgType >> 7) & 0x02) | ((msgType >> 4) & 0x01);
            // class 0x00 = Request, 0x01 = Indication, 0x02 = Success, 0x03 = Error
            // We register both Request (0x00) and Indication (0x01) because TURN
            // Send Indications also carry a transaction ID we may need to echo.
            if (msgClass != 0x00 && msgClass != 0x01) return null;
            // bytes 8-19: transaction ID
            final StringBuilder sb = new StringBuilder(STUN_TXID_LEN * 2);
            for (int i = 8; i < 20; i++) {
                sb.append(String.format("%02x", data[i] & 0xFF));
            }
            return sb.toString();
        }

        /**
         * If {@code data} is a STUN response, look up its Transaction ID in
         * the pending-request map and return the WebRTC endpoint that sent
         * the original request. Removes the entry from the map (one-shot).
         */
        private InetSocketAddress resolveDestination(final byte[] data) {
            if (data.length < STUN_HEADER_LEN) return null;
            final ByteBuffer bb = ByteBuffer.wrap(data);
            if (bb.getInt(4) != STUN_MAGIC) return null;
            final int msgType = bb.getShort(0) & 0xFFFF;
            final int msgClass = ((msgType >> 7) & 0x02) | ((msgType >> 4) & 0x01);
            // Only Success (0x02) and Error (0x03) responses carry a matching TxID.
            if (msgClass != 0x02 && msgClass != 0x03) return null;
            final StringBuilder sb = new StringBuilder(STUN_TXID_LEN * 2);
            for (int i = 8; i < 20; i++) {
                sb.append(String.format("%02x", data[i] & 0xFF));
            }
            return txMap.remove(sb.toString());
        }

        void close() {
            closed = true;
            try { localSocket.close(); } catch (final Exception ignored) {}
            try { yggConn.close();    } catch (final Exception ignored) {}
        }
    }
}
