package eu.siacs.conversations.services;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;
import mobile.Mobile;
import mobile.MobileLogger;
import mobile.Yggdrasil;

public class YggdrasilService extends VpnService {

    private static final String TAG = "YggdrasilService";

    // Yggdrasil address range 200::/7 covers 200:: to 3fff:ffff:...
    private static final String YGG_ROUTE_1 = "200::";
    private static final int    YGG_PREFIX_1 = 7;

    public static final String ACTION_START = "eu.siacs.conversations.yggdrasil.START";
    public static final String ACTION_STOP  = "eu.siacs.conversations.yggdrasil.STOP";

    private static volatile YggdrasilService sInstance;
    private Yggdrasil mNode;
    private ParcelFileDescriptor mTunFd;
    private volatile boolean mRunning = false;

    public static boolean isRunning() {
        return sInstance != null && sInstance.mRunning;
    }

    public static String getAddress() {
        if (sInstance != null && sInstance.mNode != null) {
            try { return sInstance.mNode.getAddressString(); } catch (Exception e) { return null; }
        }
        return null;
    }

    public static void start(final Context ctx) {
        final Intent i = new Intent(ctx, YggdrasilService.class);
        i.setAction(ACTION_START);
        ctx.startForegroundService(i);
    }

    public static void stop(final Context ctx) {
        final Intent i = new Intent(ctx, YggdrasilService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            doStop();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction()) && !mRunning) {
            startForeground(
                    NotificationService.YGGDRASIL_NOTIFICATION_ID,
                    buildNotification());
            doStart();
        }
        return START_STICKY;
    }

    private void doStart() {
        sInstance = this;
        new Thread(() -> {
            try {
                final AppSettings settings = new AppSettings(getApplicationContext());
                String config = settings.getYggdrasilConfig();
                if (config == null || config.isEmpty()) {
                    config = Mobile.generateConfigJSON();
                    settings.setYggdrasilConfig(config);
                    Log.i(TAG, "Generated new Yggdrasil config");
                }

                // Build TUN interface routing only Yggdrasil range
                final Builder builder = new Builder();
                builder.setSession("Conversations Yggdrasil");
                // Local address inside the tunnel (our Yggdrasil address)
                final mobile.ConfigSummary summary = Mobile.summaryForConfig(config);
                final String addr = summary.getIPv6Address();
                builder.addAddress(addr, 7);
                // Route only Yggdrasil range — regular traffic unaffected
                builder.addRoute(YGG_ROUTE_1, YGG_PREFIX_1);
                // Only route traffic from this app — other apps completely unaffected
                builder.addAllowedApplication(getPackageName());
                builder.setMtu(1280);

                mTunFd = builder.establish();
                if (mTunFd == null) {
                    Log.e(TAG, "Failed to establish VPN tunnel");
                    return;
                }

                mNode = Yggdrasil.new_();
                final String err = mNode.startJSON(
                        mTunFd.getFd(),
                        config,
                        new MobileLogger());
                if (err != null && !err.isEmpty()) {
                    Log.e(TAG, "Yggdrasil start error: " + err);
                    return;
                }
                mRunning = true;
                Log.i(TAG, "Yggdrasil started, address: " + mNode.getAddressString());
            } catch (final Exception e) {
                Log.e(TAG, "Yggdrasil startup failed", e);
            }
        }, "yggdrasil-start").start();
    }

    private void doStop() {
        mRunning = false;
        if (mNode != null) {
            try { mNode.stop(); } catch (Exception e) { Log.e(TAG, "stop error", e); }
            mNode = null;
        }
        if (mTunFd != null) {
            try { mTunFd.close(); } catch (Exception e) { /* ignore */ }
            mTunFd = null;
        }
        sInstance = null;
        Log.i(TAG, "Yggdrasil stopped");
    }

    @Override
    public void onDestroy() {
        doStop();
        super.onDestroy();
    }

    private android.app.Notification buildNotification() {
        final android.app.NotificationChannel channel =
                new android.app.NotificationChannel(
                        "yggdrasil",
                        "Yggdrasil",
                        android.app.NotificationManager.IMPORTANCE_LOW);
        final android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);

        return new android.app.Notification.Builder(this, "yggdrasil")
                .setContentTitle("Yggdrasil")
                .setContentText(getString(R.string.yggdrasil_running))
                .setSmallIcon(R.drawable.ic_link_24dp)
                .setOngoing(true)
                .build();
    }
}
