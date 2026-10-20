# 代码安全与质量审计报告

- 审计日期：2026-10-20
- 审计范围：`app/src/main` 全部 Kotlin（46 个文件，约 7000 行）+ `cpp/capture.cpp` + `IPvz2Service.aidl` + 构建/清单配置 + `tools/`
- 验证方式：全量静态阅读 + `./gradlew testDebugUnitTest`（23 个用例全部通过）

---

## 结论速览

整体工程质量明显高于一般个人项目：**无网络权限、无硬编码密钥、无动态加载、组件收敛、资源释放路径完整、native 内存拷贝有边界校验**。

需要处理的问题集中在**一处真实安全弱点（跨进程帧文件）**、**两个资源/逻辑缺陷**，以及若干纵深防御加固项。按优先级：

| # | 级别 | 问题 | 位置 |
|---|---|---|---|
| 1 | 中（安全） | 帧文件固定路径 + `chmod 666`，全局可读可写，存在画面泄露/篡改/DoS 风险 | `Pvz2UserService.kt:455-493` |
| 2 | 中（功能） | `cleanup()` 中 `shellService` 永远为 null，停止时不释放虚拟屏 | `VirtualDisplaySession.kt:97,122,552` |
| 3 | 中（稳定） | `readFrameFromService` 两条早退路径泄漏 ParcelFileDescriptor | `VirtualDisplaySession.kt:184-198` |
| 4 | 低（防御） | AIDL 暴露以 shell/root 身份执行任意命令的 `exec()` | `IPvz2Service.aidl:35`、`Pvz2UserService.kt:111` |
| 5 | 低（防御） | `grabLogcat` 的 grep 单引号未转义，理论命令注入（当前不可达） | `Pvz2UserService.kt:149` |
| 6 | 低（防御） | `packageName` 拼进 shell 字符串（UI 层已有白名单拦截） | `VirtualDisplaySession.kt:249,342` |
| 7 | 低（稳定） | `ACTION_VD_STOP` 用 startForegroundService 但不调 startForeground | `AutomationService.kt:149-156` |
| 8 | 低（加固） | release 未开 minify；proguard 残留已删除类的 keep | `app/build.gradle.kts`、`proguard-rules.pro` |
| 9 | 低（防御） | native 采集单例无锁，并发调用理论上 use-after-free | `cpp/capture.cpp:46` |
| 10 | 极低 | Recognizer 屏幕变化时从已缩放模板二次缩放 | `Recognizer.kt:53-56` |

---

## 1. 帧文件固定路径 + chmod 666（中危，建议优先修）

**现状**：`acquireFrameFd()` 每帧把屏幕内容写死到 `/data/local/tmp/pvz2/frame.rgba`，并 `chmod 666` 让任意 uid 可读写。

```
/data/local/tmp/pvz2/            <- mkdirs() 默认 755，other 可进入
└── frame.rgba                   <- 666，任意 app 可读可写
```

`/data/local/tmp` 对 other 通常带可遍历位（`--x`），配合固定可预测的文件名，任意普通应用无需权限即可按路径访问该文件。MuMu 等模拟器（本项目主用环境）目录权限通常更宽松。

**风险**：
- **画面泄露**：任意 app 持续读取该文件即可拿到副屏实时画面。
- **帧中毒**：任意 app 写入伪造帧，干扰模板匹配/OCR 与点击决策。
- **拒绝服务**：把文件替换/扩写成超大文件，App 侧 `ByteArray(length.toInt())`（`VirtualDisplaySession.kt:189`）会按文件大小分配内存，直接 OOM。
- 固定文件名在特权进程（shell/root）下反复打开，也存在符号链接跟随的理论风险（当前目录权限下条目不可被其他 uid 替换，故未被证实）。

**修复建议**（按推荐顺序）：
1. 优先复用 `screencap()` 已验证的模式——经 `ParcelFileDescriptor` 管道流式回传，不落盘；
2. 或用 `SharedMemory` / `ashmem` 传 fd（AIDL 注释里原本就写的是 ashmem）；
3. 若必须落盘：`createTempFile()` 随机文件名 + 权限 600 + 用完即删，并通过 fd 而非固定路径交给 App。

## 2. cleanup() 不释放 shell 侧虚拟屏（中危，逻辑缺陷）

`start()` 中拿到的 service 只存为局部变量，从未赋值给成员 `shellService`（全文件仅有声明、读取、置 null，没有赋值点）。导致 `stop() → cleanup()` 里的：

```kotlin
runCatching { shellService?.releaseVirtualDisplay() }  // 永远是空操作
```

点"停止"不会释放虚拟屏与 native AImageReader。目前仅靠两层兜底回收：再次 `start()` 时 `createDisplayWithCapture()` 内部先 `releaseVirtualDisplay()`，以及 `onDestroy()` 里 `unbind(remove=true)` 触发远端 `destroy()`。一旦改为长驻/不销毁进程，就会累积幽灵屏直到系统拒绝建屏。

**修复**：`start()` 拿到 service 后补一句 `shellService = service`。

## 3. readFrameFromService 泄漏 fd（中危，稳定性）

`VirtualDisplaySession.kt:184-198`：

```kotlin
val pfd = service.acquireFrameFd() ?: return null
return try {
    val length = pfd.statSize
    if (length <= 0L) return null      // ← pfd 未关闭
    ...
} catch (t) { ... null }              // ← statSize 抛异常时 pfd 未关闭
```

虚拟屏黑帧期间 `length=0` 会持续命中，每轮（120ms）泄漏一个 fd，长运行会耗尽进程 fd 上限（1024），随后截图/取帧全部失败。

**修复**：把整段包进 `ParcelFileDescriptor.AutoCloseInputStream(pfd).use { }`，或在每条 return/catch 前 `pfd.close()`。

## 4. AIDL 通用 exec()（低危，纵深防御）

`exec(String command)` 以 shell(uid 2000)/root(uid 0) 身份执行任意命令。Shizuku UserService 的 binder 按调用方 uid 隔离，第三方应用无法绑定，**当前不构成跨应用漏洞**；但它是一个能力极强的"万能口"，App 内任何一条未来新增的入口（deep link、导出组件、脚本动作）触达它，就等于 shell/root 级命令执行。

**建议**：把 `exec` 收窄为具名白名单方法（如 `forceStop(pkg)`、`windowDump()`），业务层不接触原始字符串；确需保留也应限制为仅诊断构建可用。

## 5. grabLogcat 单引号注入（低危，当前不可达）

`Pvz2UserService.kt:149`：

```kotlin
"logcat -d -v brief -t 8000 | grep -E '$filter' | tail -n $take"
```

`$filter` 内的单引号未转义，传入 `'` 即可闭合单引号注入任意命令（shell/root 权限）。当前两个调用点（`MainActivity.kt:316/320`）均为硬编码常量，**不可达**，但作为 AIDL 接口是个埋着的注入点。

**修复**：对 filter 过滤/拒绝 `'`；更稳妥的是 logcat 与 grep 都用 `ProcessBuilder` 参数数组执行（管道改为进程串联），完全不经 `sh -c`。

## 6. 包名拼进 shell 字符串（低危，UI 已拦截）

- `VirtualDisplaySession.kt:342`：`am force-stop $packageName`
- `VirtualDisplaySession.kt:249-259`：awk 脚本中直接插入 `$packageName`

当前 UI 对自定义包名有白名单校验 `[A-Za-z0-9._]+`（`MainActivity.kt:222`），渠道列表为硬编码常量，shell 元字符进不来。但校验只在 UI 层，存储值被篡改或新增入口时 shell 侧无防御。

**修复**：`am force-stop` 改 `ProcessBuilder("am","force-stop",packageName)`；awk 用 `-v pkg="$packageName"` 传参，不插进脚本。

## 7. ACTION_VD_STOP 前台服务时序（低危，边缘崩溃）

该分支（`AutomationService.kt:149`）用 `startForegroundService` 拉起却不调 `startForegroundCompat()`。正常流程（先 VD_START 后 STOP）服务已在前台，不触发；但服务被系统回收后再收到该 action 时，Android 12+ 会因 5 秒内未进入前台抛 `ForegroundServiceDidNotStartInTimeException`。建议分支内补 `startForegroundCompat()`，或停止动作改用 `stopService`。

## 8. 发布加固（低危）

- `release { isMinifyEnabled = false }`：写好的 proguard keep 规则当前不生效，APK 可被直接反编译。自用无碍，分发前建议开启并回归 Shizuku/UserService 启动。
- `proguard-rules.pro` 仍 keep 已删除的 `com.pvz2.auto.fallback.Pvz2AccessibilityService`，属残留，可删。

## 9. native 单例无锁（低危）

`cpp/capture.cpp` 的 `g_capturer` 全局单例无互斥保护，`setup/release/acquire` 在 Binder 多线程并发时理论上存在 use-after-free。当前业务侧串行调用，未触发。建议加 `std::mutex`，或在 Java 层用同一把锁串行化所有 native 调用。

## 10. Recognizer 二次缩放（极低，画质）

`Recognizer.setScreen()` 屏幕变化时从**已缩放**的 `scaled` 模板重建，而非从 `originals`（对照 `TemplateMatcher.kt:97-116` 的正确做法），二次 resize 轻微损失精度。建议改为从 originals 重建。

---

## 已确认良好的实践

- **攻击面极小**：Manifest 未声明 `INTERNET`，全项目无网络代码、无硬编码 token/密钥/IP；`local.properties` 已被 gitignore，git 历史无敏感文件。
- **组件收敛**：`AutomationService` 与 `FileProvider` 均 `exported=false`；ShizukuProvider 以 `INTERACT_ACROSS_USERS_FULL` 保护；`allowBackup=false`；FileProvider 仅暴露 `logs/`。
- **注入面控制**：`tap/swipe` 参数全为 Int（Binder 类型保证）；`launchViaAm` 明确用参数数组 exec 并注释禁止 `sh -c` 拼接；脚本 ActionType 为白名单枚举（无 shell 动作），脚本/pipeline 全部从只读 assets 加载；条件里的"JS 表达式"尚无求值器，无代码执行面。
- **native 内存安全**：`capture.cpp` 对目标缓冲区容量、源帧 `dataLength`、rowStride 异常均有显式校验，错误路径都释放了 AImage。
- **资源管理**：OpenCV `Mat`、`Bitmap`、`MotionEvent`、截图管道 fd 在绝大多数路径有 `use/release/finally`；`PendingIntent` 用 `FLAG_IMMUTABLE`。
- **依赖可信**：Shizuku 13.1.5、OpenCV 4.13.0、ONNX Runtime 1.20.0 均为官方 Maven 坐标、版本固定，无动态代码加载。
- **工程纪律**：23 个单元测试全部通过；错误码/返回值有客观校验（如 startActivity 负码、窗口 displayId 反查）；注释与 HANDOFF 文档对边界和坑的记录非常充分。

## 验证情况

- 静态阅读：覆盖全部 46 个 Kotlin 文件、`capture.cpp`、AIDL、Manifest、Gradle、proguard、tools 脚本。
- 构建验证：`gradlew testDebugUnitTest` 通过——Pvz2ChannelsTest 8/8、ScriptJsonTest 7/7、VirtualDisplayFlagsTest 8/8，共 23 个用例，0 失败 0 错误。
- 未覆盖：未在真机/模拟器做动态验证（fd 泄漏、帧文件权限的实际可利用性建议在 MuMu 上用 `adb shell ls -lZ /data/local/tmp/pvz2/` 复核）。
