package eu.siacs.conversations.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;
import eu.siacs.conversations.ui.YggdrasilPeersActivity;
import java.util.HashSet;
import java.util.Set;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages an embedded Yggdrasil node (via yggmobile Go library) and a
 * pure-Java SOCKS5 proxy that tunnels TCP over Yggdrasil IP packets.
 *
 * Architecture:
 *   XMPP client → SOCKS5 (Java, localhost:1080)
 *              → raw IPv6 packets via yggmobile.ReadPacket/WritePacket
 *              → Yggdrasil overlay network
 */
public class YggdrasilManager {

    public static final String TAG = "YggdrasilManager";
    public static final int SOCKS_PORT = 1080;

    private static final List<String> DEFAULT_PEERS = Arrays.asList(
        "tcp://de1.mimir.im:7743?key=1bb8affffffff5ef2b5157b691dc1dd13875c1ec90e789e73bce03af983c4420",
        "tcp://de2.mimir.im:7743?key=0dedeefeffe7e36dd503d83ac8314859ef2601e0841b6d95fb6168501413c58e",
        "tcp://sk1.mimir.im:7743?key=0000000003782d918d36b649e77d70a80322b22be41d4b25455bd81f6e58580f",
        "tcp://sk2.mimir.im:7743?key=00ffed7fdfffa148ab3b01a9c53c20a7bcc8683f621598943f364fcdba034bef",
        "tcp://us1.mimir.im:7743?key=00ff9bffdbffdd6bd9a2151915d9474545c50d324f7b282bff33ef7c402ebe94",
        "tcp://45.95.202.21:12403",
        "tcp://51.15.204.214:12345",
        "tcp://62.210.85.80:39565"
    );

    private static final YggdrasilManager INSTANCE = new YggdrasilManager();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private String lastError = "";
    private android.content.Context appContext;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private YggdrasilManager() {}

    public static YggdrasilManager getInstance() { return INSTANCE; }

    public String getLastError() { return lastError; }

    public void start(Context context) {
        if (running.get()) return;
        appContext = context.getApplicationContext();
        new Thread(() -> startInternal(), "YggdrasilStart").start();
    }

    private synchronized void startInternal() {
        if (running.get()) return;
        try {
            List<String> peerList = YggdrasilPeersActivity.getEnabledPeers(appContext);
            if (peerList.isEmpty()) peerList = DEFAULT_PEERS;
            String peers = String.join("\n", peerList);
            Log.i(TAG, "startInternal: starting Yggdrasil node with " + peerList.size() + " peer(s):");
            for (String p : peerList) Log.i(TAG, "startInternal:   - " + p);
            yggmobile.Yggmobile.start(peers);
            // Give gVisor stack a moment to initialize
            Thread.sleep(500);
            String addr = yggmobile.Yggmobile.getAddress();
            Log.i(TAG, "startInternal: Yggdrasil address: " + addr);
            lastError = "";

            // Start pure-Java SOCKS5 proxy
            executor = Executors.newCachedThreadPool();
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            serverSocket.bind(new InetSocketAddress(loopback, SOCKS_PORT));
            running.set(true);
            Log.i(TAG, "startInternal: SOCKS5 proxy on 127.0.0.1:" + SOCKS_PORT);
            executor.submit(this::acceptLoop);
            registerNetworkCallback(appContext);

        } catch (Throwable e) {
            StringBuilder sb = new StringBuilder();
            Throwable t = e;
            while (t != null) {
                sb.append(t.getClass().getName()).append(": ")
                  .append(t.getMessage()).append("\n");
                t = t.getCause();
                if (t != null) sb.append("caused by:\n");
            }
            lastError = sb.toString();
            Log.e(TAG, "startInternal: failed: " + lastError);
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                executor.submit(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) Log.w(TAG, "accept error: " + e.getMessage());
            }
        }
    }

    /**
     * SOCKS5 handler. For Yggdrasil targets (200::/7) we open a direct
     * TCP connection — the OS will route it through the Yggdrasil TUN
     * interface if one exists, or we fall back to a raw-packet approach.
     *
     * Since we have no TUN interface, we connect directly by IPv6 address.
     * The Yggdrasil node handles routing at the overlay level.
     */
    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Greeting
            int ver = in.read();
            if (ver != 5) { client.close(); return; }
            int nMethods = in.read();
            byte[] methods = new byte[nMethods];
            readFully(in, methods);
            out.write(new byte[]{5, 0}); out.flush();

            // Request
            if (in.read() != 5) { client.close(); return; }
            int cmd = in.read(); in.read(); // RSV
            int atyp = in.read();
            if (cmd != 1) {
                out.write(new byte[]{5,7,0,1,0,0,0,0,0,0}); out.flush();
                client.close(); return;
            }

            String host;
            if (atyp == 1) {
                byte[] b = new byte[4]; readFully(in, b);
                host = InetAddress.getByAddress(b).getHostAddress();
            } else if (atyp == 4) {
                byte[] b = new byte[16]; readFully(in, b);
                host = InetAddress.getByAddress(b).getHostAddress();
            } else if (atyp == 3) {
                int len = in.read();
                byte[] b = new byte[len]; readFully(in, b);
                host = new String(b);
            } else { client.close(); return; }

            int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

            // Connect via Yggdrasil overlay network
            Socket remote;
            try {
                yggmobile.YggConn yggConn = yggmobile.Yggmobile.dialTCP(host, port);
                remote = new YggSocket(yggConn);
            } catch (Exception e) {
                Log.w(TAG, "ygg dial " + host + ":" + port + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage() + ", trying direct");
                try {
                    remote = new Socket();
                    remote.connect(new InetSocketAddress(host, port), 10000);
                    } catch (IOException e2) {
                    Log.w(TAG, "direct connect also failed: " + e2.getMessage());
                    out.write(new byte[]{5,4,0,1,0,0,0,0,0,0}); out.flush();
                    client.close(); return;
                }
            }

            out.write(new byte[]{5,0,0,1,0,0,0,0,0,0}); out.flush();

            client.setSoTimeout(0); remote.setSoTimeout(0);
            InputStream rIn = remote.getInputStream();
            OutputStream rOut = remote.getOutputStream();

            Socket fc = client, fr = remote;
            executor.submit(() -> pipe(in, rOut, fc, fr));
            pipe(rIn, out, fr, fc);

        } catch (Exception e) {
            Log.d(TAG, "client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Returns a map from peer URI to a two-element long array:
     *   [0] = 1 if the peer is currently up, 0 otherwise
     *   [1] = latency in milliseconds, or -1 if not yet measured
     */
    public Set<String> getConnectedPeers() {
        if (!isRunning()) return new HashSet<>();
        try {
            String json = yggmobile.Yggmobile.getPeersJSON();
            Set<String> connected = new HashSet<>();
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                if (obj.optBoolean("up", false)) {
                    connected.add(obj.getString("uri"));
                }
            }
            return connected;
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    public void updatePeers(Context context) {
        if (!isRunning()) {
            Log.i(TAG, "updatePeers: not running, ignoring");
            return;
        }
        List<String> enabled = YggdrasilPeersActivity.getEnabledPeers(context);
        Log.i(TAG, "updatePeers: restarting node with " + enabled.size() + " enabled peer(s):");
        for (String p : enabled) Log.i(TAG, "updatePeers:   - " + p);
        // Restart with new peer list
        stop();
        start(context);
    }

    public synchronized void stop() {
        if (!running.get()) return;
        Log.i(TAG, "stop: stopping Yggdrasil node and SOCKS proxy");
        running.set(false);
        unregisterNetworkCallback();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (executor != null) executor.shutdownNow();
        YggdrasilCallRelay.getInstance().shutdownAll();
        yggmobile.Yggmobile.stop();
        Log.i(TAG, "stop: stopped");
    }

    public boolean isRunning() { return running.get(); }

    private static void pipe(InputStream src, OutputStream dst,
                              Socket a, Socket b) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = src.read(buf)) != -1) { dst.write(buf, 0, n); dst.flush(); }
        } catch (IOException ignored) {
        } finally {
            try { a.close(); } catch (IOException ignored) {}
            try { b.close(); } catch (IOException ignored) {}
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("EOF");
            off += n;
        }
    }

    // ── Network change diagnostics ───────────────────────────────────────────
    //
    // Logs WiFi <-> mobile transitions and dumps the live Yggdrasil peer
    // table right at the moment of transition, so adb logcat shows exactly
    // how peer links react (drop / reconnect) when the underlying network
    // path changes mid-session.

    private void registerNetworkCallback(Context context) {
        if (context == null) return;
        try {
            connectivityManager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                Log.w(TAG, "[net] ConnectivityManager unavailable, skipping network logging");
                return;
            }
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    Log.i(TAG, "[net] onAvailable network=" + network);
                    logPeerSnapshot("net-available");
                }

                @Override
                public void onLost(Network network) {
                    Log.w(TAG, "[net] onLost network=" + network);
                    logPeerSnapshot("net-lost");
                }

                @Override
                public void onCapabilitiesChanged(
                        final Network network, final NetworkCapabilities caps) {
                    final String transport = describeTransport(caps);
                    final boolean validated =
                            caps != null
                                    && caps.hasCapability(
                                            NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    Log.i(TAG, "[net] onCapabilitiesChanged network=" + network
                            + " transport=" + transport + " validated=" + validated);
                    logPeerSnapshot("net-capabilities-changed(" + transport + ")");
                }

                @Override
                public void onUnavailable() {
                    Log.w(TAG, "[net] onUnavailable");
                }
            };
            final NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback);
            Log.i(TAG, "[net] network callback registered");
        } catch (final Exception e) {
            Log.w(TAG, "[net] failed to register network callback: " + e.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                Log.i(TAG, "[net] network callback unregistered");
            } catch (final Exception ignored) {
            }
        }
        networkCallback = null;
    }

    private static String describeTransport(final NetworkCapabilities caps) {
        if (caps == null) return "unknown";
        final StringBuilder sb = new StringBuilder();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) sb.append("WIFI ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) sb.append("CELLULAR ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) sb.append("ETHERNET ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.append("VPN ");
        return sb.length() == 0 ? "OTHER" : sb.toString().trim();
    }

    /** Dumps the current Yggdrasil peer table (uri/up) to logcat, tagged with a reason. */
    private void logPeerSnapshot(final String reason) {
        if (!isRunning()) {
            Log.i(TAG, "[net] peer snapshot (" + reason + "): node not running");
            return;
        }
        try {
            final String json = yggmobile.Yggmobile.getPeersJSON();
            Log.i(TAG, "[net] peer snapshot (" + reason + "): " + json);
        } catch (final Exception e) {
            Log.w(TAG, "[net] could not get peer snapshot: " + e.getMessage());
        }
    }
}
