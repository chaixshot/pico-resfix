package com.picoxr.resfix;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    EditText etSearch;
    AppAdapter adapter;
    Config.GlobalCfg glob;
    private List<Config.AppEntry> allApps;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Drawable> iconCache = new HashMap<>();

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
        etSearch = findViewById(R.id.et_search);

        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new AppAdapter();
        recycler.setAdapter(adapter);

        fabDefault.setOnClickListener(v -> openDefaultEditor());
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    void reload() {
        glob = Config.getGlobal();
        boolean showSys = swSystem != null && swSystem.isChecked();
        allApps = Config.listApps(this, !showSys, glob);

        // Sort: Custom first, then by label alphabet
        allApps.sort((a, b) -> {
            if (a.hasOverride != b.hasOverride) {
                return a.hasOverride ? -1 : 1;
            }
            return String.valueOf(a.label).compareToIgnoreCase(String.valueOf(b.label));
        });

        android.util.Log.i("ResFixGUI", "listApps returned " + allApps.size()
                + " apps (showSystem=" + showSys + ")");
        
        filter(etSearch.getText().toString());
    }

    void filter(String query) {
        if (allApps == null) return;
        List<Config.AppEntry> filtered;
        if (android.text.TextUtils.isEmpty(query)) {
            filtered = allApps;
        } else {
            filtered = new java.util.ArrayList<>();
            String q = query.toLowerCase();
            for (Config.AppEntry e : allApps) {
                if (String.valueOf(e.label).toLowerCase().contains(q) || e.pkg.toLowerCase().contains(q)) {
                    filtered.add(e);
                }
            }
        }
        adapter.setApps(filtered);
        status.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
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
            h.label.setSelected(true);
            h.pkg.setText(e.pkg);
            h.pkg.setSelected(true);
            h.cardSys.setVisibility(e.isSystem ? View.VISIBLE : View.GONE);
            String prefix = e.hasOverride
                    ? h.root.getContext().getString(R.string.custom_prefix)
                    : h.root.getContext().getString(R.string.default_prefix);
            String res = prefix + e.w + "x" + e.h + (e.density > 0 ? " @" + e.density : "");
            h.res.setText(res);
            if (e.hasOverride) {
                h.res.setTextColor(h.root.getContext().getColor(R.color.primary));
            } else {
                h.res.setTextColor(h.root.getContext().getColor(android.R.color.white));
            }
            h.root.setOnClickListener(v -> {
                Intent i = new Intent(AppListActivity.this, AppDetailActivity.class);
                i.putExtra("pkg", e.pkg);
                startActivity(i);
            });

            // Lazy load icon
            h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            h.tag = e.pkg;
            final String pkgName = e.pkg;
            Drawable cached = iconCache.get(pkgName);
            if (cached != null) {
                h.icon.setImageDrawable(cached);
            } else {
                executor.execute(() -> {
                    try {
                        PackageManager pm = getPackageManager();
                        final Drawable icon = pm.getApplicationIcon(pkgName);
                        iconCache.put(pkgName, icon);
                        handler.post(() -> {
                            if (pkgName.equals(h.tag)) {
                                h.icon.setImageDrawable(icon);
                            }
                        });
                    } catch (Exception ignored) {
                    }
                });
            }
        }

        @Override
        public int getItemCount() { return apps == null ? 0 : apps.size(); }

        class VH extends RecyclerView.ViewHolder {
            View root, cardSys; TextView label, pkg, res;
            ImageView icon; String tag;
            VH(View v) { super(v); root = v; label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_pkg); res = v.findViewById(R.id.tv_res);
                cardSys = v.findViewById(R.id.card_sys);
                icon = v.findViewById(R.id.iv_icon); }
        }
    }
}
