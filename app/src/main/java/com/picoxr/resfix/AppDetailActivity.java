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
    MaterialButton btnSave, btnRemove, btnSwapVal;

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
        }
        swEnable.setOnCheckedChangeListener((x, checked) -> {
            spPreset.setEnabled(checked);
            spPresetSwap.setEnabled(checked);
            etW.setEnabled(checked); etH.setEnabled(checked); etDensity.setEnabled(checked);
        });

        btnSave.setOnClickListener(v -> save());
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
                    isDock = target.optBoolean("dock", false);
                }
            }
        } catch (Throwable ignored) {}
        if (target != null) {
            try {
                etW.setText(String.valueOf(target.optInt("w", 1602)));
                etH.setText(String.valueOf(target.optInt("h", 902)));
                if (target.has("density")) etDensity.setText(String.valueOf(target.getInt("density")));
            } catch (Throwable ignored) {}
        } else {
            etW.setText("1602"); etH.setText("902");
        }
        swEnable.setChecked(enabled);
        swDock.setChecked(isDock);
        boolean en = enabled || TextUtils.isEmpty(pkg); // default always editable
        spPreset.setEnabled(en);
        spPresetSwap.setEnabled(en);
        etW.setEnabled(en); etH.setEnabled(en); etDensity.setEnabled(en);
    }

    void save() {
        int w = parseInt(etW, 1602), h = parseInt(etH, 902);
        if (w < 320 || h < 240) { Toast.makeText(this,R.string.invalid_res,Toast.LENGTH_SHORT).show(); return; }
        try {
            JSONObject root = Config.readRoot();
            JSONObject target;
            if (TextUtils.isEmpty(pkg)) {
                target = Config.defaultObj(root);
                root.put("default", target);
            } else {
                JSONObject apps = Config.appsObj(root);
                target = apps.optJSONObject(pkg);
                if (target == null) { target = new JSONObject(); apps.put(pkg, target); }
                root.put("apps", apps);
                target.put("disabled", !swEnable.isChecked());
                target.put("dock", swDock.isChecked());
            }
            target.put("w", w); target.put("h", h);
            String d = etDensity.getText() != null ? etDensity.getText().toString().trim() : "";
            if (!TextUtils.isEmpty(d)) target.put("density", parseIntStr(d));
            else target.remove("density");
            boolean ok = Config.writeRoot(root);
            Toast.makeText(this, ok ? getString(R.string.saved_toast) : getString(R.string.write_failed), Toast.LENGTH_LONG).show();
            if (ok) finish();
        } catch (Throwable t) {
            Toast.makeText(this, getString(R.string.save_failed) + ": " + t, Toast.LENGTH_LONG).show();
        }
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
