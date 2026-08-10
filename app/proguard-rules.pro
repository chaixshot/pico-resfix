# LSPosed discovers the module entry via the IXposedHookLoadPackage interface
# (implemented by ResFix) and calls handleLoadPackage by name. Keep that entry.
-keep class com.picoxr.resfix.ResFix implements de.robv.android.xposed.IXposedHookLoadPackage {
    <init>();
    void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage$LoadPackageParam);
}

# ResApp (settings Activity) is referenced from the manifest.
-keep class com.picoxr.resfix.ResApp { *; }

# Xposed API is compileOnly (provided at runtime by LSPosed/Vector).
# We must NOT keep nor package these classes — Vector rejects modules that bundle them.
-dontwarn de.robv.android.xposed.**
