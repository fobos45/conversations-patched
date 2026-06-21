package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.YggdrasilManager;

public class YggdrasilPeersActivity extends AppCompatActivity {

    private static final String TAG = "YggdrasilPeersActivity";

    public static final String PREFS_NAME = "yggdrasil_peers";
    public static final String KEY_PEERS  = "peers_json";

    // Default peers (used only on first launch, no mimir)
    private static final String[] DEFAULT_PEERS = {
        "tcp://45.95.202.21:12403",
        "tcp://51.15.204.214:12345",
        "tcp://62.210.85.80:39565",
        "tcp://yggpeer.tilde.green:53299",
        "tls://109.176.250.101:65534"
    };

    private PeerAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusUpdater;

    // ── Peer storage ──────────────────────────────────────────────────────────

    public static List<PeerItem> loadPeers(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String json = prefs.getString(KEY_PEERS, null);
        List<PeerItem> list = new ArrayList<>();
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new PeerItem(o.getString("uri"), o.getBoolean("enabled")));
                }
                return list;
            } catch (Exception ignored) {}
        }
        // First launch: add defaults all enabled
        for (String uri : DEFAULT_PEERS) {
            list.add(new PeerItem(uri, true));
        }
        savePeers(ctx, list);
        return list;
    }

    public static void savePeers(Context ctx, List<PeerItem> peers) {
        try {
            JSONArray arr = new JSONArray();
            for (PeerItem p : peers) {
                JSONObject o = new JSONObject();
                o.put("uri", p.uri);
                o.put("enabled", p.enabled);
                arr.put(o);
            }
            ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
               .edit().putString(KEY_PEERS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static List<String> getEnabledPeers(Context ctx) {
        List<String> result = new ArrayList<>();
        for (PeerItem p : loadPeers(ctx)) {
            if (p.enabled) result.add(p.uri);
        }
        return result;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build layout: CoordinatorLayout > (Toolbar + RecyclerView + FAB)
        CoordinatorLayout root = new CoordinatorLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        // Toolbar
        Toolbar toolbar = new Toolbar(this);
        toolbar.setId(android.R.id.primary);
        CoordinatorLayout.LayoutParams tbParams = new CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        toolbar.setLayoutParams(tbParams);
        toolbar.setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface));
        root.addView(toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.pref_yggdrasil_peers);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // RecyclerView below toolbar
        RecyclerView rv = new RecyclerView(this);
        CoordinatorLayout.LayoutParams rvParams = new CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
        rvParams.topMargin = dp(56);
        rvParams.bottomMargin = dp(72);
        rv.setLayoutParams(rvParams);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setPadding(0, 8, 0, 8);
        root.addView(rv);

        // FAB for adding peers
        FloatingActionButton fab = new FloatingActionButton(this);
        CoordinatorLayout.LayoutParams fabParams = new CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        fabParams.gravity = Gravity.BOTTOM | Gravity.END;
        int margin = dp(16);
        fabParams.setMargins(margin, margin, margin, margin);
        fab.setLayoutParams(fabParams);
        fab.setContentDescription(getString(R.string.ygg_add_peer));
        fab.setImageResource(android.R.drawable.ic_input_add);
        fab.setOnClickListener(v -> showAddDialog(null, -1));
        root.addView(fab);

        setContentView(root);

        adapter = new PeerAdapter(loadPeers(this), this);
        rv.setAdapter(adapter);

        statusUpdater = new Runnable() {
            @Override public void run() {
                updateStatuses();
                handler.postDelayed(this, 3000);
            }
        };
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(statusUpdater);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusUpdater);
    }

    // ── Status update ─────────────────────────────────────────────────────────

    private void updateStatuses() {
        if (!YggdrasilManager.getInstance().isRunning()) return;
        try {
            String json = yggmobile.Yggmobile.getPeersJSON();
            JSONArray arr = new JSONArray(json);
            java.util.Set<String> online = new java.util.HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optBoolean("up")) online.add(o.getString("uri"));
            }
            adapter.updateStatuses(online);
        } catch (Exception ignored) {}
    }

    // ── Add / Edit dialog ─────────────────────────────────────────────────────

    void showAddDialog(PeerItem existing, int position) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        layout.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(this);
        input.setHint("tcp://host:port");
        if (existing != null) input.setText(existing.uri);
        input.setSingleLine(true);
        layout.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle(existing == null ? R.string.ygg_add_peer : R.string.ygg_edit_peer)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String uri = input.getText().toString().trim();
                if (TextUtils.isEmpty(uri)) return;
                if (!uri.startsWith("tcp://") && !uri.startsWith("tls://")
                        && !uri.startsWith("quic://")) {
                    Toast.makeText(this, R.string.ygg_invalid_peer, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (existing == null) {
                    Log.i(TAG, "UI: add peer uri=" + uri);
                    adapter.addPeer(new PeerItem(uri, true));
                } else {
                    Log.i(TAG, "UI: edit peer uri=" + existing.uri + " -> " + uri);
                    adapter.editPeer(position, uri);
                }
                persist();
            })
            .setNegativeButton(android.R.string.cancel, null);

        if (existing != null) {
            b.setNeutralButton(R.string.ygg_delete_peer, (d, w) -> {
                Log.i(TAG, "UI: delete peer uri=" + existing.uri);
                adapter.removePeer(position);
                persist();
            });
        }
        b.show();
    }

    void persist() {
        Log.i(TAG, "UI: persist() -> saving " + adapter.items.size()
                + " peer(s), requesting Yggdrasil restart");
        savePeers(this, adapter.items);
        YggdrasilManager.getInstance().updatePeers(this);
    }

    int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density);
    }

    int resolveColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    // ── Data model ────────────────────────────────────────────────────────────

    public static class PeerItem {
        public String uri;
        public boolean enabled;
        public boolean online;

        public PeerItem(String uri, boolean enabled) {
            this.uri = uri;
            this.enabled = enabled;
        }

        public String displayName() {
            String s = uri.replaceFirst("^[a-z]+://", "");
            int q = s.indexOf('?');
            if (q > 0) s = s.substring(0, q);
            return s;
        }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.VH> {

        final List<PeerItem> items;
        final Context ctx;
        final java.util.Map<String, Boolean> lastOnline = new java.util.HashMap<>();

        PeerAdapter(List<PeerItem> items, Context ctx) {
            this.items = new ArrayList<>(items);
            this.ctx = ctx;
        }

        void updateStatuses(java.util.Set<String> online) {
            for (PeerItem p : items) {
                boolean nowOnline = online.contains(p.uri);
                Boolean prev = lastOnline.get(p.uri);
                if (prev == null || prev != nowOnline) {
                    Log.i(TAG, "status: peer " + p.uri + " -> "
                            + (nowOnline ? "ONLINE" : "OFFLINE"));
                    lastOnline.put(p.uri, nowOnline);
                }
                p.online = nowOnline;
            }
            notifyDataSetChanged();
        }

        void addPeer(PeerItem p) {
            items.add(p);
            notifyItemInserted(items.size() - 1);
        }

        void editPeer(int pos, String uri) {
            items.get(pos).uri = uri;
            notifyItemChanged(pos);
        }

        void removePeer(int pos) {
            items.remove(pos);
            notifyItemRemoved(pos);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int padH = dp(16), padV = dp(14);
            row.setPadding(padH, padV, padH, padV);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

            // Status dot
            android.view.View dot = new android.view.View(ctx);
            int dotSize = dp(10);
            LinearLayout.LayoutParams dotP = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotP.setMarginEnd(dp(12));
            dot.setLayoutParams(dotP);
            // Make dot circular
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            shape.setColor(0xFF757575);
            dot.setBackground(shape);
            row.addView(dot);

            // Peer URI text
            TextView name = new TextView(ctx);
            LinearLayout.LayoutParams nameP = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nameP);
            name.setTextSize(14);
            name.setMaxLines(2);
            row.addView(name);

            // Edit button
            TextView editBtn = new TextView(ctx);
            editBtn.setText("✎");
            editBtn.setTextSize(18);
            editBtn.setPadding(dp(8), 0, dp(8), 0);
            row.addView(editBtn);

            // Enable switch
            SwitchCompat sw = new SwitchCompat(ctx);
            row.addView(sw);

            return new VH(row, dot, name, editBtn, sw);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            PeerItem item = items.get(pos);
            h.name.setText(item.displayName());
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(item.enabled);

            // Status dot color
            android.graphics.drawable.GradientDrawable d =
                (android.graphics.drawable.GradientDrawable) h.dot.getBackground();
            d.setColor(item.online ? 0xFF00C853 : 0xFF757575);

            h.sw.setOnCheckedChangeListener((btn, checked) -> {
                Log.i(TAG, "UI: " + (checked ? "enable" : "disable")
                        + " peer uri=" + item.uri);
                item.enabled = checked;
                persist();
            });

            h.editBtn.setOnClickListener(v ->
                showAddDialog(item, h.getAdapterPosition()));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final android.view.View dot;
            final TextView name, editBtn;
            final SwitchCompat sw;
            VH(android.view.View root, android.view.View dot,
               TextView name, TextView editBtn, SwitchCompat sw) {
                super(root);
                this.dot = dot; this.name = name;
                this.editBtn = editBtn; this.sw = sw;
            }
        }
    }
}
