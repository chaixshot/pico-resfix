package com.picoxr.resfix;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * ResFix hook — per-app virtual-display resolution override for PICO 4.
 *
 * We hook AppContainer.createVirtualDisplay(String,int,int,int,int) in
 * com.picovr.systemext, invoked by AppRecord as createVirtualDisplay("NS_APP[<pkg>]",
 * this.mWidth, this.mHeight, this.mDensity, flags).
 *
 * To avoid the "right-side clipping" caused by overriding only the w/h ARGS (which leaves
 * AppRecord.mScale/mWidth/mHeight inconsistent), we override BOTH:
 *   - the call ARGS (w,h,density)  -> virtual display buffer is created at the target res
 *   - the this-object FIELDS (mWidth,mHeight,mDensity) -> SystemExt's own calculateScale(900/h)
 *     and later resizeSurface() stay consistent, so the on-screen window keeps ~1600x900
 *     physical size while rendering the higher-res buffer (supersample, no clipping).
 *
 * Config: /data/local/tmp/resfix.cfg (JSON)
 *   { "default": { "w":1920,"h":1080,"density":200,"applyThird":true,"applySystem":false },
 *     "apps":    { "<pkg>": { "w":2560,"h":1440,"density":240 } } }
 */
public class ResFix implements IXposedHookLoadPackage {

    static final String TAG = "PicoResFix";
    static final String CONFIG = "/data/local/tmp/resfix.cfg";

    static final class Cfg {
        int w, h, density = -1;
        boolean applyThird = true, applySystem = false;
    }

    private static String readConfigText() {
        try {
            File f = new File(CONFIG);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            int n = in.read(data);
            in.close();
            return new String(data, 0, n, StandardCharsets.UTF_8);
        } catch (Throwable t) { return null; }
    }

    static Cfg defaultConfig() {
        Cfg c = new Cfg();
        try {
            String s = readConfigText();
            if (s == null) return c;
            JSONObject root = new JSONObject(s);
            if (root.has("default")) {
                JSONObject d = root.getJSONObject("default");
                c.w = d.optInt("w", 0);
                c.h = d.optInt("h", 0);
                c.density = d.has("density") ? d.getInt("density") : -1;
                c.applyThird = d.optBoolean("applyThird", true);
                c.applySystem = d.optBoolean("applySystem", false);
            }
        } catch (Throwable ignored) {}
        return c;
    }

    static Cfg appConfig(String pkg) {
        try {
            String s = readConfigText();
            if (s == null) return null;
            JSONObject root = new JSONObject(s);
            if (!root.has("apps")) return null;
            JSONObject apps = root.getJSONObject("apps");
            if (!apps.has(pkg)) return null;
            JSONObject a = apps.getJSONObject(pkg);
            if (a.optBoolean("disabled", false)) return null;
            Cfg c = new Cfg();
            c.w = a.optInt("w", 0);
            c.h = a.optInt("h", 0);
            c.density = a.has("density") ? a.getInt("density") : -1;
            if (c.w <= 0 || c.h <= 0) return null;
            return c;
        } catch (Throwable ignored) { return null; }
    }

    static boolean isNonSystemApp(Object container) {
        if (container == null) return true;
        try {
            java.lang.reflect.Method m = container.getClass().getMethod("isSystemApp");
            m.setAccessible(true);
            return !((Boolean) m.invoke(container));
        } catch (Throwable t) { return true; }
    }

    static String pkgFromName(String name) {
        if (name == null || !name.startsWith("NS_APP[")) return null;
        int end = name.indexOf(']');
        if (end < 0) return null;
        String inner = name.substring("NS_APP[".length(), end);
        return inner.isEmpty() ? null : inner;
    }

    static String fieldString(Object o, String f) {
        try { return (String) XposedHelpers.getObjectField(o, f); }
        catch (Throwable t) { return null; }
    }

    /** Fallback pkg from this-object component name. */
    static String pkgFromThis(Object o) {
        try {
            Object cn = XposedHelpers.getObjectField(o, "mComponentName");
            if (cn != null) {
                String p = (String) cn.getClass().getMethod("getPackageName").invoke(cn);
                if (p != null) return p;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Decide target config. Returns null when this AppRecord should NOT be resized.
     */
    static Cfg decide(String pkg, Object appRecord) {
        boolean sys = !isNonSystemApp(appRecord);
        Cfg app = (pkg != null) ? appConfig(pkg) : null;
        if (app != null) return app;
        Cfg glob = defaultConfig();
        if (sys && !glob.applySystem) return null;
        if (!sys && !glob.applyThird) return null;
        if (glob.w <= 0 || glob.h <= 0) return null;
        return glob;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (lp.packageName == null || !"com.picovr.systemext".equals(lp.packageName)) return;
        try {
            Class<?> appContainer = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);
            XposedHelpers.findAndHookMethod(appContainer, "createVirtualDisplay",
                    String.class, int.class, int.class, int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            String pkg = pkgFromName(name);
                            if (pkg == null) return;   // not a flat 2D app (e.g. NS_WINDOW_/caption)

                            Cfg cfg = decide(pkg, param.thisObject);
                            if (cfg == null || cfg.w <= 0 || cfg.h <= 0) return;

                            int ow = (Integer) param.args[1];
                            int oh = (Integer) param.args[2];
                            int od = (Integer) param.args[3];

                            // override call args (virtual display buffer)
                            param.args[1] = cfg.w;
                            param.args[2] = cfg.h;
                            if (cfg.density > 0) param.args[3] = cfg.density;

                            // override this-object fields so mScale + later resizeSurface stay consistent
                            try {
                                XposedHelpers.setIntField(param.thisObject, "mWidth", cfg.w);
                                XposedHelpers.setIntField(param.thisObject, "mHeight", cfg.h);
                                if (cfg.density > 0) {
                                    XposedHelpers.setIntField(param.thisObject, "mDensity", cfg.density);
                                }
                            } catch (Throwable ignored) {}

                            int nd = (Integer) param.args[3];
                            boolean sys = !isNonSystemApp(param.thisObject);
                            XposedBridge.log(TAG + ": " + name + " " + (sys ? "[sys]" : "[3rd]")
                                    + " " + ow + "x" + oh + "@" + od + " -> " + cfg.w + "x" + cfg.h + "@" + nd
                                    + " (mScale-consistent)");
                        }
                    });
            XposedBridge.log(TAG + ": installed (per-app, mScale-consistent)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed");
            XposedBridge.log(t);
        }
    }
}
