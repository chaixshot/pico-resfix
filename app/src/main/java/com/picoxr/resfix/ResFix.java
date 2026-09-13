package com.picoxr.resfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Per-app virtual-display resolution and dock-mode override for PICO SystemExt. */
public class ResFix implements IXposedHookLoadPackage {
    static final String TAG = "PicoResFix";
    static final String CONFIG = "/data/local/tmp/resfix.cfg";
    private static final String SELF_PACKAGE = "com.picoxr.resfix";
    private static final String CONFIG_SETTING = "pico_systemext_coord_resfix_config";
    private static final String GENERATION_SETTING = "pico_systemext_coord_resfix_generation";

    /** Window-type constants: 2002 = near-field dock, 3002 = far-field floating. */
    private static final int WINDOW_TYPE_DOCK = 2002;
    private static final int WINDOW_TYPE_FLOATING = 3002;
    private static final String NEAR_SPACE_POSITION = "near";

    /**
     * Hook callbacks run far more often than the config changes, and every probe costs a
     * file read plus two binder calls. Re-probe at most this often; the GUI bumps the
     * generation setting on every write, so changes still apply within one interval.
     */
    private static final long CONFIG_PROBE_INTERVAL_MS = 400;

    static final class Cfg {
        final int w;
        final int h;
        final int density;
        final boolean applyThird;
        final boolean applySystem;

        Cfg(int w, int h, int density, boolean applyThird, boolean applySystem) {
            this.w = w;
            this.h = h;
            this.density = density;
            this.applyThird = applyThird;
            this.applySystem = applySystem;
        }
    }

    private static final class Snapshot {
        final String key;
        final JSONObject root;

        Snapshot(String key, JSONObject root) {
            this.key = key;
            this.root = root;
        }
    }

    private static volatile Snapshot snapshot;
    private static volatile long lastProbeElapsed;
    private static volatile boolean loggedStaleConfig;
    /** Launch context as a stack: AppRecord.obtain() and prepareAppData() may nest. */
    private static final ThreadLocal<List<String>> launchingPackages = new ThreadLocal<>();
    /** getPackageName() lookup per class, including the "no such method" negative. */
    private static final ConcurrentHashMap<Class<?>, Optional<Method>> GETTER_CACHE =
            new ConcurrentHashMap<>();
    /** Last window type routed per package, so per-callback logging only fires on change. */
    private static final ConcurrentHashMap<String, Integer> lastRoutedType = new ConcurrentHashMap<>();

    private static Context systemContext() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            return activityThread == null ? null
                    : (Context) XposedHelpers.callMethod(activityThread, "getSystemContext");
        } catch (Throwable t) {
            log("failed to obtain system context", t);
            return null;
        }
    }

    private static Snapshot configSnapshot() {
        Snapshot current = snapshot;
        long now = SystemClock.elapsedRealtime();
        if (current != null && now - lastProbeElapsed < CONFIG_PROBE_INTERVAL_MS) return current;
        lastProbeElapsed = now;

        Context context = systemContext();
        String generation = null;
        String settingsConfig = null;
        if (context != null) {
            try {
                generation = Settings.Global.getString(context.getContentResolver(), GENERATION_SETTING);
                settingsConfig = Settings.Global.getString(context.getContentResolver(), CONFIG_SETTING);
            } catch (Throwable t) {
                log("failed to read configuration settings", t);
            }
        }

        String fileConfig = readFileConfig();
        JSONObject parsed = parseConfig(fileConfig, CONFIG);
        String source = CONFIG;
        if (parsed == null) {
            parsed = parseConfig(settingsConfig, "Settings.Global");
            source = "Settings.Global";
        }
        if (parsed == null) {
            Snapshot last = snapshot;
            if (last != null) {
                if (!loggedStaleConfig) {
                    loggedStaleConfig = true;
                    log("configuration reload failed; keeping last valid snapshot", null);
                }
                return last;
            }
            parsed = new JSONObject();
        }
        loggedStaleConfig = false;

        String content = source + ":" + (source.equals(CONFIG) ? fileConfig : settingsConfig);
        String key = (generation == null ? "" : generation) + ":" + content.hashCode();
        Snapshot fresh = new Snapshot(key, parsed);
        snapshot = fresh;
        return fresh;
    }

    private static JSONObject parseConfig(String text, String source) {
        if (text == null || text.isEmpty()) return null;
        try {
            return ConfigSchema.parse(text);
        } catch (Throwable t) {
            log("invalid configuration from " + source, t);
            return null;
        }
    }

    private static String readFileConfig() {
        try {
            File file = new File(CONFIG);
            if (!file.exists() || file.length() > ConfigSchema.MAX_CONFIG_BYTES) return null;
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > ConfigSchema.MAX_CONFIG_BYTES) return null;
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Throwable t) {
            log("failed to read configuration file", t);
            return null;
        }
    }

    static Cfg defaultConfig(boolean dock) {
        JSONObject value = configSnapshot().root.optJSONObject("default");
        if (value == null) return new Cfg(0, 0, -1, true, false);
        try {
            String widthKey = dock ? "near_w" : "w";
            String heightKey = dock ? "near_h" : "h";
            String densityKey = dock ? "near_density" : "density";
            int width = value.has(widthKey) ? value.getInt(widthKey) : 0;
            int height = value.has(heightKey) ? value.getInt(heightKey) : 0;
            int density = value.has(densityKey) ? value.getInt(densityKey) : -1;
            return new Cfg(width, height, density, value.optBoolean("applyThird", true),
                    value.optBoolean("applySystem", false));
        } catch (Throwable t) {
            log("invalid default configuration", t);
            return new Cfg(0, 0, -1, true, false);
        }
    }

    static Cfg appConfig(String pkg, boolean dock) {
        if (pkg == null) return null;
        JSONObject apps = configSnapshot().root.optJSONObject("apps");
        JSONObject value = apps == null ? null : apps.optJSONObject(pkg);
        if (value == null || value.optBoolean("disabled", false)) return null;
        try {
            String widthKey = dock ? "near_w" : "w";
            String heightKey = dock ? "near_h" : "h";
            String densityKey = dock ? "near_density" : "density";
            // Older entries only had w/h; keep them working for Dock until explicitly split.
            if (dock && (!value.has(widthKey) || !value.has(heightKey))) {
                widthKey = "w";
                heightKey = "h";
                densityKey = "density";
            }
            int width = value.getInt(widthKey);
            int height = value.getInt(heightKey);
            int density = value.has(densityKey) ? value.getInt(densityKey) : -1;
            return ConfigSchema.isResolutionValid(width, height)
                    && (density < 0 || ConfigSchema.isDensityValid(density))
                    ? new Cfg(width, height, density, true, false) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static Boolean dockOverride(String pkg) {
        // Single choke point for the self-exclusion so every dock hook inherits it.
        if (pkg == null || SELF_PACKAGE.equals(pkg)) return null;
        JSONObject apps = configSnapshot().root.optJSONObject("apps");
        JSONObject app = apps == null ? null : apps.optJSONObject(pkg);
        return app != null && app.has("dock") ? app.optBoolean("dock") : null;
    }

    static Boolean isSystemApp(Object container) {
        if (container == null) return null;
        try {
            Method method = container.getClass().getMethod("isSystemApp");
            method.setAccessible(true);
            Object result = method.invoke(container);
            return result instanceof Boolean ? (Boolean) result : null;
        } catch (Throwable t) {
            log("unable to classify app record", t);
            return null;
        }
    }

    static String pkgFromName(String name) {
        if (name == null || !name.startsWith("NS_APP[")) return null;
        int end = name.indexOf(']');
        if (end < 0) return null;
        String inner = name.substring("NS_APP[".length(), end);
        return inner.isEmpty() ? null : inner;
    }

    static String fieldString(Object object, String field) {
        try { return (String) XposedHelpers.getObjectField(object, field); }
        catch (Throwable t) { return null; }
    }

    static String pkgFromThis(Object object) {
        if (object == null) return null;
        // getPackageName() method, cached per class - this runs on every hook event.
        String pkg = invokeGetter(object, "getPackageName");
        if (pkg != null && !pkg.isEmpty()) return pkg;

        // Direct package field.
        pkg = fieldString(object, "mPackageName");
        if (pkg != null && !pkg.isEmpty()) return pkg;

        // Last structural fallback.
        try {
            Object componentName = XposedHelpers.getObjectField(object, "mComponentName");
            if (componentName != null) {
                pkg = invokeGetter(componentName, "getPackageName");
                if (pkg != null && !pkg.isEmpty()) return pkg;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String invokeGetter(Object object, String methodName) {
        Optional<Method> cached = GETTER_CACHE.get(object.getClass());
        if (cached == null) {
            Method found = null;
            try {
                found = object.getClass().getMethod(methodName);
                found.setAccessible(true);
            } catch (Throwable ignored) {}
            cached = Optional.ofNullable(found);
            GETTER_CACHE.putIfAbsent(object.getClass(), cached);
            cached = GETTER_CACHE.get(object.getClass());
        }
        if (!cached.isPresent()) return null;
        try {
            return (String) cached.get().invoke(object);
        } catch (Throwable t) {
            return null;
        }
    }

    static String currentLaunchPackage() {
        List<String> stack = launchingPackages.get();
        return stack == null || stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    /** Removes any launch frame still visible from the current thread. */
    static void clearLaunchPackage() {
        launchingPackages.remove();
    }

    static Optional<String> pushLaunchPackage(String pkg) {
        if (pkg == null) return Optional.empty();
        List<String> stack = launchingPackages.get();
        if (stack == null) {
            stack = new ArrayList<>();
            launchingPackages.set(stack);
        }
        stack.add(pkg);
        return Optional.of(pkg);
    }

    static void popLaunchPackage(String pkg) {
        if (pkg == null) return;
        List<String> stack = launchingPackages.get();
        if (stack == null) return;
        int index = stack.lastIndexOf(pkg);
        if (index >= 0) stack.remove(index);
        if (stack.isEmpty()) launchingPackages.remove();
    }

    static boolean nativeDockMode(String pkg, Object appRecord) {
        try {
            Context context = systemContext();
            if (context == null || pkg == null) return false;
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(
                    pkg, PackageManager.GET_META_DATA);
            return Config.isAppDockMode(pm, pkg, ai);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Returns null when this AppRecord should not be resized. */
    static Cfg decide(String pkg, Object appRecord) {
        if (SELF_PACKAGE.equals(pkg)) return null;
        Boolean dock = dockOverride(pkg);
        boolean effectiveDock = dock != null ? dock : nativeDockMode(pkg, appRecord);
        Cfg app = appConfig(pkg, effectiveDock);
        if (app != null) return app;
        Cfg global = defaultConfig(effectiveDock);
        if (!ConfigSchema.isResolutionValid(global.w, global.h)) return null;
        Boolean system = isSystemApp(appRecord);
        if (system == null) return null;
        if (system && !global.applySystem) return null;
        if (!system && !global.applyThird) return null;
        return global;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"com.picovr.systemext".equals(lp.packageName)) return;
        installResolutionHook(lp);
        installDockHooks(lp);
    }

    private static void installResolutionHook(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> appContainer = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);
            // Signature: (String name, int w, int h, int density, int flags)
            XposedHelpers.findAndHookMethod(appContainer, "createVirtualDisplay",
                    String.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String name = (String) param.args[0];
                                String pkg = pkgFromName(name);
                                if (pkg == null) return;
                                Cfg cfg = decide(pkg, param.thisObject);
                                if (cfg == null) return;

                                int targetW = cfg.w, targetH = cfg.h;
                                int targetDensity = cfg.density > 0 ? cfg.density : currentDensity(param.thisObject);
                                param.args[1] = targetW;
                                param.args[2] = targetH;
                                param.args[3] = targetDensity;

                                setInt(param.thisObject, "mWidth", targetW, true);
                                setInt(param.thisObject, "mHeight", targetH, true);
                                if (cfg.density > 0) setInt(param.thisObject, "mDensity", targetDensity, false);

                                XposedBridge.log(TAG + ": route " + pkg + " -> "
                                        + targetW + "x" + targetH + "@" + targetDensity);
                            } catch (Throwable t) {
                                log("createVirtualDisplay hook failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("failed to install resolution hook", t);
        }
        installAppRecordFix(lp);
    }

    private static void installAppRecordFix(XC_LoadPackage.LoadPackageParam lp) {
        try {
            final Class<?> appRecord = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppRecord", lp.classLoader);
            hook("AppRecord.prepareAppData", () -> XposedHelpers.findAndHookMethod(appRecord,
                    "prepareAppData", Context.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            pushLaunchPackage(pkgFromThis(param.thisObject));
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object container = param.thisObject;
                                String pkg = pkgFromThis(container);
                                if (pkg == null) pkg = currentLaunchPackage();
                                if (pkg == null) return;

                                Cfg cfg = decide(pkg, container);
                                if (cfg != null) {
                                    setInt(container, "mWidth", cfg.w, false);
                                    setInt(container, "mHeight", cfg.h, false);
                                }

                                Boolean dock = dockOverride(pkg);
                                if (dock != null) {
                                    XposedHelpers.setObjectField(container, "mAppResizeable", dock);
                                    trySetIntField(container, "mWindowType",
                                            dock ? WINDOW_TYPE_DOCK : WINDOW_TYPE_FLOATING);
                                    trySetIntField(container, "mType",
                                            dock ? WINDOW_TYPE_DOCK : WINDOW_TYPE_FLOATING);
                                }
                            } catch (Throwable t) {
                                log("AppRecord.prepareAppData hook failed", t);
                            } finally {
                                popLaunchPackage(pkgFromThis(param.thisObject));
                            }
                        }
                    }));
        } catch (Throwable t) {
            log("failed to install AppRecord fix", t);
        }
    }

    private static int currentDensity(Object container) {
        try {
            return XposedHelpers.getIntField(container, "mDensity");
        } catch (Throwable t) {
            return 200;
        }
    }

    private static void setInt(Object obj, String field, int value, boolean required) {
        try {
            XposedHelpers.setIntField(obj, field, value);
        } catch (Throwable t) {
            if (required) log("failed to set required field " + field, t);
        }
    }

    private static void installDockHooks(XC_LoadPackage.LoadPackageParam lp) {
        final Class<?> appManagerUtils, appRecord, appContainer;
        try {
            appManagerUtils = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppManagerUtils", lp.classLoader);
            appRecord = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppRecord", lp.classLoader);
            appContainer = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppContainer", lp.classLoader);
        } catch (Throwable t) {
            log("failed to resolve dock hook classes", t);
            return;
        }

        hook("AppManagerUtils.isNearPanel", () -> hookBooleanReturn(
                appManagerUtils, "isNearPanel", pkgFromFirstArgOrLaunch(), true));
        hook("AppManagerUtils.isVrActivity", () -> hookBooleanReturn(
                appManagerUtils, "isVrActivity", pkgFromFirstArg(), false));
        hook("AppManagerUtils.isVrApp", () -> hookBooleanReturn(
                appManagerUtils, "isVrApp", pkgFromFirstArg(), false));
        hook("AppManagerUtils.getVrSpacePosition", () -> hookStringReturn(
                appManagerUtils, "getVrSpacePosition",
                pkgFromFirstArgOrLaunch(), NEAR_SPACE_POSITION));
        hook("AppRecord.getVrSpacePosition", () -> hookStringReturn(
                appRecord, "getVrSpacePosition", pkgFromThisOrLaunch(), NEAR_SPACE_POSITION));
        hook("AppRecord.resizeable", () -> hookBooleanReturn(
                appRecord, "resizeable", pkgFromThisOrLaunch(), true));

        hook("AppRecord.<init>", () -> hookAllConstructors(appRecord, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String pkg = pkgFromThisOrLaunch(param.thisObject);
                    if (isDocked(pkg)) {
                        trySetIntField(param.thisObject, "mType", WINDOW_TYPE_DOCK);
                        trySetIntField(param.thisObject, "mWindowType", WINDOW_TYPE_DOCK);
                        XposedHelpers.setObjectField(param.thisObject, "mAppResizeable", true);
                    }
                } catch (Throwable t) {
                    log("AppRecord constructor hook failed", t);
                }
            }
        }));

        hook("AppContainer.updateVisible", () -> XposedHelpers.findAndHookMethod(
                appContainer, "updateVisible", boolean.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length >= 2 && !(Boolean) param.args[0]
                                    && (Integer) param.args[1] == 6
                                    && isDocked(pkgFromThisOrLaunch(param.thisObject))) {
                                param.setResult(false);
                            }
                        } catch (Throwable t) {
                            log("updateVisible hook failed", t);
                        }
                    }
                }));

        installAppRecordObtainHook(lp, appRecord);
        installDockRoutingExtras(lp);
    }

    private static void installAppRecordObtainHook(XC_LoadPackage.LoadPackageParam lp,
            Class<?> appRecord) {
        final Class<?> activityInfo = XposedHelpers.findClass("android.content.pm.ActivityInfo", lp.classLoader);
        hook("AppRecord.obtain", () -> XposedHelpers.findAndHookMethod(appRecord, "obtain",
                Context.class, activityInfo, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args.length > 1 && param.args[1] != null) {
                                pushLaunchPackage(fieldString(param.args[1], "packageName"));
                            }
                        } catch (Throwable ignored) {}
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        String pushed = null;
                        if (param.args.length > 1 && param.args[1] != null) {
                            pushed = fieldString(param.args[1], "packageName");
                        }
                        try {
                            Object record = param.getResult();
                            if (record == null) return;
                            String pkg = currentLaunchPackage();
                            if (pkg == null) pkg = pkgFromThis(record);
                            if (isDocked(pkg)) {
                                trySetIntField(record, "mType", WINDOW_TYPE_DOCK);
                                trySetIntField(record, "mWindowType", WINDOW_TYPE_DOCK);
                                XposedHelpers.setObjectField(record, "mAppResizeable", true);
                            }
                        } catch (Throwable t) {
                            log("AppRecord.obtain hook failed", t);
                        } finally {
                            popLaunchPackage(pushed);
                        }
                    }
                }));
    }

    private static void installDockRoutingExtras(XC_LoadPackage.LoadPackageParam lp) {
        // These names are internal PICO routing APIs. The exact overloads are selected by
        // return type below, so a changed or absent overload is skipped instead of receiving
        // a result whose type could throw at its call site.
        final Class<?> appManagerService, immersiveModeManager, activityStarterControl, controlUtils;
        try {
            appManagerService = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.AppManagerService", lp.classLoader);
            immersiveModeManager = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.action.ImmersiveModeManager", lp.classLoader);
            activityStarterControl = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.ActivityStarterControl", lp.classLoader);
            controlUtils = XposedHelpers.findClass(
                    "com.bytedance.nativeshell.appmanager.util.ActivityStarterControlUtils", lp.classLoader);
        } catch (Throwable t) {
            // The core dock and window hooks remain active; the optional routing fixes are
            // only useful for specific firmware builds and need no diagnostic noise.
            return;
        }

        hook("AppManagerService.notifyAllowAppStart", () -> hookBooleanReturn(
                appManagerService, "notifyAllowAppStart", pkgFromFirstArg(), true));
        hook("AppManagerService.onAllowStartApp", () -> hookBooleanReturn(
                appManagerService, "onAllowStartApp", pkgFromFirstArg(), true));
        hook("ImmersiveModeManager.onAllowStartApp", () -> hookBooleanReturn(
                immersiveModeManager, "onAllowStartApp", pkgFromFirstArg(), true));
        hook("ActivityStarterControl.checkAppSwitchAllowed", () -> hookBooleanReturn(
                activityStarterControl, "checkAppSwitchAllowed", pkgFromFirstArg(), true));
        hook("ActivityStarterControlUtils.checkAppSwitchAllowed", () -> hookBooleanReturn(
                controlUtils, "checkAppSwitchAllowed", pkgFromFirstArg(), true));

        // Void display-tip methods have no return value, so suppressing is a safe no-return
        // override. Only exact void overloads matching the configured dock package run.
        hook("AppManagerService.showImmersiveTips", () -> hookVoidReturn(
                appManagerService, "showImmersiveTips"));
        hook("ImmersiveModeManager.showImmersiveTips", () -> hookVoidReturn(
                immersiveModeManager, "showImmersiveTips"));
    }

    private interface PackageProvider {
        String get(XC_MethodHook.MethodHookParam param);
    }

    private static PackageProvider pkgFromThis() {
        return param -> pkgFromThis(param.thisObject);
    }

    private static PackageProvider pkgFromFirstArg() {
        return param -> {
            if (param.args.length == 0 || param.args[0] == null) return null;
            return fieldString(param.args[0], "packageName");
        };
    }

    private static PackageProvider pkgFromThisOrLaunch() {
        return param -> pkgFromThisOrLaunch(param.thisObject);
    }

    private static PackageProvider pkgFromFirstArgOrLaunch() {
        return param -> {
            String pkg = pkgFromFirstArg().get(param);
            return pkg != null ? pkg : currentLaunchPackage();
        };
    }

    private static String pkgFromThisOrLaunch(Object object) {
        String pkg = pkgFromThis(object);
        return pkg != null ? pkg : currentLaunchPackage();
    }

    private static boolean isDocked(String pkg) {
        return Boolean.TRUE.equals(dockOverride(pkg));
    }

    private static void hookBooleanReturn(Class<?> type, String methodName,
            PackageProvider provider, boolean dockedResult) throws Throwable {
        hookMethodsOfType(type, methodName, boolean.class, provider, dockedResult);
    }

    private static void hookStringReturn(Class<?> type, String methodName,
            PackageProvider provider, String dockedResult) throws Throwable {
        hookMethodsOfType(type, methodName, String.class, provider, dockedResult);
    }

    private static void hookVoidReturn(Class<?> type, String methodName) throws Throwable {
        for (Method method : type.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || method.getReturnType() != void.class) continue;
            hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = currentLaunchPackage();
                        if (isDocked(pkg)) param.setResult(null);
                    } catch (Throwable t) {
                        log("suppress immersive tips hook failed", t);
                    }
                }
            });
        }
    }

    private static void hookMethodsOfType(Class<?> type, String methodName, Class<?> returnType,
            PackageProvider provider, Object dockedResult) throws Throwable {
        for (Method method : type.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || method.getReturnType() != returnType) continue;
            final Object result = dockedResult;
            hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String pkg = provider.get(param);
                        if (isDocked(pkg)) param.setResult(result);
                    } catch (Throwable t) {
                        log("dock classification hook failed", t);
                    }
                }
            });
        }
    }

    private static void hookMethod(Method method, XC_MethodHook hook) throws Throwable {
        Class<?>[] parameters = method.getParameterTypes();
        Object[] args = new Object[parameters.length + 1];
        System.arraycopy(parameters, 0, args, 0, parameters.length);
        args[parameters.length] = hook;
        XposedHelpers.findAndHookMethod(method.getDeclaringClass(), method.getName(), args);
    }

    /** Each dock hook installs independently so one missing internal method cannot
     *  take down the hooks registered after it. */
    private interface HookInstall {
        void install() throws Throwable;
    }

    private static void hook(String name, HookInstall install) {
        try {
            install.install();
            XposedBridge.log(TAG + ": installed " + name);
        } catch (Throwable t) {
            log("failed to install " + name, t);
        }
    }

    private static void hookAllConstructors(Class<?> clazz, XC_MethodHook hook) {
        try {
            XposedBridge.class.getMethod("hookAllConstructors", Class.class, XC_MethodHook.class)
                    .invoke(null, clazz, hook);
        } catch (Throwable t) {
            // Not fatal on its own, but dock apps created outside obtain() will keep their
            // native window type - this log is what makes that diagnosable.
            log("hookAllConstructors unavailable; constructor-level dock override inactive", t);
        }
    }

    private static void trySetIntField(Object obj, String field, int value) {
        try { XposedHelpers.setIntField(obj, field, value); }
        catch (Throwable ignored) {}
    }

    private static void log(String message, Throwable error) {
        XposedBridge.log(TAG + ": " + message + (error == null ? "" : " (" + error + ")"));
    }
}
