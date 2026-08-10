package com.picoxr.resfix;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Per-app (or default) resolution editor. If pkg == "" it edits the global default
 * for non-system apps (stored under "default"), otherwise under "apps[<pkg>]".
 */
public class AppDetailActivity extends Activity {

    String pkg;
    TextView tvTitle, tvPkg;
    Switch swEnable;
    RadioGroup rgPreset;
    EditText etW, etH, etDensity;
    Button btnSave, btnRemove;

    final String[] wArr = {"1280","1600","1920","2560","3840"};
    final String[] hArr = {"720","900","1080","1440","2160"};
    final String[] nArr = {"1280 × 720","1600 × 900","1920 × 1080","2560 × 1440","3840 × 2160"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_detail);
        pkg = getIntent().getStringExtra("pkg");
        tvTitle = findViewById(R.id.tv_title);
        tvPkg = findViewById(R.id.tv_pkg);
        swEnable = findViewById(R.id.sw_enable);
        rgPreset = findViewById(R.id.rg_preset);
        etW = findViewById(R.id.et_w);
        etH = findViewById(R.id.et_h);
        etDensity = findViewById(R.id.et_density);
        btnSave = findViewById(R.id.btn_save);
        btnRemove = findViewById(R.id.btn_remove);

        if (TextUtils.isEmpty(pkg)) {
            tvTitle.setText(R.string.default_title);
            tvPkg.setText("默认（未单独设置的非系统应用）");
        } else {
            tvTitle.setText("应用分辨率");
            tvPkg.setText(pkg);
        }

        // presets
        for (int i = 0; i < nArr.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(nArr[i]);
            rb.setId(-1 - i);
            rb.setOnClickListener(v -> { etW.setText(wArr[-rb.getId()-1]); etH.setText(hArr[-rb.getId()-1]); });
            rgPreset.addView(rb);
        }

        loadCurrent();
        swEnable.setOnCheckedChangeListener((x, checked) -> {
            for (int i = 0; i < rgPreset.getChildCount(); i++) rgPreset.getChildAt(i).setEnabled(checked);
            etW.setEnabled(checked); etH.setEnabled(checked); etDensity.setEnabled(checked);
        });

        btnSave.setOnClickListener(v -> save());
        btnRemove.setOnClickListener(v -> removeOverride());
    }

    void loadCurrent() {
        JSONObject root = Config.readRoot();
        JSONObject target = null;
        boolean enabled = true;
        try {
            if (TextUtils.isEmpty(pkg)) {
                target = Config.defaultObj(root);
            } else {
                JSONObject apps = Config.appsObj(root);
                if (apps.has(pkg)) {
                    target = apps.getJSONObject(pkg);
                    enabled = !target.optBoolean("disabled", false);
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
        boolean en = enabled || TextUtils.isEmpty(pkg); // default always editable
        for (int i = 0; i < rgPreset.getChildCount(); i++) rgPreset.getChildAt(i).setEnabled(en);
        etW.setEnabled(en); etH.setEnabled(en); etDensity.setEnabled(en);
    }

    void save() {
        int w = parseInt(etW, 1602), h = parseInt(etH, 902);
        if (w < 320 || h < 240) { Toast.makeText(this,"无效分辨率",Toast.LENGTH_SHORT).show(); return; }
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
            }
            target.put("w", w); target.put("h", h);
            String d = etDensity.getText().toString().trim();
            if (!TextUtils.isEmpty(d)) target.put("density", parseIntStr(d));
            else target.remove("density");
            boolean ok = Config.writeRoot(root);
            Toast.makeText(this, ok ? getString(R.string.saved_toast) : "写入失败(root)", Toast.LENGTH_LONG).show();
            if (ok) finish();
        } catch (Throwable t) {
            Toast.makeText(this, "保存失败: "+t, Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, ok ? getString(R.string.remove_toast) : "写入失败", Toast.LENGTH_LONG).show();
            if (ok) finish();
        } catch (Throwable t) { Toast.makeText(this,"失败",Toast.LENGTH_LONG).show(); }
    }

    static int parseInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }
    static int parseIntStr(String s) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return 0; }
    }
}
