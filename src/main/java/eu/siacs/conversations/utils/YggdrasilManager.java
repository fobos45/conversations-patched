package eu.siacs.conversations.utils;

import android.content.Context;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

/**
 * Manages an embedded Yggdrasil node and SOCKS5 proxy via the yggmobile AAR.
 * The proxy listens on 127.0.0.1:1080 and tunnels TCP through Yggdrasil.
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

    private YggdrasilManager() {}

    public static YggdrasilManager getInstance() {
        return INSTANCE;
    }

    private String lastError = "";

    public String getLastError() {
        return lastError;
    }

    public void start(Context context) {
        if (isRunning()) return;
        new Thread(() -> startInternal(context), "YggdrasilStart").start();
    }

    private synchronized void startInternal(Context context) {
        if (isRunning()) return;
        try {
            String peers = String.join("\n", DEFAULT_PEERS);
            Log.i(TAG, "Starting Yggdrasil with " + DEFAULT_PEERS.size() + " peers...");
            yggmobile.Yggmobile.start(peers, SOCKS_PORT);
            String addr = yggmobile.Yggmobile.getAddress();
            lastError = "";
            Log.i(TAG, "Yggdrasil started, address=" + addr + " socks5=127.0.0.1:" + SOCKS_PORT);
        } catch (Throwable e) {
            StringBuilder sb = new StringBuilder();
            Throwable t = e;
            while (t != null) {
                sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append("\n");
                for (StackTraceElement el : t.getStackTrace()) {
                    sb.append("  at ").append(el.toString()).append("\n");
                }
                t = t.getCause();
                if (t != null) sb.append("caused by:\n");
            }
            lastError = sb.toString();
            Log.e(TAG, "Failed to start Yggdrasil: " + lastError);
            writeErrorToDownloads(lastError);
        }
    }

    private void writeErrorToDownloads(String text) {
        try {
            java.io.File downloads = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            java.io.File f = new java.io.File(downloads, "ygg_error.txt");
            java.io.FileWriter fw = new java.io.FileWriter(f);
            fw.write(text);
            fw.close();
            Log.e(TAG, "Error written to: " + f.getAbsolutePath());
        } catch (java.io.IOException ex) {
            Log.e(TAG, "Could not write error file: " + ex.getMessage());
        }
    }

    public synchronized void stop() {
        if (!isRunning()) return;
        yggmobile.Yggmobile.stop();
        Log.i(TAG, "Yggdrasil stopped");
    }

    public boolean isRunning() {
        try {
            return yggmobile.Yggmobile.isRunning();
        } catch (Exception e) {
            return false;
        }
    }
}
