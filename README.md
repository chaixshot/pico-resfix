# PICO 2D Resolution — Per-App Virtual-Display Resolution Mod

针对 **PICO 4 标准版 (A8110, Android 10 / API 29)** 的 2D 平面应用分辨率解锁模块。
允许**按应用单独设置** 2D 虚拟显示分辨率（不再锁定 1600×900）、自定义 DPI，并可将指定应用从远场浮窗切换至近场 Dock。

用 **Zygisk Vector (LSPosed 兼容框架)** 注入 `com.picovr.systemext` 实现 —— 无需替换系统 APK。

---

## 一、效果

| 项目 | 原厂 | 解锁后 |
|---|---|---|
| 2D 应用虚拟显示分辨率 | 1602×902 (density 200) | **按应用单独配置**（默认 2560×1440） |
| DPI | 固定 200 | **可按应用覆盖**（可选） |
| 窗口模式 | 远场浮窗 | **可按应用切换为近场 Dock** |
| 作用范围 | 所有 2D 应用 | **非系统应用默认启用，系统应用可选** |

- 只改分辨率（px）+ 可选 DPI，**不改 density 时是超采样效果，画面比例不变**。
- 每应用独立配置，互不影响。

---

## 二、架构

```
PICO 4 (Android 10, API 29)
└─ Magisk + Zygisk Vector v2.2 (LSPosed 兼容框架)
   └─ 本模块 com.picoxr.resfix (mid)
       ├─ LSPosed hook: ResFix
       │    hook com.picovr.systemext 的
       │    AppContainer.createVirtualDisplay(String,int,int,int,int)
       │    → 解析 "NS_APP[<pkg>]" → 按包名查配置 → 覆盖 w/h(/density)
       │    AppManagerUtils.getWindowType(ActivityInfo)
       │    → 按包名将 Dock 应用返回为 near (type 2002)
       └─ GUI: AppListActivity + AppDetailActivity
            （应用列表 + 每应用分辨率设置）

配置通道: /data/local/tmp/resfix.cfg (JSON)
  - GUI App (root) 写入
  - ResFix hook 每次 createVirtualDisplay 实时读取
```

**关键点**: hook 的是 **Java 方法**（systemext 是 Zygote 派生的系统 App），所以用 **LSPosed** 而非 native 注入。

---

## 三、为什么 hook 而非替换 APK

- `SystemExt` 是 `sharedUserId="android.uid.system"` 的 PERSISTENT 系统应用，自签名替换 APK 会被 PackageManager 拒绝。故用 LSPosed hook。

## 四、配置文件格式 (/data/local/tmp/resfix.cfg)

```json
{
  "default": { "w": 2560, "h": 1440, "density": 200,
               "applyThird": true, "applySystem": false },
  "apps": {
    "com.example.app": { "w": 1920, "h": 1080, "density": 240, "dock": true }
  }
}
```

- `default`: 未单独配置的非系统应用（applyThird=true 时）统一用此分辨率。
- `apps.<pkg>`: 某应用的单独覆盖（禁用用 `"disabled": true`）。
- `apps.<pkg>.dock`: `true` 时将此应用路由至原生近场 Dock；该设置独立于分辨率覆盖，关闭“启用覆盖”不会关闭 Dock。
- `density` 省略 = 跟随系统；`-1`/不存在 = 不改 density。

Dock 模式通过运行时 hook 复刻 Pico2Dock 所需的 SystemExt 行为，而不改 APK manifest 或 package metadata：

- `AppManagerUtils.getWindowType(ActivityInfo)` 在 SystemExt 创建窗口前将目标应用归为 `near`（窗口类型 `2002`）；SystemExt 随即使用原生近场 Dock 栈及 `900 × 600 dp` 布局。
- `AppRecord` 的可调整大小状态对 Dock 应用强制为启用，对应 Pico2Dock 写入的 `android:resizeableActivity="true"`。
- 全屏应用按 SystemExt 的专用原因 `6` 隐藏面板时，仅保留已配置 Dock 应用的近场面板；用户关闭、Home、息屏、透视模式和其他面板仍遵循原厂可见性逻辑。
- Pico2Dock 还写入 `pvr.2dtovr.mode`、`isPUI`、`pvr.vrshell.mode` 等 metadata；当前 PICO 4 framework/SystemExt 的 Java 消费链没有把它们用于近场窗口创建，因此模块不伪造这些 package metadata，以免把普通 2D 应用错误归类为 VR 应用。

改动后需完全关闭并重新启动目标应用。

> Dock 的窗口路由、可调整大小状态和全屏可见性均已按 PICO 4 SystemExt 的 Java 路径实现；仍需在真机上验证游戏内启动、应用内跳转、缩放及关闭 Dock 后的回退行为。

---

## 五、构建（本机无 Android Studio，全命令行）

需要: JDK 17 + Android SDK (platform 34, build-tools 34) + Gradle 8.7

```bash
# 1. 生成 Xposed compileOnly stub jar（把 de.robv stub 编成 jar，不打包进 APK）
#    stub 源码来自 pico4-winlimit/mod_window/stub
# 2. 构建
gradle :app:assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```

> **关键坑**: Xposed API 类必须 **compileOnly**（stub jar），**不能打包进 APK** ——
> Vector 会拒绝加载 "Xposed API classes are compiled into the module's APK" 的模块。

---

## 六、部署

1. `adb install -r app-debug.apk`
2. 更新 LSPosed 模块数据库 apk_path（见 `../pico4-winlimit/lsp_mod/` 的脚本）
3. `adb reboot`（Vector 重新扫描模块）
4. 桌面打开 "PICO 2D Resolution" → 应用列表 → 点应用设分辨率或打开“以停靠模式启动” → 保存
5. 重开目标 2D 应用生效

---

## 七、目录

```
resfix-gui/
├─ settings.gradle / build.gradle / gradle.properties
├─ app/
│  ├─ build.gradle
│  ├─ proguard-rules.pro
│  └─ src/main/
│     ├─ AndroidManifest.xml        # LSPosed module + launcher Activity
│     ├─ assets/xposed_init         # 入口 com.picoxr.resfix.ResFix
│     ├─ java/com/picoxr/resfix/
│     │  ├─ ResFix.java             # Xposed hook（按包名覆盖分辨率/DPI）
│     │  ├─ AppListActivity.java    # 应用列表 + 显示系统应用开关
│     │  ├─ AppDetailActivity.java  # 单应用分辨率设置
│     │  └─ Config.java             # resfix.cfg 读写
│     └─ res/                       # 布局/字符串/图标
└─ (../pico4-winlimit/lsp_mod/ 含 lspd 数据库部署脚本)
```

---

## 进度/验证 (2026-08-11)

- ✅ per-app 单独分辨率生效（papertracker 单独 1920×1080，其他默认 2560×1440）
- ✅ 默认配置（非系统 2560×1440，系统 app 不动）
- ✅ hook 读 resfix.cfg 实时生效，无异常
- ✅ GUI App 正常启动、列出应用、无崩溃
