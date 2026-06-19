package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.YggdrasilManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Speed test activity that measures download and upload bandwidth through
 * the embedded Yggdrasil SOCKS5 proxy (127.0.0.1:1080).
 *
 * The test is deliberately simple: download a configurable URL (default: a
 * librespeed garbage endpoint on a public Yggdrasil node), measure throughput
 * in Mbps, then optionally upload a fixed-size buffer to the same host.
 * Since traffic goes through the SOCKS proxy it is routed over the Yggdrasil
 * overlay, so the result reflects your actual Yggdrasil bandwidth regardless
 * of which TURN relay or peer is active.
 */
public class YggdrasilSpeedTestActivity extends AppCompatActivity {

    /** Extra: peer URI string from YggdrasilPeersActivity (display-only). */
    public static final String EXTRA_PEER_URI = "peer_uri";

    private static final String PREFS = "ygg_speedtest";
    private static final String KEY_URL = "test_url";

    // Default: librespeed garbage endpoint on a well-known public Yggdrasil
    // node.  The user can change this to any HTTP server reachable in the
    // Yggdrasil network; the value is persisted across launches.
    private static final String DEFAULT_TEST_URL =
            "http://[302:db60:d602:f0ea:216c:1539:9c2a:fbf9]/speedtest/garbage.php?ckSize=10";

    // Download size cap: 10 MiB is enough to get a stable reading.
    private static final int DOWNLOAD_CAP_BYTES = 10 * 1024 * 1024;
    // Upload payload: 2 MiB.
    private static final int UPLOAD_BYTES = 2 * 1024 * 1024;

    private SpeedometerView speedometer;
    private TextView statusText;
    private TextView resultText;
    private Button startButton;
    private EditText urlEdit;
    private ProgressBar progressBar;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final String peerUri = getIntent().getStringExtra(EXTRA_PEER_URI);
        final String peerLabel = peerUri != null ? displayName(peerUri) : "";

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(
                    peerLabel.isEmpty() ? "Тест скорости Yggdrasil" : "Тест: " + peerLabel);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        executor = Executors.newSingleThreadExecutor();

        buildUi(peerLabel);
    }

    private void buildUi(final String peerLabel) {
        final float dp = getResources().getDisplayMetrics().density;
        final int pad16 = (int) (16 * dp);
        final int pad8  = (int) (8 * dp);

        final ScrollView scroll = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad16, pad16, pad16, pad16);
        scroll.addView(root);
        setContentView(scroll);

        // ── Speedometer ──────────────────────────────────────────────────────
        speedometer = new SpeedometerView(this);
        final int gaugeSize = (int) (220 * dp);
        final LinearLayout.LayoutParams gaugeParams =
                new LinearLayout.LayoutParams(gaugeSize, gaugeSize);
        gaugeParams.gravity = Gravity.CENTER_HORIZONTAL;
        gaugeParams.bottomMargin = pad8;
        root.addView(speedometer, gaugeParams);

        // ── Status / result labels ────────────────────────────────────────────
        statusText = new TextView(this);
        statusText.setText("Нажмите «Старт» для начала теста");
        statusText.setTextSize(14);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, matchWrap());

        resultText = new TextView(this);
        resultText.setTextSize(22);
        resultText.setGravity(Gravity.CENTER);
        resultText.setPadding(0, pad8, 0, pad8);
        root.addView(resultText, matchWrap());

        // ── Progress bar ──────────────────────────────────────────────────────
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setVisibility(View.INVISIBLE);
        root.addView(progressBar, matchWrap());

        // ── Start button ──────────────────────────────────────────────────────
        startButton = new Button(this);
        startButton.setText("Старт");
        final LinearLayout.LayoutParams btnParams = matchWrap();
        btnParams.topMargin = pad8;
        root.addView(startButton, btnParams);

        startButton.setOnClickListener(v -> {
            if (running.get()) {
                cancel();
            } else {
                startTest();
            }
        });

        // ── URL setting ───────────────────────────────────────────────────────
        final TextView urlLabel = new TextView(this);
        urlLabel.setText("Тестовый URL (librespeed garbage endpoint):");
        urlLabel.setTextSize(12);
        final LinearLayout.LayoutParams lblParams = matchWrap();
        lblParams.topMargin = (int) (24 * dp);
        root.addView(urlLabel, lblParams);

        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setText(getSavedUrl());
        urlEdit.setTextSize(12);
        urlEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(final Editable s) {
                saveUrl(s.toString().trim());
            }
        });
        root.addView(urlEdit, matchWrap());

        final TextView hint = new TextView(this);
        hint.setText("URL должен быть доступен через сеть Yggdrasil.");
        hint.setTextSize(11);
        hint.setTextColor(0xFF888888);
        root.addView(hint, matchWrap());
    }

    private void startTest() {
        if (!YggdrasilManager.getInstance().isRunning()) {
            setStatus("Yggdrasil не запущен. Включите его в настройках.");
            return;
        }
        running.set(true);
        startButton.setText("Отмена");
        resultText.setText("");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        speedometer.setSpeed(0);

        // Hide keyboard
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlEdit.getWindowToken(), 0);

        final String testUrl = urlEdit.getText().toString().trim();
        executor.submit(() -> runTest(testUrl));
    }

    private void cancel() {
        running.set(false);
        uiHandler.post(() -> {
            startButton.setText("Старт");
            progressBar.setVisibility(View.INVISIBLE);
            setStatus("Тест отменён.");
        });
    }

    // ── Test logic (background thread) ───────────────────────────────────────

    private void runTest(final String rawUrl) {
        // ── Download ──────────────────────────────────────────────────────────
        post(() -> setStatus("↓ Загрузка…"));

        final double downloadMbps;
        try {
            downloadMbps = measureDownload(rawUrl);
        } catch (final Exception e) {
            if (running.get()) {
                post(() -> {
                    setStatus("Ошибка загрузки: " + e.getMessage());
                    startButton.setText("Старт");
                    progressBar.setVisibility(View.INVISIBLE);
                });
            }
            running.set(false);
            return;
        }

        if (!running.get()) return;

        post(() -> {
            progressBar.setProgress(50);
            setStatus("↑ Отправка…");
            speedometer.setSpeed((float) downloadMbps);
        });

        // ── Upload ────────────────────────────────────────────────────────────
        double uploadMbps = 0;
        try {
            uploadMbps = measureUpload(rawUrl);
        } catch (final Exception ignored) {
            // Upload may fail if the server doesn't accept POST; show 0.
        }

        if (!running.get()) return;

        final double finalUpload = uploadMbps;
        post(() -> {
            progressBar.setProgress(100);
            progressBar.setVisibility(View.INVISIBLE);
            startButton.setText("Старт");
            setStatus("Тест завершён");
            resultText.setText(String.format(Locale.ROOT,
                    "↓ %.2f Мбит/с    ↑ %.2f Мбит/с", downloadMbps, finalUpload));
            speedometer.setSpeed((float) downloadMbps);
        });

        running.set(false);
    }

    private double measureDownload(final String rawUrl) throws Exception {
        final Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", YggdrasilManager.SOCKS_PORT));
        final URL url = new URL(rawUrl);
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Cache-Control", "no-cache");

        final int code = conn.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);

        final byte[] buf = new byte[65536];
        long totalBytes = 0;
        final long startNs = System.nanoTime();

        try (final InputStream in = conn.getInputStream()) {
            int n;
            while (running.get()
                    && totalBytes < DOWNLOAD_CAP_BYTES
                    && (n = in.read(buf)) != -1) {
                totalBytes += n;
                final long elapsed = System.nanoTime() - startNs;
                final double mbps = (totalBytes * 8.0) / (elapsed / 1e9) / 1_000_000.0;
                final int progress = (int) Math.min(50, totalBytes * 50L / DOWNLOAD_CAP_BYTES);
                final long bytesSnap = totalBytes;
                post(() -> {
                    progressBar.setProgress(progress);
                    speedometer.setSpeed((float) mbps);
                    setStatus(String.format(Locale.ROOT,
                            "↓ %.2f Мбит/с (%s)", mbps, humanBytes(bytesSnap)));
                });
            }
        } finally {
            conn.disconnect();
        }

        final long elapsedNs = System.nanoTime() - startNs;
        return (totalBytes * 8.0) / (elapsedNs / 1e9) / 1_000_000.0;
    }

    private double measureUpload(final String rawUrl) throws Exception {
        // Upload to the same host; librespeed servers accept POST /speedtest/empty.php
        final String uploadUrl = rawUrl.replaceFirst("/garbage\\.php.*$", "/empty.php");
        final Proxy proxy = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", YggdrasilManager.SOCKS_PORT));
        final URL url = new URL(uploadUrl);
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(UPLOAD_BYTES);
        conn.setRequestProperty("Content-Type", "application/octet-stream");

        final byte[] chunk = new byte[65536];
        long sent = 0;
        final long startNs = System.nanoTime();

        try (final OutputStream out = conn.getOutputStream()) {
            while (running.get() && sent < UPLOAD_BYTES) {
                final int toSend = (int) Math.min(chunk.length, UPLOAD_BYTES - sent);
                out.write(chunk, 0, toSend);
                sent += toSend;
                final long elapsed = System.nanoTime() - startNs;
                final double mbps = (sent * 8.0) / (elapsed / 1e9) / 1_000_000.0;
                final long sentSnap = sent;
                post(() -> setStatus(String.format(Locale.ROOT,
                        "↑ %.2f Мбит/с (%s)", mbps, humanBytes(sentSnap))));
            }
            out.flush();
        } finally {
            conn.disconnect();
        }

        final long elapsedNs = System.nanoTime() - startNs;
        return (sent * 8.0) / (elapsedNs / 1e9) / 1_000_000.0;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setStatus(final String s) { statusText.setText(s); }

    private void post(final Runnable r) { uiHandler.post(r); }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private String getSavedUrl() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_URL, DEFAULT_TEST_URL);
    }

    private void saveUrl(final String url) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putString(KEY_URL, url).apply();
    }

    private static String displayName(final String uri) {
        String s = uri.replaceFirst("^[a-z]+://", "");
        final int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        return s;
    }

    private static String humanBytes(final long bytes) {
        if (bytes < 1024) return bytes + " Б";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f КБ", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f МБ", bytes / (1024.0 * 1024));
    }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running.set(false);
        if (executor != null) executor.shutdownNow();
    }

    // ── SpeedometerView ───────────────────────────────────────────────────────

    /**
     * Simple arc-gauge: 0–100 Mbps range, colour shifts green→yellow→red.
     * The needle and current value are redrawn on every {@link #setSpeed} call.
     */
    public static class SpeedometerView extends View {

        private static final float START_ANGLE = 135f;
        private static final float SWEEP_ANGLE = 270f;
        private static final float MAX_SPEED   = 100f; // Mbps

        private final Paint arcBgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arcFgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF oval = new RectF();
        private float speed = 0f; // Mbps

        public SpeedometerView(final Context ctx) { super(ctx); init(); }
        public SpeedometerView(final Context ctx, final AttributeSet a) { super(ctx, a); init(); }

        private void init() {
            arcBgPaint.setStyle(Paint.Style.STROKE);
            arcBgPaint.setColor(0x22FFFFFF);

            arcFgPaint.setStyle(Paint.Style.STROKE);
            arcFgPaint.setStrokeCap(Paint.Cap.ROUND);

            needlePaint.setStyle(Paint.Style.STROKE);
            needlePaint.setStrokeCap(Paint.Cap.ROUND);
            needlePaint.setColor(Color.WHITE);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);

            labelPaint.setColor(0xAAFFFFFF);
            labelPaint.setTextAlign(Paint.Align.CENTER);

            setBackgroundColor(0xFF1E1E2E);
        }

        public void setSpeed(final float mbps) {
            this.speed = Math.max(0, Math.min(MAX_SPEED, mbps));
            invalidate();
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            final float cx = w / 2f;
            final float cy = h / 2f;
            final float r  = Math.min(w, h) * 0.40f;
            final float strokeW = r * 0.14f;

            oval.set(cx - r, cy - r, cx + r, cy + r);

            // Background arc
            arcBgPaint.setStrokeWidth(strokeW);
            canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, arcBgPaint);

            // Foreground arc with gradient
            final float fraction = speed / MAX_SPEED;
            arcFgPaint.setStrokeWidth(strokeW);
            arcFgPaint.setShader(new SweepGradient(cx, cy,
                    new int[]{0xFF00C853, 0xFFFFD600, 0xFFFF1744},
                    new float[]{0f, 0.5f, 1f}));
            canvas.save();
            canvas.rotate(START_ANGLE, cx, cy);
            canvas.drawArc(oval, 0, SWEEP_ANGLE * fraction, false, arcFgPaint);
            canvas.restore();

            // Needle
            final double needleAngleRad = Math.toRadians(START_ANGLE + SWEEP_ANGLE * fraction);
            final float nx = cx + (float) Math.cos(needleAngleRad) * r;
            final float ny = cy + (float) Math.sin(needleAngleRad) * r;
            needlePaint.setStrokeWidth(strokeW * 0.3f);
            canvas.drawLine(cx, cy, nx, ny, needlePaint);

            // Value
            textPaint.setTextSize(r * 0.48f);
            canvas.drawText(String.format(Locale.ROOT, "%.1f", speed), cx, cy + r * 0.15f, textPaint);

            // Unit label
            labelPaint.setTextSize(r * 0.22f);
            canvas.drawText("Мбит/с", cx, cy + r * 0.42f, labelPaint);
        }
    }
}
