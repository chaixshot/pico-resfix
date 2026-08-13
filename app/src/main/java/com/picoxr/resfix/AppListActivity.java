package com.picoxr.resfix;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.List;

/**
 * Main screen: lists installed 2D (launcher) apps. A "show system apps" switch filters
 * system apps. Tapping an app opens AppDetailActivity for its per-app resolution.
 * "默认设置" opens a simple editor for the global default (third-party apps).
 */
public class AppListActivity extends AppCompatActivity {

    RecyclerView recycler;
    MaterialSwitch swSystem;
    TextView status;
    FloatingActionButton fabDefault;
    AppAdapter adapter;
    Config.GlobalCfg glob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        recycler = findViewById(R.id.recycler);
        status = findViewById(R.id.status);
        fabDefault = findViewById(R.id.fab_default);

        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new AppAdapter();
        recycler.setAdapter(adapter);

        fabDefault.setOnClickListener(v -> openDefaultEditor());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_list, menu);
        MenuItem item = menu.findItem(R.id.menu_show_system);
        swSystem = (MaterialSwitch) item.getActionView();
        if (swSystem != null) {
            swSystem.setOnCheckedChangeListener((b, c) -> reload());
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    void reload() {
        glob = Config.getGlobal();
        boolean showSys = swSystem != null && swSystem.isChecked();
        List<Config.AppEntry> apps = Config.listApps(this, !showSys, glob);
        android.util.Log.i("ResFixGUI", "listApps returned " + apps.size()
                + " apps (showSystem=" + showSys + ")");
        adapter.setApps(apps);
        status.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
    }

    void openDefaultEditor() {
        // Reuse AppDetailActivity in "default mode" (no package = edit global default)
        Intent i = new Intent(this, AppDetailActivity.class);
        i.putExtra("pkg", "");
        startActivity(i);
    }

    class AppAdapter extends RecyclerView.Adapter<AppAdapter.VH> {
        List<Config.AppEntry> apps;

        void setApps(List<Config.AppEntry> a) { apps = a; notifyDataSetChanged(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int t) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            final Config.AppEntry e = apps.get(pos);
            h.label.setText(e.label != null ? e.label : e.pkg);
            h.pkg.setText(e.pkg);
            h.cardSys.setVisibility(e.isSystem ? View.VISIBLE : View.GONE);
            String prefix = e.hasOverride
                    ? h.root.getContext().getString(R.string.custom_prefix)
                    : h.root.getContext().getString(R.string.default_prefix);
            String res = prefix + e.w + "x" + e.h + (e.density > 0 ? " @" + e.density : "");
            h.res.setText(res);
            h.root.setOnClickListener(v -> {
                Intent i = new Intent(AppListActivity.this, AppDetailActivity.class);
                i.putExtra("pkg", e.pkg);
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() { return apps == null ? 0 : apps.size(); }

        class VH extends RecyclerView.ViewHolder {
            View root, cardSys; TextView label, pkg, res;
            VH(View v) { super(v); root = v; label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_pkg); res = v.findViewById(R.id.tv_res);
                cardSys = v.findViewById(R.id.card_sys); }
        }
    }
}
