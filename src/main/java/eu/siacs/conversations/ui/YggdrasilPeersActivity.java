package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import eu.siacs.conversations.R;
import eu.siacs.conversations.utils.YggdrasilManager;

public class YggdrasilPeersActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "yggdrasil_peers";
    public static final String KEY_PEERS  = "custom_peers";

    private PeerAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statusUpdater;

    // ── Persistence ──────────────────────────────────────────────────────────

    /** Returns all saved peer URIs (ordered, no duplicates). */
    public static List<String> getAllPeers(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> saved = prefs.getStringSet(KEY_PEERS, new HashSet<>());
        return new ArrayList<>(saved);
    }

    /** Saves the full peer list. */
    private static void savePeers(Context ctx, List<String> peers) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_PEERS, new LinkedHashSet<>(peers)).apply();
    }

    /** Returns all saved peers (used by YggdrasilManager). */
    public static List<String> getEnabledPeers(Context ctx) {
        return getAllPeers(ctx);
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

        List<PeerItem> items = new ArrayList<>();
        for (String uri : getAllPeers(this)) {
            items.add(new PeerItem(uri));
        }

        adapter = new PeerAdapter(items, this,
                /* onEdit   */ this::showEditDialog,
                /* onDelete */ this::deletePeer);
        recyclerView.setAdapter(adapter);

        statusUpdater = new Runnable() {
            @Override public void run() {
                updatePeerStatuses();
                handler.postDelayed(this, 3000);
            }
        };
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "+")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) { showAddDialog(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(statusUpdater);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusUpdater);
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    // ── Status update ─────────────────────────────────────────────────────────

    private void updatePeerStatuses() {
        if (adapter == null) return;
        Set<String> connected = YggdrasilManager.getInstance().getConnectedPeers();
        adapter.updateStatuses(connected);
    }

    // ── CRUD dialogs ──────────────────────────────────────────────────────────

    private void showAddDialog() {
        showPeerDialog(null, -1);
    }

    private void showEditDialog(PeerItem item, int position) {
        showPeerDialog(item, position);
    }

    private void showPeerDialog(PeerItem existing, int position) {
        int dp16 = (int) (16 * getResources().getDisplayMetrics().density);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("tcp://host:port");
        if (existing != null) {
            input.setText(existing.uri);
            input.setSelection(existing.uri.length());
        }

        LinearLayout container = new LinearLayout(this);
        container.setPadding(dp16 * 2, dp16, dp16 * 2, 0);
        container.addView(input);

        String title  = existing == null ? getString(R.string.add_peer)    : getString(R.string.edit_peer);
        String button = existing == null ? getString(R.string.add_peer_btn): getString(R.string.save_peer_btn);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(button, (d, w) -> {
                    String uri = input.getText().toString().trim();
                    if (uri.isEmpty()) {
                        Toast.makeText(this, R.string.peer_empty_error, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (existing == null) {
                        addPeer(uri);
                    } else {
                        updatePeer(position, uri);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void addPeer(String uri) {
        List<String> peers = getAllPeers(this);
        if (peers.contains(uri)) {
            Toast.makeText(this, R.string.peer_duplicate_error, Toast.LENGTH_SHORT).show();
            return;
        }
        peers.add(uri);
        savePeers(this, peers);
        adapter.addItem(new PeerItem(uri));
        YggdrasilManager.getInstance().updatePeers(this);
    }

    private void updatePeer(int position, String newUri) {
        List<String> peers = getAllPeers(this);
        String oldUri = adapter.getItem(position).uri;
        int idx = peers.indexOf(oldUri);
        if (idx >= 0) peers.set(idx, newUri);
        else peers.add(newUri);
        savePeers(this, peers);
        adapter.updateItem(position, newUri);
        YggdrasilManager.getInstance().updatePeers(this);
    }

    private void deletePeer(PeerItem item, int position) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_peer)
                .setMessage(item.displayName())
                .setPositiveButton(R.string.delete_peer_btn, (d, w) -> {
                    List<String> peers = getAllPeers(this);
                    peers.remove(item.uri);
                    savePeers(this, peers);
                    adapter.removeItem(position);
                    YggdrasilManager.getInstance().updatePeers(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ── Data model ────────────────────────────────────────────────────────────

    public static class PeerItem {
        public String uri;
        public boolean online;

        public PeerItem(String uri) { this.uri = uri; }

        public String displayName() {
            String s = uri.replaceFirst("^[a-z]+://", "");
            int q = s.indexOf('?');
            if (q > 0) s = s.substring(0, q);
            return s;
        }
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    interface OnEdit   { void onEdit(PeerItem item, int position); }
    interface OnDelete { void onDelete(PeerItem item, int position); }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class PeerAdapter extends RecyclerView.Adapter<PeerAdapter.VH> {

        private final List<PeerItem> items;
        private final Context ctx;
        private final OnEdit   onEdit;
        private final OnDelete onDelete;

        PeerAdapter(List<PeerItem> items, Context ctx, OnEdit onEdit, OnDelete onDelete) {
            this.items    = items;
            this.ctx      = ctx;
            this.onEdit   = onEdit;
            this.onDelete = onDelete;
        }

        PeerItem getItem(int position) { return items.get(position); }

        void addItem(PeerItem item) {
            items.add(item);
            notifyItemInserted(items.size() - 1);
        }

        void removeItem(int position) {
            items.remove(position);
            notifyItemRemoved(position);
        }

        void updateItem(int position, String newUri) {
            items.get(position).uri = newUri;
            notifyItemChanged(position);
        }

        void updateStatuses(Set<String> connected) {
            for (PeerItem item : items) item.online = connected.contains(item.uri);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            float density = ctx.getResources().getDisplayMetrics().density;
            int dp8  = (int)(8  * density);
            int dp16 = (int)(16 * density);
            int dp48 = (int)(48 * density);

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp16, dp16, dp8, dp16);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // Status dot
            View dot = new View(ctx);
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp8 * 2, dp8 * 2);
            dotParams.setMarginEnd(dp8 * 2);
            dot.setLayoutParams(dotParams);
            row.addView(dot);

            // Peer URI (fills remaining space)
            TextView name = new TextView(ctx);
            LinearLayout.LayoutParams nameParams =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            name.setLayoutParams(nameParams);
            name.setTextSize(14);
            row.addView(name);

            // Edit button
            TextView btnEdit = new TextView(ctx);
            btnEdit.setText("✎");
            btnEdit.setTextSize(18);
            btnEdit.setPadding(dp8, 0, dp8, 0);
            row.addView(btnEdit);

            // Delete button
            TextView btnDel = new TextView(ctx);
            btnDel.setText("✕");
            btnDel.setTextSize(18);
            btnDel.setPadding(dp8, 0, dp8, 0);
            row.addView(btnDel);

            return new VH(row, dot, name, btnEdit, btnDel);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            PeerItem item = items.get(position);
            holder.name.setText(item.displayName());
            holder.dot.setBackgroundColor(item.online ? 0xFF00C853 : 0xFF757575);

            holder.btnEdit.setOnClickListener(v -> onEdit.onEdit(item, holder.getAdapterPosition()));
            holder.btnDel .setOnClickListener(v -> onDelete.onDelete(item, holder.getAdapterPosition()));
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final View dot;
            final TextView name, btnEdit, btnDel;

            VH(View root, View dot, TextView name, TextView btnEdit, TextView btnDel) {
                super(root);
                this.dot     = dot;
                this.name    = name;
                this.btnEdit = btnEdit;
                this.btnDel  = btnDel;
            }
        }
    }
}
