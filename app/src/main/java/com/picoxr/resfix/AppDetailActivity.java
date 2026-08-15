package com.picoxr.resfix;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

/**
 * Per-app (or default) resolution editor. If pkg == "" it edits the global default
 * for non-system apps (stored under "default"), otherwise under "apps[<pkg>]".
 */
public class AppDetailActivity extends AppCompatActivity {

    String pkg;
    TextView tvTitle, tvPkg;
    ImageView ivIcon;
    MaterialSwitch swEnable, swDock;
    Spinner spPreset, spPresetSwap;
    TextInputEditText etW, etH, etDensity;
    MaterialButton btnSave, btnApplyRestart, btnRemove, btnSwapVal;

    final String[] resFloatArr = {"1280 × 722","1600 × 902","1920 × 1082","2560 × 1442","3840 × 2162"};
    final String[] resDockArr = {"807 × 432","1127 × 752","1447 × 1072","1767 × 1392","2087 × 1712"};

    @Override
    protected void onCreate(Bundle b) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        pkg = getIntent().getStringExtra("pkg");

        Toolbar toolbar = findViewById(R.id.detail_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvTitle = findViewById(R.id.tv_title);
        tvPkg = findViewById(R.id.tv_pkg);
        ivIcon = findViewById(R.id.iv_icon);
        swEnable = findViewById(R.id.sw_enable);
        swDock = findViewById(R.id.sw_dock);
        spPreset = findViewById(R.id.sp_preset);
        spPresetSwap = findViewById(R.id.sp_preset_swap);
        etW = findViewById(R.id.et_w);
        etH = findViewById(R.id.et_h);
        etDensity = findViewById(R.id.et_density);
        btnSave = findViewById(R.id.btn_save);
        btnApplyRestart = findViewById(R.id.btn_apply_restart);
        btnRemove = findViewById(R.id.btn_remove);
        btnSwapVal = findViewById(R.id.btn_swap_val);

        if (TextUtils.isEmpty(pkg)) {
            tvTitle.setText(R.string.default_title);
            tvPkg.setText(R.string.default_cfg);
            ivIcon.setImageResource(R.mipmap.ic_launcher);
        } else {
            tvPkg.setText(pkg);
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                tvTitle.setText(pm.getApplicationLabel(ai));
                ivIcon.setImageDrawable(pm.getApplicationIcon(ai));
            } catch (Exception e) {
                tvTitle.setText(R.string.detail_title);
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
        }
        tvTitle.setSelected(true);
        tvPkg.setSelected(true);

        // Floating Resolution
        String[] itemsFloat = new String[resFloatArr.length + 1];
        itemsFloat[0] = getString(R.string.select_preset);
        System.arraycopy(resFloatArr, 0, itemsFloat, 1, resFloatArr.length);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, itemsFloat);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPreset.setAdapter(adapter);
        spPreset.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    applyDimensions(resFloatArr[position - 1]);
                    spPresetSwap.setSelection(0);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Dock Resolution
        String[] itemsDock = new String[resDockArr.length + 1];
        itemsDock[0] = getString(R.string.select_preset);
        System.arraycopy(resDockArr, 0, itemsDock, 1, resDockArr.length);
        ArrayAdapter<String> adapterSwap = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, itemsDock);
        adapterSwap.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spPresetSwap.setAdapter(adapterSwap);
        spPresetSwap.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) {
                    applyDimensions(resDockArr[position - 1]);
                    spPreset.setSelection(0);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadCurrent();
        if (TextUtils.isEmpty(pkg)) {
            swDock.setVisibility(View.GONE);
            btnApplyRestart.setVisibility(View.GONE);
        }
        swEnable.setOnCheckedChangeListener((x, checked) -> {
            spPreset.setEnabled(checked);
            spPresetSwap.setEnabled(checked);
            etW.setEnabled(checked); etH.setEnabled(checked); etDensity.setEnabled(checked);
        });

        btnSave.setOnClickListener(v -> save());
        btnApplyRestart.setOnClickListener(v -> {
            if (save()) restartTargetApp();
        });
        btnRemove.setOnClickListener(v -> removeOverride());
        btnSwapVal.setOnClickListener(v -> {
            Editable tw = etW.getText();
            Editable th = etH.getText();
            String sw = tw != null ? tw.toString() : "";
            String sh = th != null ? th.toString() : "";
            etW.setText(sh);
            etH.setText(sw);
        });
    }

    // Helper method to split the string and set text
    private void applyDimensions(String resolution) {
        String[] dimensions = resolution.split(" × ");
        if (dimensions.length == 2) {
            etW.setText(dimensions[0]);
            etH.setText(dimensions[1]);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void loadCurrent() {
        Config.GlobalCfg glob = Config.getGlobal();
        JSONObject root = Config.readRoot();
        JSONObject target = null;
        boolean enabled = true;
        boolean isDock = TextUtils.isEmpty(pkg) && swDock.isChecked();
        try {
            if (TextUtils.isEmpty(pkg)) {
                target = Config.defaultObj(root);
            } else {
                JSONObject apps = Config.appsObj(root);
                if (apps.has(pkg)) {
                    target = apps.getJSONObject(pkg);
                    enabled = !target.optBoolean("disabled", false);
                    // Keep legacy resolution-only entries on the APK's native window route.
                    // A missing dock key means no window-mode override, not Floating.
                    isDock = target.has("dock")
                            ? target.optBoolean("dock", false)
                            : Config.isAppDockMode(getPackageManager(), pkg, null);
                } else {
                    isDock = Config.isAppDockMode(getPackageManager(), pkg, null);
                }
            }
        } catch (Throwable ignored) {}

        if (TextUtils.isEmpty(pkg)) {
            if (target != null) {
                if (isDock) {
                    etW.setText(String.valueOf(target.optInt("near_w", glob.dockWidth)));
                    etH.setText(String.valueOf(target.optInt("near_h", glob.dockHeight)));
                    etDensity.setText(target.has("near_density") ? String.valueOf(target.optInt("near_density")) : String.valueOf(glob.dockDensity));
                } else {
                    etW.setText(String.valueOf(target.optInt("w", glob.floatingWidth)));
                    etH.setText(String.valueOf(target.optInt("h", glob.floatingHeight)));
                    etDensity.setText(target.has("density") ? String.valueOf(target.optInt("density")) : String.valueOf(glob.floatingDensity));
                }
            } else {
                if (isDock) {
                    etW.setText(String.valueOf(glob.dockWidth));
                    etH.setText(String.valueOf(glob.dockHeight));
                    etDensity.setText(String.valueOf(glob.dockDensity));
                } else {
                    etW.setText(String.valueOf(glob.floatingWidth));
                    etH.setText(String.valueOf(glob.floatingHeight));
                    etDensity.setText(String.valueOf(glob.floatingDensity));
                }
            }
        } else {
            if (target != null) {
                etW.setText(String.valueOf(target.optInt("w", isDock ? glob.dockWidth : glob.floatingWidth)));
                etH.setText(String.valueOf(target.optInt("h", isDock ? glob.dockHeight : glob.floatingHeight)));
                etDensity.setText(String.valueOf(target.optInt("density", isDock ? glob.dockDensity : glob.floatingDensity)));
            } else {
                etW.setText(String.valueOf(isDock ? glob.dockWidth : glob.floatingWidth));
                etH.setText(String.valueOf(isDock ? glob.dockHeight : glob.floatingHeight));
                etDensity.setText(String.valueOf(isDock ? glob.dockDensity : glob.floatingDensity));
            }
            swDock.setChecked(isDock);
        }

        swEnable.setChecked(enabled);
        boolean en = enabled || TextUtils.isEmpty(pkg); // default always editable
        spPreset.setEnabled(en);
        spPresetSwap.setEnabled(en);
        etW.setEnabled(en); etH.setEnabled(en); etDensity.setEnabled(en);
    }

    boolean save() {
        Config.GlobalCfg glob = Config.getGlobal();
        boolean isDockInEditor = swDock.isChecked();
        int defW = isDockInEditor ? glob.dockWidth : glob.floatingWidth;
        int defH = isDockInEditor ? glob.dockHeight : glob.floatingHeight;
        
        int w = parseInt(etW, defW), h = parseInt(etH, defH);
        if (w < 320 || h < 240) {
            Toast.makeText(this, R.string.invalid_res, Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            JSONObject root = Config.readRoot();
            if (TextUtils.isEmpty(pkg)) {
                JSONObject target = Config.defaultObj(root);
                root.put("default", target);
                String pfx = isDockInEditor ? "near_" : "";
                target.put(pfx + "w", w); target.put(pfx + "h", h);
                String d = etDensity.getText() != null ? etDensity.getText().toString().trim() : "";
                if (!TextUtils.isEmpty(d)) target.put(pfx + "density", parseIntStr(d));
                else target.remove(pfx + "density");
            } else {
                JSONObject apps = Config.appsObj(root);
                JSONObject target = apps.optJSONObject(pkg);
                if (target == null) { target = new JSONObject(); apps.put(pkg, target); }
                root.put("apps", apps);
                target.put("disabled", !swEnable.isChecked());
                target.put("dock", swDock.isChecked());
                target.put("w", w); target.put("h", h);
                String d = etDensity.getText() != null ? etDensity.getText().toString().trim() : "";
                if (!TextUtils.isEmpty(d)) target.put("density", parseIntStr(d));
                else target.remove("density");
            }
            boolean ok = Config.writeRoot(root);
            Toast.makeText(this, ok ? getString(R.string.saved_toast) : getString(R.string.write_failed), Toast.LENGTH_LONG).show();
            return ok;
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.save_failed) + ": " + t, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    void restartTargetApp() {
        final String targetPkg = pkg;
        new Thread(() -> {
            boolean restarted = false;
            try {
                Process process = new ProcessBuilder("su", "-c",
                        "am force-stop " + targetPkg + "; monkey -p " + targetPkg + " 1")
                        .redirectErrorStream(true)
                        .start();
                restarted = process.waitFor() == 0;
            } catch (Throwable ignored) {}

            final boolean success = restarted;
            runOnUiThread(() -> {
                Toast.makeText(this, success ? R.string.app_restarted_toast : R.string.restart_failed,
                        Toast.LENGTH_LONG).show();
                if (success) finish();
            });
        }).start();
    }

    void removeOverride() {
        try {
            JSONObject root = Config.readRoot();
            if (!TextUtils.isEmpty(pkg)) {
                JSONObject apps = Config.appsObj(root);
                apps.remove(pkg);
                root.put("apps", apps);
            } else {
                root.remove("default");
            }
            boolean ok = Config.writeRoot(root);
            Toast.makeText(this, ok ? getString(R.string.remove_toast) : getString(R.string.write_failed), Toast.LENGTH_LONG).show();
            if (ok) finish();
        } catch (Throwable t) { Toast.makeText(this, R.string.failed, Toast.LENGTH_SHORT).show(); }
    }

    static int parseInt(TextInputEditText e, int def) {
        if (e.getText() == null) return def;
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }
    static int parseIntStr(String s) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return 0; }
    }
}
