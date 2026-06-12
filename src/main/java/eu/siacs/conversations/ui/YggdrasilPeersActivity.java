package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import android.content.SharedPreferences;
import android.content.Context;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.YggdrasilManager;

public class YggdrasilPeersActivity extends AppCompatActivity {

    public static final String PREFS_NAME  = "yggdrasil_peers";
    public static final String KEY_PEERS   = "custom_peers";
    public static final String KEY_DISABLED = "disabled_peers";

    private static final int MENU_ADD = 1;

    private PeerAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusUpdater;

    // ── Persistence ───────────────────────────────────────────────────────────

    /** All saved peer URIs. */
    public static List<String> getAllPeers(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY_PEERS, new HashSet<>());
        return new ArrayList<>(saved);
    }

    private static void savePeers(Context ctx, List<String> peers) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_PEERS, new LinkedHashSet<>(peers)).apply();
    }

    public static Set<String> getDisabledPeers(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getStringSet(KEY_DISABLED, new HashSet<>());
    }

    public static void setDisabledPeers(Context ctx, Set<String> disabled) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_DISABLED, disabled).apply();
    }

    /** Called by YggdrasilManager — returns enabled peers only. */
    public static List<String> getEnabledPeers(Context ctx) {
        Set<String> disabled = getDisabledPeers(ctx);
        List<String> enabled = new ArrayList<>();
        for (String peer : getAllPeers(ctx)) {
            if (!disabled.contains(peer)) enabled.add(peer);
        }
        return enabled;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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
        for (String uri : getAllPeers(this)) {
            items.add(new PeerItem(uri, !disabled.contains(uri)));
        }

        adapter = new PeerAdapter(items, this);
        recyclerView.setAdapter(adapter);

        statusUpdater = new Runnable() {
            @Override
            public void run() {
                updatePeerStatuses();
                handler.postDelayed(this, 3000);
            }
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_ADD, 0, R.string.add_peer)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_ADD) {
            showAddDialog();
            return true;
        }
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

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ── Status update ─────────────────────────────────────────────────────────

    private void updatePeerStatuses() {
        if (adapter == null) return;
        Set<String> connected = YggdrasilManager.getInstance().getConnectedPeers();
        adapter.updateStatuses(connected);
    }

    // ── Add dialog ────────────────────────────────────────────────────────────

    private void showAddDialog() {
        int dp16 = (int) (16 * getResources().getDisplayMetrics().density);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("tcp://host:port");

        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp16 * 2, dp16, dp16 * 2, 0);
        container.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.add_peer)
                .setView(container)
                .setPositiveButton(R.string.add_peer_btn, (d, w) -> {
                    String uri = input.getText().toString().trim();
                    if (uri.isEmpty()) {
                        Toast.makeText(this, R.string.peer_empty_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    List<String> peers = getAllPeers(this);
                    if (peers.contains(uri)) {
                        Toast.makeText(this, R.string.peer_duplicate_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    peers.add(uri);
                    savePeers(this, peers);
                    adapter.addItem(new PeerItem(uri, true));
                    YggdrasilManager.getInstance().updatePeers(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private void confirmDelete(PeerItem item, int position) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_peer)
                .setMessage(item.displayName())
                .setPositiveButton(R.string.delete_peer_btn, (d, w) -> {
                    List<String> peers = getAllPeers(this);
                    peers.remove(item.uri);
                    savePeers(this, peers);
                    Set<String> disabled = new HashSet<>(getDisabledPeers(this));
                    disabled.remove(item.uri);
                    setDisabledPeers(this, disabled);
                    adapter.removeItem(position);
                    YggdrasilManager.getInstance().updatePeers(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Data model ────────────────────────────────────────────────────────────

    public static class PeerItem {
        public final String uri;
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

    private class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.VH> {

        private final List<PeerItem> items;
        private final Context ctx;

        PeerAdapter(List<PeerItem> items, Context ctx) {
            this.items = items;
            this.ctx = ctx;
        }

        void addItem(PeerItem item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }

        void removeItem(int position) {
            items.remove(position);
            notifyItemRemoved(position);
        }

        void updateStatuses(Set<String> connected) {
            for (PeerItem item : items) {
                item.online = connected.contains(item.uri);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(48, 24, 24, 24);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // Status dot
            View dot = new View(ctx);
            int dp8 = (int) (8 * ctx.getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams dotParams =
                    new android.widget.LinearLayout.LayoutParams(dp8 * 2, dp8 * 2);
            dotParams.setMarginEnd(dp8 * 2);
            dot.setLayoutParams(dotParams);
            row.addView(dot);

            // Peer name
            TextView name = new TextView(ctx);
            android.widget.LinearLayout.LayoutParams nameParams =
                    new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nameParams);
            name.setTextSize(14);
            row.addView(name);

            // Enable/disable switch
            SwitchCompat sw = new SwitchCompat(ctx);
            row.addView(sw);

            // Delete button
            TextView btnDel = new TextView(ctx);
            btnDel.setText("✕");
            btnDel.setTextSize(18);
            int dp12 = (int) (12 * ctx.getResources().getDisplayMetrics().density);
            btnDel.setPadding(dp12, 0, dp12, 0);
            row.addView(btnDel);

            return new VH(row, dot, name, sw, btnDel);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            PeerItem item = items.get(position);
            holder.name.setText(item.displayName());
            holder.sw.setOnCheckedChangeListener(null);
            holder.sw.setChecked(item.enabled);

            holder.dot.setBackgroundColor(item.online ? 0xFF00C853 : 0xFF757575);

            holder.sw.setOnCheckedChangeListener((btn, checked) -> {
                item.enabled = checked;
                Set<String> disabled = new HashSet<>(getDisabledPeers(ctx));
                if (checked) disabled.remove(item.uri);
                else disabled.add(item.uri);
                setDisabledPeers(ctx, disabled);
                YggdrasilManager.getInstance().updatePeers(ctx);
            });

            holder.btnDel.setOnClickListener(v ->
                    confirmDelete(item, holder.getAdapterPosition()));
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            final View dot;
            final TextView name;
            final SwitchCompat sw;
            final TextView btnDel;

            VH(View root, View dot, TextView name, SwitchCompat sw, TextView btnDel) {
                super(root);
                this.dot    = dot;
                this.name   = name;
                this.sw     = sw;
                this.btnDel = btnDel;
            }
        }
    }
}
