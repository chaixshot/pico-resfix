package com.picoxr.resfix;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Main screen: lists installed 2D (launcher) apps. A "show system apps" switch filters
 * system apps. Tapping an app opens AppDetailActivity for its per-app resolution.
 * "默认设置" opens a simple editor for the global default (third-party apps).
 */
public class AppListActivity extends Activity {

    RecyclerView recycler;
    Switch swSystem;
    TextView status;
    Button btnDefault;
    AppAdapter adapter;
    Config.GlobalCfg glob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);

        recycler = findViewById(R.id.recycler);
        status = findViewById(R.id.status);
        swSystem = findViewById(R.id.sw_system);
        btnDefault = findViewById(R.id.btn_default);

        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        recycler.setAdapter(adapter);

        swSystem.setOnCheckedChangeListener((b, c) -> reload());
        btnDefault.setOnClickListener(v -> openDefaultEditor());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    void reload() {
        glob = Config.getGlobal();
        boolean showSys = swSystem.isChecked();
        List<Config.AppEntry> apps = Config.listApps(this, !showSys, glob);
        android.util.Log.i("ResFixGUI", "listApps returned " + apps.size()
                + " apps (showSystem=" + swSystem.isChecked() + ")");
        for (Config.AppEntry e : apps) {
            android.util.Log.i("ResFixGUI", "  " + (e.isSystem?"[sys]":"[3rd]") + " " + e.pkg);
        }
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
            h.sys.setVisibility(e.isSystem ? View.VISIBLE : View.GONE);
            String res = e.hasOverride
                    ? ("自定义: " + e.w + "x" + e.h + (e.density > 0 ? " @" + e.density : ""))
                    : ("默认: " + e.w + "x" + e.h + " @" + e.density);
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
            View root; TextView label, pkg, res, sys;
            VH(View v) { super(v); root = v; label = v.findViewById(R.id.tv_label);
                pkg = v.findViewById(R.id.tv_pkg); res = v.findViewById(R.id.tv_res);
                sys = v.findViewById(R.id.tv_sys); }
        }
    }
}
