package eu.siacs.conversations.utils;

import android.content.Context;
import android.util.Log;

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

import eu.siacs.conversations.Config;

/**
 * Manages an embedded Yggdrasil node and a local SOCKS5 proxy that tunnels
 * TCP connections through it.
 *
 * Usage:
 *   YggdrasilManager.getInstance().start(context);
 *   // then connect via SOCKS5 at 127.0.0.1:1080
 *   YggdrasilManager.getInstance().stop();
 *
 * The SOCKS5 proxy accepts connections on localhost:SOCKS_PORT and forwards
 * them to the destination host:port via the Yggdrasil network using the
 * yggdrasil-go gomobile bindings (yggdrasil.Core + yggdrasil.Dialer).
 *
 * If the gomobile library is not present the manager falls back to a plain
 * TCP forward so the app can still compile and run without Yggdrasil support.
 */
public class YggdrasilManager {

    public static final String TAG = "YggdrasilManager";
    public static final int SOCKS_PORT = 1080;

    // Default bootstrap peers (same as Mimir APK)
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

    // Yggdrasil gomobile objects — kept as Object to avoid compile errors
    // when the library is absent.  Cast to the real types when available.
    private Object yggCore;    // yggdrasil.Core
    private Object yggDialer;  // yggdrasil.Dialer

    private YggdrasilManager() {}

    public static YggdrasilManager getInstance() {
        return INSTANCE;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public synchronized void start(Context context) {
        if (running.get()) return;

        executor = Executors.newCachedThreadPool();

        boolean yggAvailable = startYggdrasilCore();
        if (!yggAvailable) {
            Log.w(TAG, "Yggdrasil gomobile library not found — SOCKS5 proxy will use plain TCP");
        }

        executor.submit(() -> runSocksServer());
        running.set(true);
        Log.i(TAG, "YggdrasilManager started, SOCKS5 on 127.0.0.1:" + SOCKS_PORT);
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);

        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}

        stopYggdrasilCore();

        if (executor != null) executor.shutdownNow();
        Log.i(TAG, "YggdrasilManager stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    // ── Yggdrasil core lifecycle ──────────────────────────────────────────────

    /**
     * Tries to start the Yggdrasil node via gomobile reflection.
     * Returns true if the library is present and the node started successfully.
     */
    private boolean startYggdrasilCore() {
        try {
            // Attempt to load yggdrasil-go gomobile classes via reflection.
            // The actual class names depend on the gomobile package path used
            // when building the .aar.  Typical path: yggdrasil.Core
            Class<?> coreClass  = Class.forName("yggdrasil.Core");
            Class<?> configClass = Class.forName("yggdrasil.Config");

            Object config = configClass.newInstance();

            // Add peers
            for (String peer : DEFAULT_PEERS) {
                configClass.getMethod("addPeer", String.class).invoke(config, peer);
            }

            // Disable TUN/VPN interface — we only want the network stack
            configClass.getMethod("setIfName", String.class).invoke(config, "none");

            Object core = coreClass.newInstance();
            coreClass.getMethod("start", configClass).invoke(core, config);

            yggCore   = core;
            yggDialer = coreClass.getMethod("dialer").invoke(core);

            Log.i(TAG, "Yggdrasil core started");
            return true;

        } catch (ClassNotFoundException e) {
            Log.w(TAG, "yggdrasil gomobile library not on classpath: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Yggdrasil core: " + e.getMessage());
            return false;
        }
    }

    private void stopYggdrasilCore() {
        if (yggCore == null) return;
        try {
            yggCore.getClass().getMethod("stop").invoke(yggCore);
        } catch (Exception e) {
            Log.w(TAG, "Error stopping Yggdrasil core: " + e.getMessage());
        }
        yggCore   = null;
        yggDialer = null;
    }

    // ── SOCKS5 proxy server ───────────────────────────────────────────────────

    private void runSocksServer() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), SOCKS_PORT));
            Log.i(TAG, "SOCKS5 server listening on port " + SOCKS_PORT);

            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleSocksClient(client));
                } catch (IOException e) {
                    if (running.get()) {
                        Log.w(TAG, "SOCKS accept error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not bind SOCKS5 server on port " + SOCKS_PORT + ": " + e.getMessage());
        }
    }

    private void handleSocksClient(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream  in  = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // SOCKS5 greeting: VER(1) NMETHODS(1) METHODS(n)
            int ver = in.read();
            if (ver != 0x05) { client.close(); return; }
            int nMethods = in.read();
            byte[] methods = new byte[nMethods];
            readFully(in, methods);

            // Respond: no auth required
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // SOCKS5 request: VER(1) CMD(1) RSV(1) ATYP(1) DST DSTPORT(2)
            if (in.read() != 0x05) { client.close(); return; }
            int cmd  = in.read();
            in.read(); // reserved
            int atyp = in.read();

            if (cmd != 0x01) { // only CONNECT supported
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0});
                out.flush();
                client.close();
                return;
            }

            String host;
            if (atyp == 0x01) {        // IPv4
                byte[] addr = new byte[4];
                readFully(in, addr);
                host = InetAddress.getByAddress(addr).getHostAddress();
            } else if (atyp == 0x04) { // IPv6
                byte[] addr = new byte[16];
                readFully(in, addr);
                host = InetAddress.getByAddress(addr).getHostAddress();
            } else if (atyp == 0x03) { // domain
                int len = in.read();
                byte[] domBytes = new byte[len];
                readFully(in, domBytes);
                host = new String(domBytes);
            } else {
                client.close();
                return;
            }

            int port = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

            // Connect to destination via Yggdrasil (or plain TCP as fallback)
            Socket remote = connectToDestination(host, port);
            if (remote == null) {
                out.write(new byte[]{0x05, 0x04, 0x00, 0x01, 0,0,0,0, 0,0});
                out.flush();
                client.close();
                return;
            }

            // Success reply
            byte[] reply = new byte[]{0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0};
            out.write(reply);
            out.flush();

            client.setSoTimeout(0);
            remote.setSoTimeout(0);

            // Bidirectional pipe
            InputStream  rIn  = remote.getInputStream();
            OutputStream rOut = remote.getOutputStream();
            final Socket finalClient = client;
            final Socket finalRemote = remote;

            executor.submit(() -> pipe(in,  rOut, finalClient, finalRemote));
            pipe(rIn, out, finalRemote, finalClient);

        } catch (Exception e) {
            Log.d(TAG, "SOCKS client error: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Connects to host:port.
     * Uses Yggdrasil dialer if available, otherwise plain TCP.
     */
    private Socket connectToDestination(String host, int port) {
        if (yggDialer != null) {
            try {
                // yggdrasil.Dialer.dial(network, address) → net.Conn (wrapped as Socket by gomobile)
                String address = host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
                Object conn = yggDialer.getClass()
                        .getMethod("dial", String.class, String.class)
                        .invoke(yggDialer, "tcp", address);
                // gomobile wraps net.Conn as a java.net.Socket subclass
                if (conn instanceof Socket) {
                    Log.d(TAG, "Connected to " + address + " via Yggdrasil");
                    return (Socket) conn;
                }
            } catch (Exception e) {
                Log.w(TAG, "Yggdrasil dial failed for " + host + ":" + port + " — " + e.getMessage());
            }
        }

        // Fallback: plain TCP (useful during development / when library absent)
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10000);
            Log.d(TAG, "Connected to " + host + ":" + port + " via plain TCP (fallback)");
            return s;
        } catch (IOException e) {
            Log.w(TAG, "Plain TCP connect failed: " + e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void pipe(InputStream src, OutputStream dst, Socket closeSrc, Socket closeDst) {
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = src.read(buf)) != -1) {
                dst.write(buf, 0, n);
                dst.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { closeSrc.close(); } catch (IOException ignored) {}
            try { closeDst.close(); } catch (IOException ignored) {}
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int n = in.read(buf, offset, buf.length - offset);
            if (n == -1) throw new IOException("Unexpected end of stream");
            offset += n;
        }
    }
}
