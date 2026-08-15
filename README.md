[English](README.md) | [简体中文](README_zh.md) | [Русский](README_ru.md)

# PICO 2D Resolution — Per-App Virtual-Display Resolution Mod

A resolution unlocking module for 2D flat applications on **PICO 4 Standard Edition (A8110, Android 10 / API 29)**.
Allows **per-app configuration** of 2D virtual display resolution (no longer locked at 1600×900), custom DPI, and switching specified apps from far-field floating windows to near-field Dock.

Implemented by injecting into `com.picovr.systemext` using **Zygisk Vector (LSPosed compatible framework)** — no system APK replacement required.

---

## Prerequisites

- **Device**: Pico 4 Headset (Phoenix/China firmware supported).
- **Permissions**: **Root Access** ([Guide](https://pico4.wiki/guides/root/01-root/)) is required to apply changes to system files.
- **Environment**: **Magisk** + **LSPosed Framework** ([Zygisk Vector](https://github.com/JingMatrix/Vector)) must be installed and active.
- **LSPosed Scope**: Ensure `com.picovr.systemext` is selected in the module scope.

---

## 1. Features

| Item | Original | Unlocked |
|---|---|---|
| 2D App Virtual Display Resolution | 1602×902 (density 200) | **Per-app configuration** (Default 2560×1440) |
| DPI | Fixed at 200 | **Overridable per-app** (Optional) |
| Window Mode | Far-field Floating | **Switchable to Near-field Dock per-app** |
| Scope | All 2D Apps | **Enabled by default for non-system apps, optional for system apps** |

- Only changes resolution (px) + optional DPI. **Without changing density, it results in supersampling, keeping the aspect ratio unchanged.**
- Independent configuration per app, no mutual interference.

---

## 2. Architecture

```
PICO 4 (Android 10, API 29)
└─ Magisk + Zygisk Vector v2.2 (LSPosed compatible framework)
   └─ This Module com.picoxr.resfix (mid)
       ├─ LSPosed hook: ResFix
       │    hooks com.picovr.systemext's
       │    AppContainer.createVirtualDisplay(String,int,int,int,int)
       │    → Parses "NS_APP[<pkg>]" → Checks config by package name → Overrides w/h(/density)
       │    AppManagerUtils.getWindowType(ActivityInfo)
       │    → Returns "near" (type 2002) for Dock apps by package name
       └─ GUI: AppListActivity + AppDetailActivity
            (App list + per-app resolution settings)

Config path: /data/local/tmp/resfix.cfg (JSON)
  - Written by GUI App (root)
  - Read in real-time by ResFix hook during each createVirtualDisplay
```

---

## 3. Why hook instead of replacing APK?

- `SystemExt` is a PERSISTENT system app with `sharedUserId="android.uid.system"`. Replacing the APK with a self-signed one would be rejected by PackageManager. Thus, LSPosed hook is used.

---

## 4. Config Format (/data/local/tmp/resfix.cfg)

```json
{
  "default": { "w": 2560, "h": 1440, "density": 200,
               "applyThird": true, "applySystem": false },
  "apps": {
    "com.example.app": { "w": 1920, "h": 1080, "density": 240, "dock": true }
  }
}
```

- `default`: Uniform resolution for non-configured non-system apps (when applyThird=true).
- `apps.<pkg>`: Individual override for an app (use `"disabled": true` to disable).
- `apps.<pkg>.dock`: When `true`, routes this app to the native Near-field Dock; this setting is independent of resolution override.
- `density` omitted = Follow system; `-1`/non-existent = Do not change density.

Changes require fully closing and restarting the target app.

---

## 5. Build (CLI only)

Requirements: JDK 17 + Android SDK (platform 34, build-tools 34) + Gradle 8.7

```bash
gradle :app:assembleDebug
```

---

## 6. Deployment

1. `adb install -r app-debug.apk`
2. Update LSPosed module database `apk_path`
3. `adb reboot` (Vector rescans modules)
4. Open "PICO 2D Resolution" on Home → App List → Tap an app to set resolution or enable Dock → Save
5. Restart the target 2D app to apply changes
