# PVZ2 Auto — Shizuku 系统级安卓自动化

一个最小可运行的 Kotlin Android 应用，用 **Shizuku** 拿到 shell 级权限后自动化 PVZ2：

```
截图 (screencap) → OpenCV 模板匹配 (TM_CCOEFF_NORMED) → 坐标换算 → 注入点击 (input tap) → 循环
```

核心循环跑在**前台服务**的 `Dispatchers.IO` 协程里；Shizuku 不可用时自动降级到
**MediaProjection + AccessibilityService** 应用层方案。

---

## 📌 文档导航

| 文档 | 内容 |
|---|---|
| **[docs/HANDOFF.md](docs/HANDOFF.md)** | **项目交接文档** —— 每次改动做了什么、验证到什么程度、留下什么坑。接手前先读这个。 |
| [docs/VERIFY_CHAIN.md](docs/VERIFY_CHAIN.md) | 「方案 C 识别链」的真机验证操作手册（含分数判读表） |
| [tools/shizuku/README.md](tools/shizuku/README.md) | Shizuku 服务端的启动脚本与踩坑记录 |

> ⚠️ 本 README 描述的是**项目骨架与权限链路**，其中「构建验证状态 / 运行时验证状态」
> 两节记录的是最初脚手架的真机实测结果。`maa/` 包的开发进展、方案 C 验证、
> 以及尚未接入主循环的部分，**以 `docs/HANDOFF.md` 为准**。

---

## ⚠️ 一处必须说明的方案修正

任务清单里写的是「Shizuku + `screencap` 命令 / `input` 命令」，隐含使用
`Shizuku.newProcess()`。**但这条路在 Shizuku API 13 上已经不通了**，我没有照抄，原因如下（已用字节码核实）：

| 证据 | 结论 |
|---|---|
| `javap -p` 反编译 `dev.rikka.shizuku:api:13.1.5` | `private static ShizukuRemoteProcess newProcess(String[], String[], String)` —— **是 `private`，应用根本调不到** |
| 同版本 `Shizuku.java` 源码 | 方法带 `@deprecated`，注释写明 *"planned to be removed from Shizuku API 14"* |
| 官方 README（13.1.1 changelog） | *"Prepare to remove `Shizuku#newProcess`, developers **have to use `UserService`** instead … `UserService` can replace `newProcess` **in all cases**"* |
| `javap -p` 反编译 `api:12.2.0` | 该版本 `newProcess` 仍是 `public static` |

所以本实现按官方要求改用 **UserService**：Shizuku 在它自己的 `app_process` 里以
shell(uid 2000) 或 root(uid 0) 身份实例化我们的 `Pvz2UserService`，通过 AIDL 调用。
截图/点击/滑动最终仍然是 `screencap -p` 和 `input` 命令，只是执行位置从「临时进程」
换成了「常驻的 UserService 进程」——这也是官方推荐的做法，且省掉了每次操作 fork 进程的开销。

> 参考：[Shizuku-API README](https://github.com/RikkaApps/Shizuku-API) · [Shizuku 官网](https://shizuku.rikka.app/)

---

## 技术选型核对结果

所有版本号与 API 用法都不是猜的，而是从官方发布产物里核对出来的：

| 模块 | 选型 | 核对方式 |
|---|---|---|
| Shizuku SDK | `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5` | Maven Central `maven-metadata.xml`；README「Add dependency」 |
| Shizuku 接入 | `ShizukuProvider`，authority `${applicationId}.shizuku` | README「Acquire the Binder → Shizuku」原文 |
| Shizuku 授权 | `checkSelfPermission` / `shouldShowRequestPermissionRationale` / `requestPermission` + 结果监听 | README「Request permission」示例 |
| 执行特权命令 | `Shizuku.bindUserService(UserServiceArgs, ServiceConnection)` | README「UserService」 |
| UserService 清理 | AIDL 里 `void destroy() = 16777114` | README「Stop the User Service」原话 |
| 截图 | UserService 内 `screencap -p`，经 `ParcelFileDescriptor` 管道回传 | `IRemoteProcess.getInputStream()` 返回 PFD，同一官方模式 |
| 触控 | `input tap` / `input swipe` | — |
| 图像识别 | `org.opencv:opencv:4.13.0`（Maven Central 官方包） | `maven-metadata.xml`；源码 jar 核对签名 |
| OpenCV 初始化 | `OpenCVLoader.initLocal()` | 源码 jar：`public static boolean initLocal()` |
| 模板匹配 | `Imgproc.matchTemplate(..., Imgproc.TM_CCOEFF_NORMED)`（=5）、`Core.minMaxLoc(Mat)` → `MinMaxLocResult.maxVal/maxLoc` | 源码 jar 逐条核对 |
| 缩放 | `Imgproc.resize(src, dst, Size(), fx, fy, Imgproc.INTER_AREA)`（=3） | 源码 jar 核对 |
| 降级截图 | `MediaProjection` + `ImageReader(RGBA_8888)` | [Android 媒体投影官方文档](https://developer.android.google.cn/media/grow/media-projection) |
| 降级触控 | `AccessibilityService#dispatchGesture` + `canPerformGestures="true"` | [Foreground service types](https://developer.android.google.cn/about/versions/14/changes/fgs-types-required) / 官方 API |
| 前台服务类型 | 清单声明 `specialUse\|mediaProjection`，运行时**按后端各传一个** | 同上，官方「Include foreground service type at runtime」 |
| 并发 | Kotlin 协程 `Dispatchers.IO` | — |

### Android 14 前台服务类型的硬性顺序

官方文档规定，`mediaProjection` 类型的顺序不能调换，代码里严格照做：

1. `createScreenCaptureIntent()` → 用户授权（Activity）
2. 启动 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 前台服务
3. 之后才允许 `getMediaProjection()`，且**必须先 `registerCallback()`** 才能 `createVirtualDisplay()`

Shizuku 路径不涉及 MediaProjection，因此用 `specialUse`（清单里带
`android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）。

---

## 坐标适配

基准 **1280×720**，高度匹配、水平居中（等价于 Unity `CanvasScaler.matchWidthOrHeight = 1`）：

```
scale   = 屏幕高 / 720
offsetX = (屏幕宽 − 1280 × scale) / 2

屏幕坐标 = (offsetX + 逻辑x × scale , 逻辑y × scale)
```

高度严格对齐，所以 `y` 不需要偏移；`offsetX` 在 16:9 上为 0，在更宽的屏上是正数（左右留黑边），
在更窄的屏上是负数（左右各裁掉一点），`ScreenAdapter.toScreenRect()` 会做裁剪。

**自适应是拿真实帧的宽高做的**（`matcher.setScreen(frame.width(), frame.height())`），
不是去问 WindowManager。这样旋转屏幕、分屏都不用额外处理，模板会自动按新尺寸重算。

---

## 目录结构

```
app/src/main/
├─ aidl/com/pvz2/auto/IPvz2Service.aidl      Shizuku UserService 的 AIDL 契约
├─ assets/templates/                          720P 模板 PNG（见该目录 README）
├─ res/xml/accessibility_service_config.xml   降级触控的服务配置
└─ java/com/pvz2/auto/
   ├─ MainActivity.kt                         权限控制台 + MediaProjection 授权
   ├─ core/
   │  ├─ ScreenAdapter.kt                     逻辑坐标 ↔ 屏幕坐标
   │  ├─ TemplateMatcher.kt                   模板加载/缩放/匹配
   │  ├─ ScreenCapturer.kt · TouchInjector.kt 两个后端抽象
   │  ├─ Mats.kt                              Bitmap→Mat 并回收 Bitmap
   │  └─ BotState.kt                          运行状态（StateFlow）
   ├─ shizuku/
   │  ├─ ShizukuBridge.kt                     可用性 + 权限
   │  ├─ ShizukuUserServiceClient.kt          绑定 UserService
   │  ├─ Pvz2UserService.kt                   ★ 以 shell/root 身份运行的实现
   │  └─ ShizukuBackends.kt                   截图/触控的 Shizuku 实现
   ├─ fallback/
   │  ├─ MediaProjectionCapturer.kt           应用层截图
   │  └─ Pvz2AccessibilityService.kt          应用层手势注入（贝塞尔路径）
   ├─ service/AutomationService.kt            前台服务，托管循环
   ├─ overlay/FloatingWindow.kt               可拖动悬浮启动/停止面板
   └─ task/
      ├─ Pvz2Bot.kt                           ★ 截图→匹配→换算→点击 主循环
      ├─ BotAction.kt · Pvz2Tasks.kt          规则表（用户可改）
```

---

## 使用步骤

1. **安装并启动 Shizuku**（[官网下载](https://shizuku.rikka.app/download/)）。
   非 root 设备需用 adb 或 Android 11+ 的无线调试启动一次。
2. 编译安装本应用，打开后依次点：
   1. `Authorise Shizuku` → 在 Shizuku 弹窗里允许
   2. `Allow floating panel` → 授予「显示在其他应用上层」
   3. `Allow notifications`（Android 13+）
   4. （可选，仅降级方案需要）`Enable fallback accessibility service`
3. 点 **`Grab one screenshot`** 抓一张图，按 `assets/templates/README.md` 裁出模板
   （先把截图缩到 1280×720 再裁），放进 `app/src/main/assets/templates/` 后重新编译。
4. 点 **`Start (Shizuku)`**。悬浮面板出现，`Start`/`Stop` 随时控制。

### 编译

```bash
./gradlew :app:assembleDebug
```

需要 **JDK 17**、Android SDK `platforms;android-35` 与 `build-tools;35.0.0`。

---

## 资源与线程

* 截图路径每帧产生一个 `Bitmap`，在 `Bitmap.toMatAndRecycle()` 里立刻回收；`Mat` 由循环
  在 `finally` 中 `release()`。模板 `Mat` 在 `TemplateMatcher.release()` 里统一释放。
* 截图管道：UserService 用**独立线程**把 PNG 灌进 `ParcelFileDescriptor` 管道。
  必须这样——`screencap` 输出 1~3 MB，而管道缓冲区只有 64 KB，同步拷贝会在 Binder 调用里死锁。
  用 PFD 而不是 `byte[]`，还避开了 Binder 约 1 MB 的事务上限（`TransactionTooLargeException`）。
* 所有耗时操作（截图、匹配、shell 往返）都在 `Dispatchers.IO`，主线程只碰 UI。
* 单步异常被 `Pvz2Bot.step()` 捕获并跳过本帧，循环不会崩。

---

## 构建验证状态

本项目**实际编译通过**，不是"看起来能编译"：

```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 46s
```

| 项目 | 结果 |
|---|---|
| 工具链 | Temurin JDK 17.0.20.1 · Gradle 8.13 · AGP 8.7.3 · Kotlin 2.0.21 |
| 编译告警 / 错误 | **0 个**（`clean assembleDebug` 全量重建） |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`，43.5 MB |
| 打包的原生库 | `arm64-v8a`、`armeabi-v7a` 的 `libopencv_java4.so` + `libc++_shared.so` |
| 合并后清单 | `foregroundServiceType="0x40000020"` = `SPECIAL_USE(0x40000000)` \| `MEDIA_PROJECTION(0x20)`，与「按后端各传一个」的策略一致 |
| Shizuku Provider | `android:authorities="com.pvz2.auto.shizuku"` 已合并进清单 |
| AIDL 生成 | `IPvz2Service$Stub` 含 `TRANSACTION_screencap/tap/swipe/exec/getDisplaySize/destroy` 六个事务 |
| 关键类 | `Pvz2UserService`、`Pvz2Bot`、`MediaProjectionCapturer`、`Pvz2AccessibilityService`、`FloatingWindow` 均已进 dex |

下面这些 Android API 是直接对 `platforms/android-35/android.jar` 用 `javap` 核对过的：
`ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE`(=1073741824)、`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`(=32)、
`AccessibilityService.dispatchGesture(GestureDescription, GestureResultCallback, Handler)`、
`MediaProjection.registerCallback(Callback, Handler)`、
`ParcelFileDescriptor.createPipe()` / `AutoCloseInputStream` / `AutoCloseOutputStream`、
`ImageReader.newInstance(int,int,int,int)`。

---

## 运行时验证状态（Shizuku 全链路，真机实测）

编译通过不等于能用，所以整条权限链路在 MuMu 模拟器（Android 12 / x86_64 / 1280×720）上实测过。
**两种权限身份都验证了**，因为目标用户是没有 root 的普通手机：

| 环节 | root 路线 (uid 0) | **ADB 路线 (uid 2000，普通手机)** |
|---|---|---|
| Shizuku 服务端进程 | `root shizuku_server` | **`shell shizuku_server`** |
| 我们的 UserService 进程 | `root com.pvz2.auto:pvz2` | **`shell com.pvz2.auto:pvz2`** |
| binder 送达 | ✅ `ShizukuProvider: binder received` | ✅ |
| 权限申请弹窗 | ✅「要允许PVZ2 Auto使用 Shizuku吗？」 | ✅ |
| 授权回调 | ✅ `dispatchPermissionConfirmationResult allowed=true` | ✅ |
| App 侧状态 | `Shizuku: ready (uid 0)` | **`Shizuku: ready (uid 2000)`** |
| UserService 绑定 | ✅ `user service connected` | ✅ |
| **特权截图** | ✅ | ✅ 48009 字节 PNG，1280×720 |
| **`input tap`** | ✅ | ✅ |
| 主循环 | ✅ `Running @ 1280x720` | ✅ `Running @ 1280x720` |

**结论：核心功能不需要 root。** `screencap`、`input`、`wm size` 本来就是 `shell` 自带的能力，
我们只用了这三样。root 与否只影响「读其他应用私有目录」这类我们不需要的能力。

### 关键实现点（都是实测踩出来的）

1. **`Shizuku.newProcess` 在 API 13 是 `private`**，官方 README 明确要求改用 `UserService`。
   所以 `Pvz2UserService` 跑在 Shizuku 的 `app_process` 里，通过 AIDL 通信。
2. **`UserServiceArgs` 必须调 `processNameSuffix()`**，否则 `forAdd()` 里的
   `Objects.requireNonNull(processName)` 直接 NPE。
3. **截图走 `ParcelFileDescriptor` 管道**，不是 `byte[]` —— Binder 事务上限约 1 MB，
   全分辨率 PNG 会 `TransactionTooLargeException`；且拷贝必须在**独立线程**做，
   因为 pipe 缓冲区只有 64 KB，同步拷贝会在 Binder 调用里死锁。
4. **启动 Shizuku 服务端的参数顺序有坑**：`-Dshizuku.library.path` 必须放在
   `/system/bin` **前面**，放后面会被 `app_process` 当成启动类名，
   报 `ClassNotFoundException: -Dshizuku.library.path=...`。

复现整条链路的开发脚本见 [`tools/shizuku/`](tools/shizuku/README.md)。

---


## 已知限制（未在真机验证）

本机没有 Android 设备，也没有 PVZ2 环境，所以**运行时行为未经验证**：

1. **未在真机跑过。** 编译、清单合并、dex 打包都已验证，但 `screencap`/`input` 的实际行为、
   悬浮窗在不同 ROM 上的表现、前台服务存活率都没有实测。
2. **`assets/templates/` 是空的。** 我没有 PVZ2 的真实素材，也无法凭空造出可用的模板。
   `Pvz2Tasks.defaultActions()` 里的规则名是占位约定，必须替换成你自己裁的图。
   模板为空时应用不会崩：循环照跑，状态栏会显示识别到的分辨率——用这个确认截图链路是否通。
3. **`Pvz2Tasks` 的规则是通用「识别到就点」模型**，不含任何 PVZ2 关卡策略。
   要打关卡请在 `Pvz2Tasks.defaultActions()` 里加规则。
4. **`screencap -p` 走管道**：Android 7 以前有 CRLF 破坏 PNG 的历史问题，minSdk 26 不受影响；
   若在个别 ROM 上解码失败，改成「先写 `/data/local/tmp` 再读文件」即可（UserService 内改 3 行）。
5. **Shizuku 版本兼容性**：目标是 Shizuku v13。`destroy()` 的事务号 16777114 按官方 README 填的。

---

## 构建环境残留

`_toolchain/`（约 1.9 GB）是我为了验证编译而下载的 JDK 17 + Gradle 8.13 + Android SDK，
`local.properties` 指向它，两者都已在 `.gitignore` 中忽略。

**如果你已有 Android Studio，可以直接删掉整个 `_toolchain/` 和 `local.properties`**，
用 Android Studio 自带的 JDK/SDK 打开本工程即可。

