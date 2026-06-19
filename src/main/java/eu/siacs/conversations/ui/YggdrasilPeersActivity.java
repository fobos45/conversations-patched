package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.YggdrasilManager;

public class YggdrasilPeersActivity extends AppCompatActivity {

    public static final String PREFS_NAME  = "yggdrasil_peers";
    public static final String KEY_DISABLED = "disabled_peers";

    private PeerAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusUpdater;

    private static final List<String> ALL_PEERS = Arrays.asList(
        "tcp://de1.mimir.im:7743?key=1bb8affffffff5ef2b5157b691dc1dd13875c1ec90e789e73bce03af983c4420",
        "tcp://de2.mimir.im:7743?key=0dedeefeffe7e36dd503d83ac8314859ef2601e0841b6d95fb6168501413c58e",
        "tcp://sk1.mimir.im:7743?key=0000000003782d918d36b649e77d70a80322b22be41d4b25455bd81f6e58580f",
        "tcp://sk2.mimir.im:7743?key=00ffed7fdfffa148ab3b01a9c53c20a7bcc8683f621598943f364fcdba034bef",
        "tcp://us1.mimir.im:7743?key=00ff9bffdbffdd6bd9a2151915d9474545c50d324f7b282bff33ef7c402ebe94",
        "tcp://45.95.202.21:12403",
        "tcp://51.15.204.214:12345",
        "tcp://62.210.85.80:39565"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setPadding(0, 8, 0, 8);
        setContentView(recyclerView);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.pref_yggdrasil_peers);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        Set<String> disabled = getDisabledPeers(this);
        List<PeerItem> items = new ArrayList<>();
        for (String uri : ALL_PEERS) {
            items.add(new PeerItem(uri, !disabled.contains(uri)));
        }

        adapter = new PeerAdapter(items, this);
        recyclerView.setAdapter(adapter);

        statusUpdater = new Runnable() {
            @Override public void run() {
                updatePeerStatuses();
                handler.postDelayed(this, 3000);
            }
        };
    }

    @Override protected void onResume() { super.onResume(); handler.post(statusUpdater); }
    @Override protected void onPause()  { super.onPause();  handler.removeCallbacks(statusUpdater); }

    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }

    private void updatePeerStatuses() {
        if (adapter == null) return;
        final Map<String, long[]> stats = YggdrasilManager.getInstance().getPeerStats();
        adapter.updateStatuses(stats);
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    public static Set<String> getDisabledPeers(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getStringSet(KEY_DISABLED, new HashSet<>());
    }

    public static void setDisabledPeers(Context ctx, Set<String> disabled) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_DISABLED, disabled).apply();
    }

    public static List<String> getEnabledPeers(Context ctx) {
        Set<String> disabled = getDisabledPeers(ctx);
        List<String> enabled = new ArrayList<>();
        for (String peer : ALL_PEERS) {
            if (!disabled.contains(peer)) enabled.add(peer);
        }
        return enabled;
    }

    // ── Data model ────────────────────────────────────────────────────────────

    public static class PeerItem {
        public final String uri;
        public boolean enabled;
        public boolean online;
        public long latencyMs = -1; // -1 = not yet measured

        public PeerItem(String uri, boolean enabled) {
            this.uri     = uri;
            this.enabled = enabled;
        }

        public String displayName() {
            String s = uri.replaceFirst("^[a-z]+://", "");
            int q = s.indexOf('?');
            if (q > 0) s = s.substring(0, q);
            return s;
        }

        /** Human-readable latency, e.g. "23 мс" or "—" when unknown. */
        public String latencyLabel() {
            if (!online || latencyMs < 0) return "";
            return latencyMs + " мс";
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.VH> {

        private final List<PeerItem> items;
        private final Context ctx;

        PeerAdapter(List<PeerItem> items, Context ctx) {
            this.items = items;
            this.ctx   = ctx;
        }

        void updateStatuses(final Map<String, long[]> stats) {
            for (PeerItem item : items) {
                final long[] s = stats.get(item.uri);
                if (s != null) {
                    item.online    = s[0] > 0;     // s[0] = 1 if up
                    item.latencyMs = s[1];          // s[1] = latency ms
                } else {
                    item.online    = false;
                    item.latencyMs = -1;
                }
            }
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final float dp = ctx.getResources().getDisplayMetrics().density;
            final int dp8  = (int) (8 * dp);
            final int dp48 = (int) (48 * dp);

            // ── Row root ──────────────────────────────────────────────────────
            final LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp48, (int)(20*dp), (int)(16*dp), (int)(20*dp));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // ── Status dot ────────────────────────────────────────────────────
            final View dot = new View(ctx);
            final LinearLayout.LayoutParams dotLp =
                    new LinearLayout.LayoutParams(dp8 * 2, dp8 * 2);
            dotLp.setMarginEnd(dp8 * 2);
            dot.setLayoutParams(dotLp);
            row.addView(dot);

            // ── Name + latency column ─────────────────────────────────────────
            final LinearLayout nameCol = new LinearLayout(ctx);
            nameCol.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout.LayoutParams nameColLp =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nameColLp.setMarginEnd(dp8);
            nameCol.setLayoutParams(nameColLp);

            final TextView name = new TextView(ctx);
            name.setTextSize(14);
            nameCol.addView(name, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            final TextView latency = new TextView(ctx);
            latency.setTextSize(11);
            latency.setTextColor(0xFF888888);
            nameCol.addView(latency, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            row.addView(nameCol);

            // ── Speedometer button ────────────────────────────────────────────
            final ImageButton speedBtn = new ImageButton(ctx);
            speedBtn.setImageResource(android.R.drawable.ic_menu_compass);
            speedBtn.setBackgroundResource(android.R.drawable.btn_default);
            final LinearLayout.LayoutParams speedBtnLp =
                    new LinearLayout.LayoutParams(dp48, dp48);
            speedBtnLp.setMarginEnd(dp8);
            speedBtn.setLayoutParams(speedBtnLp);
            speedBtn.setContentDescription("Тест скорости");
            row.addView(speedBtn);

            // ── Enable/disable switch ─────────────────────────────────────────
            final SwitchCompat sw = new SwitchCompat(ctx);
            row.addView(sw);

            return new VH(row, dot, name, latency, speedBtn, sw);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            final PeerItem item = items.get(position);
            holder.name.setText(item.displayName());
            holder.latency.setText(item.latencyLabel());

            holder.sw.setOnCheckedChangeListener(null);
            holder.sw.setChecked(item.enabled);

            // Status dot colour
            if (item.online) {
                holder.dot.setBackgroundColor(0xFF00C853);
            } else if (item.enabled) {
                holder.dot.setBackgroundColor(0xFFFF6D00); // connecting/orange
            } else {
                holder.dot.setBackgroundColor(0xFF757575); // disabled/grey
            }

            // Speedometer button: always tappable to start a test
            holder.speedBtn.setOnClickListener(v -> {
                final Intent intent =
                        new Intent(ctx, YggdrasilSpeedTestActivity.class);
                intent.putExtra(YggdrasilSpeedTestActivity.EXTRA_PEER_URI, item.uri);
                ctx.startActivity(intent);
            });

            holder.sw.setOnCheckedChangeListener((btn, checked) -> {
                item.enabled = checked;
                final Set<String> disabled =
                        new HashSet<>(getDisabledPeers(ctx));
                if (checked) disabled.remove(item.uri);
                else         disabled.add(item.uri);
                setDisabledPeers(ctx, disabled);
                YggdrasilManager.getInstance().updatePeers(ctx);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final View        dot;
            final TextView    name;
            final TextView    latency;
            final ImageButton speedBtn;
            final SwitchCompat sw;

            VH(View root, View dot, TextView name, TextView latency,
               ImageButton speedBtn, SwitchCompat sw) {
                super(root);
                this.dot      = dot;
                this.name     = name;
                this.latency  = latency;
                this.speedBtn = speedBtn;
                this.sw       = sw;
            }
        }
    }
}
