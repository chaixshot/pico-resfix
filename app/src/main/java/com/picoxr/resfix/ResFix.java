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
 * Config: /data/local/tmp/resfix.cfg (JSON, written by the ResFix GUI app)
 *   {
 *     "default": { "w":2560, "h":1440, "density":200, "applyThird":true, "applySystem":false },
 *     "apps":    { "<pkg>": { "w":1920, "h":1080, "density":240 } }
 *   }
 *
 * Hooks AppContainer.createVirtualDisplay(String,int,int,int,int) in com.picovr.systemext.
 * name is "NS_APP[<pkg>]". For each flat 2D app we apply the per-app override if present,
 * else the default (only if the scope flag matches: non-system needs applyThird, system
 * needs applySystem OR a per-app entry). Density is overridden only when specified.
 */
public class ResFix implements IXposedHookLoadPackage {

    static final String TAG = "PicoResFix";
    static final String CONFIG = "/data/local/tmp/resfix.cfg";

    static final class Cfg {
        int w = 2560, h = 1440, density = -1;   // density -1 = keep original
        boolean applyThird = true, applySystem = false, hasPerApp = false;
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
        } catch (Throwable t) {
            return null;
        }
    }

    /** Global default config (fallback). Never null. */
    static Cfg defaultConfig() {
        Cfg c = new Cfg();
        try {
            String s = readConfigText();
            if (s == null) return c;
            JSONObject root = new JSONObject(s);
            if (root.has("default")) {
                JSONObject d = root.getJSONObject("default");
                c.w = d.optInt("w", c.w);
                c.h = d.optInt("h", c.h);
                c.density = d.has("density") ? d.getInt("density") : -1;
                c.applyThird = d.optBoolean("applyThird", true);
                c.applySystem = d.optBoolean("applySystem", false);
            }
        } catch (Throwable ignored) {}
        return c;
    }

    /** Per-app override for <pkg>, or null if none/disabled. */
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
            c.hasPerApp = true;
            c.w = a.optInt("w", 0);
            c.h = a.optInt("h", 0);
            c.density = a.has("density") ? a.getInt("density") : -1;
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean isNonSystemApp(Object container) {
        if (container == null) return true;
        try {
            java.lang.reflect.Method m = container.getClass().getMethod("isSystemApp");
            m.setAccessible(true);
            return !((Boolean) m.invoke(container));
        } catch (Throwable t) {
            return true;
        }
    }

    static String pkgFromName(String name) {
        if (name == null || !name.startsWith("NS_APP[")) return null;
        int end = name.indexOf(']');
        if (end < 0) return null;
        String inner = name.substring("NS_APP[".length(), end);
        return inner.isEmpty() ? null : inner;
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
                            if (pkg == null) return;   // not a flat 2D app

                            boolean sys = !isNonSystemApp(param.thisObject);

                            // decide config
                            Cfg app = appConfig(pkg);
                            Cfg use;

                            if (app != null) {
                                // explicit per-app config always applies (unless disabled already filtered)
                                use = app;
                            } else {
                                Cfg g = defaultConfig();
                                // no per-app entry: apply default only if scope matches
                                if (sys && !g.applySystem) return;
                                if (!sys && !g.applyThird) return;
                                use = g;
                            }
                            if (use.w <= 0 || use.h <= 0) return;

                            int ow = (Integer) param.args[1];
                            int oh = (Integer) param.args[2];
                            int od = (Integer) param.args[3];

                            param.args[1] = use.w;
                            param.args[2] = use.h;
                            if (use.density > 0) param.args[3] = use.density;

                            int nd = (Integer) param.args[3];
                            String sc = sys ? "[sys] " : "[3rd] ";
                            XposedBridge.log(TAG + ": " + name + " " + sc
                                    + ow + "x" + oh + "@" + od + " -> " + use.w + "x" + use.h + "@" + nd);
                        }
                    });
            XposedBridge.log(TAG + ": installed (per-app resolution override)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed");
            XposedBridge.log(t);
        }
    }
}
