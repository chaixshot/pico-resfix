package com.picoxr.resfix;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Config persistence for ResFix.
 * The config JSON lives at /data/local/tmp/resfix.cfg (world-writable tmp, read by the
 * Xposed hook inside com.picovr.systemext). The GUI app writes it via root (su).
 */
public final class Config {

    public static final String PATH = "/data/local/tmp/resfix.cfg";

    /** One app descriptor shown in the list. */
    public static class AppEntry {
        public String pkg;
        public CharSequence label;
        public boolean isSystem;
        public boolean hasOverride;
        public int w, h, density;   // effective values
    }

    public static class GlobalCfg {
        public int w = 1602, h = 902, density = 200;   // stock 1602x902 (matches physical window, no clipping)
        public boolean applyThird = true, applySystem = false;
    }

    // --- read ---
    public static String readRaw() {
        try {
            File f = new File(PATH);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] d = new byte[(int) f.length()];
            int n = in.read(d); in.close();
            return new String(d, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) { return null; }
    }

    public static JSONObject readRoot() {
        String s = readRaw();
        if (s == null) return new JSONObject();
        try { return new JSONObject(s); } catch (Throwable t) { return new JSONObject(); }
    }

    public static JSONObject defaultObj(JSONObject root) {
        try {
            if (root.has("default")) return root.getJSONObject("default");
        } catch (Throwable t) {}
        return new JSONObject();
    }

    public static JSONObject appsObj(JSONObject root) {
        try {
            if (root.has("apps")) return root.getJSONObject("apps");
        } catch (Throwable t) {}
        return new JSONObject();
    }

    public static GlobalCfg getGlobal() {
        GlobalCfg g = new GlobalCfg();
        try {
            JSONObject d = defaultObj(readRoot());
            g.w = d.optInt("w", g.w);
            g.h = d.optInt("h", g.h);
            g.density = d.has("density") ? d.getInt("density") : g.density;
            g.applyThird = d.optBoolean("applyThird", true);
            g.applySystem = d.optBoolean("applySystem", false);
        } catch (Throwable ignored) {}
        return g;
    }

    /** List installed apps (any with a launchable activity). filterSystem hides system apps.
     *  PICO does not expose most 2D apps via the standard MAIN/LAUNCHER intent, so we list ALL
     *  installed apps that have at least one activity (covers the full third-party set). */
    public static List<AppEntry> listApps(Context ctx, boolean filterSystem, GlobalCfg glob) {
        List<AppEntry> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> all = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<ApplicationInfo> sorted = new java.util.ArrayList<>(all);
            java.util.Collections.sort(sorted,
                    (a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
            JSONObject appsJ = appsObj(readRoot());
            for (ApplicationInfo ai : sorted) {
                String pkg = ai.packageName;
                if (pkg == null || pkg.equals(ctx.getPackageName())) continue; // hide ourselves
                boolean sys = (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
                if (sys && filterSystem) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                CharSequence lb = ai.loadLabel(pm);
                e.label = (lb != null) ? lb : pkg;
                e.isSystem = sys;
                if (appsJ.has(pkg)) {
                    JSONObject a = appsJ.optJSONObject(pkg);
                    if (a != null && !a.optBoolean("disabled", false)) {
                        e.hasOverride = true;
                        e.w = a.optInt("w", 0);
                        e.h = a.optInt("h", 0);
                        e.density = a.has("density") ? a.getInt("density") : -1;
                    }
                }
                if (!e.hasOverride) {
                    e.w = glob.w; e.h = glob.h; e.density = glob.density;
                }
                out.add(e);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // --- write (via su) ---
    public static boolean writeRoot(JSONObject root) {
        String json = root.toString();
        try {
            Process p = new ProcessBuilder("su", "-c",
                    "cat > " + PATH).redirectErrorStream(false).start();
            // write to su's stdin
            java.io.OutputStream os = p.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush(); os.close();
            int code = p.waitFor();
            // ensure perms
            new ProcessBuilder("su", "-c", "chmod 666 " + PATH).start().waitFor();
            return code == 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
