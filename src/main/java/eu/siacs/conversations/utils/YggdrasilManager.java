package eu.siacs.conversations.utils;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

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

    public synchronized void start(Context context) {
        if (isRunning()) return;
        try {
            JSONArray arr = new JSONArray();
            for (String peer : DEFAULT_PEERS) arr.put(peer);
            Log.i(TAG, "Starting Yggdrasil with " + arr.length() + " peers...");
            yggmobile.Yggmobile.start(arr.toString(), SOCKS_PORT);
            String addr = yggmobile.Yggmobile.getAddress();
            lastError = "";
            Log.i(TAG, "Yggdrasil started, address=" + addr + " socks5=127.0.0.1:" + SOCKS_PORT);
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            Throwable t = e;
            while (t != null) {
                sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
                t = t.getCause();
                if (t != null) sb.append(" | caused by: ");
            }
            lastError = sb.toString();
            Log.e(TAG, "Failed to start Yggdrasil: " + lastError);
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
