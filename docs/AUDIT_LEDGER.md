# 代码审计台账（4 份独立审计汇总）

审计日期：2026-10-20
审计方式：4 个独立智能体分别负责并发/热路径/坐标/平台，互不通信
处理原则：每条都对照代码核实，**不盲信**；标注"已确认/已推翻/待验证"

---

## 一、最重大的认知纠正（推翻我此前的结论）

### 1.1 识别根本没用虚拟屏的帧 ⚠️ 已确认

项目里有**两条完全独立的采集路径**：

| | 路径 | 用途 | 每帧代价 |
|---|---|---|---|
| A | `AImageReader → 文件 → Mat` | **只喂预览** | 60fps，但只到 `previewFlow` → ImageView |
| B | `screencap -p → PNG → 管道 → 解码 → Mat` | **识别唯一通路** | fork 2 进程 + PNG 编解码，**40–90ms** |

证据：`NodeGraph.refreshFrame()` → `ShizukuScreenCapturer.capture()`；
`Recognizer` 从未收到过 `VirtualDisplaySession` 的帧。

**所以「60fps 已达成」只对预览成立。Boss 打断走的是路径 B，与 60fps 无关。**

这是我此前最大的盲区：我优化了半天预览帧率，而真正决定"能不能打断"的那条路完全没碰。

### 1.2 `mask + method=5` 不会抛异常 —— 已推翻

热路径审计推测 OpenCV 4.13 会拒绝 `TM_CCOEFF_NORMED + mask`。
坐标审计从 `_toolchain/opencv_aar/jni/x86_64/libopencv_java4.so` 挖出断言字符串：
只要求 `_templ.size() == _mask.size()`、depth 匹配，**没有 method 限制**。

→ 该条**不成立**，不修。

### 1.3 `green_mask` 是死路径 —— 已确认

扫描 assets 下 800 张模板：`green_mask: true` 引用的两个模板
（`Endless/frame/boss/僵王的头.png`、`功夫僵王.png`）**零纯绿像素**。
全库仅 10 张含纯绿，且都没被引用。

→ GreenMask 的 scale 问题存在但**当前不触发**，优先级下调。

---

## 二、确定 BUG（必须修）

### P0-1 native 采集器无锁 → SIGSEGV

`app/src/main/cpp/capture.cpp` — `g_capturer` 是无锁全局指针。
`nativeRelease`（Binder 线程 A）与 `nativeAcquireFrame`（线程 B，60fps）并发时
`AImageReader_delete` 与 `acquireLatestImage` 竞争 → **use-after-free**。

表现：shell 进程直接 SIGSEGV 消失 → 屏跟着没 → 日志戛然而止。
高频取帧时几乎必现。

**状态：待修**

### P0-2 cancel 不 join 就释放复用缓冲 → UAF

`VirtualDisplaySession.kt` — `frameJob.cancel()` 后立刻 `cleanup()` → `releaseFrameBuffers()`。
帧循环的不可取消段（Binder + 3.7MB 读 + cvtColor，15–40ms）与释放窗口必然重叠。
作用在已 release 的 Mat 上 → CvException 或 native memcpy 崩溃。

**这是我上一轮把缓冲改成复用后引入的**：之前每帧新分配，release 旧的顶多浪费；现在共享，就是 UAF。

**状态：待修**

### P0-3 `update()` 非原子 read-modify-write

`VirtualDisplaySession.kt` — `transform(_state.value)` 然后赋值。
帧循环 / start / stop 分属不同线程 → `running` 可能被帧循环覆盖回 true，
`appOnDisplay` 可能被 fps 推进覆盖。

**状态：待修**

### P0-4 shell 侧每帧 `allocateDirect(3.7MB)`

`Pvz2UserService.kt:473` — 60fps = **221 MB/s** 堆外分配。
DirectByteBuffer 只由 Cleaner 回收 → GC 抖动、可能 OOM。

我上一轮只改了 App 侧读取缓冲复用，**漏了对称的写入侧**。

**状态：待修**

### P0-5 `pfd` 泄漏

`VirtualDisplaySession.kt:246-252` — `statSize <= 0` 与异常路径 `return null` 时不关 pfd。
黑帧期每 120ms 漏 1 个 fd，长跑耗尽 1024 上限。

**状态：待修**

### P0-6 `Hit.box` 只有 Template 填了

`Recognizer.kt` — OCR/Color 返回空 Rect。
`target_offset` 是"在 box 上叠加偏移"，空 box 会算出屏外坐标
（`无尽挑战_选取神器` 的 `target_offset:[12,69,-58,-21]` → cx=−17）。

**状态：待修**

### P0-7 `capture.cpp` 泄漏 ANativeWindow

`AImageReader_getWindow` 拿到的窗口从未 `ANativeWindow_release()`。

**状态：待修**

### P0-8 `releaseVirtualDisplay` 提前 return 导致采集端泄漏

`Pvz2UserService.kt:540-556` — `if (display == null) return` 把
`NativeCapture.release()` 也挡住了。建屏失败时 reader + Surface 全泄漏。

**状态：待修**

---

## 三、确定 BUG（平台契约）

### P1-1 `DisplayImePolicy` 反射缺 `isAccessible`

`DisplayImePolicy.kt:52-53,77-78` — 全仓唯一一处漏了。
`IWindowManager$Stub$Proxy` 是 private static class → `IllegalAccessException`
→ 被 `runCatching` 吞成"不致命" → **IME policy 永远设不上**。
这很可能就是"no touchable window"的真因之一。

**状态：待修**

### P1-2 `OWN_DISPLAY_GROUP` 与 `DEVICE_DISPLAY_GROUP` 同时置位

`VirtualDisplayFlags.kt:119-123` — 两者是互斥策略，至少一位被忽略。
最坏情况虚拟屏被并入默认显示组 → 窗口落回主屏。
**这可能就是"启动闪主屏"的原因。**

**状态：待修**

### P1-3 `launch` 把 2/3 当成功

`DisplayAppLauncher.kt:238-243` — 只把 `< 0` 当失败。
`START_TASK_TO_FRONT(2)` / `START_DELIVERED_TO_TOP(3)` 表示**复用了已有任务**，
此时 `launchDisplayId` 被忽略 —— 正是"游戏跑到主屏"的形态，却返回 true。

**状态：待修**

### P1-4 `launchViaAm` 无条件 return true

`DisplayAppLauncher.kt:265-279` — `am start` 打印 `Error:` 也是 true。

**状态：待修**

### P1-5 `describeDisplay` 不按 displayId 定位

`Pvz2UserService.kt:174-201` — 只按名字取第一个 `DisplayDeviceInfo`，
把入参 displayId 原样回显。有幽灵屏时会读到别人的 flags。

**状态：待修**

### P1-6 `SystemThreadWorkarounds` 递归 SOE

`SystemThreadWorkarounds.kt:144-154` — `install() → fillAppContext() → FakeContext.get()
→ ShellContext.get() → create() → apply() → activityThread 仍 null → 再进 install()`，
递归到 StackOverflowError 被 `runCatching` 吞掉才收敛。

**每次首次装配都吃一次 SOE**，落点不确定。

**状态：待修**

### P1-7 假 main Looper（线程已死）

`Pvz2UserService.kt:274-291` — 工作线程 `Looper.prepare()` 后直接退出、从不 `loop()`，
却把 `sMainLooper` 指向这个死线程。
任何 `Handler(mainLooper).post{}` **永不执行**（display listener、VirtualDisplay.Callback 等）。

**状态：待修**

### P1-8 `ACTION_VD_STOP` 已修但 `intent == null` 分支未修

`AutomationService.kt` — `START_STICKY` + 系统重启时 `intent == null` → 不匹配任何分支
→ **不调 startForeground** → 以普通后台服务复活 → 再被清理。

**状态：待修**

---

## 四、性能天花板（用户要"1ms 动作/极快识别"）

### P2-1 `tap` 用 `Thread.sleep` 占 Binder 线程 ⚠️ 架构级

`DisplayInputInjector.kt:41-57` — `Thread.sleep(holdMs)`，`holdMs` 默认 60ms。
AIDL 同步调用 → **一次点击占一个 Binder 线程 60ms**。点满 16 个 → 采集拿不到线程。

→ 单次打断下限 = 60(hold) + 5~15(DOWN) + 5~15(UP) ≈ **75–95ms**，与识别快慢无关。

### P2-2 每帧 5 次整帧拷贝

`native → direct Buffer → ByteArray → 文件 → ByteArray → Mat → cvtColor`
单帧搬运 ≈ 24.9 MB，60fps 下 ≈ 1.5 GB/s。

### P2-3 预览双重颜色转换

`RGBA→BGR`（每帧）然后 `BGR→RGBA`（预览 10Hz）—— **转过去又转回来**。
而且 BGR 那次转换在预览循环里做，**识别根本不用这条通路的帧**。

### P2-4 `NodeGraph` 默认 `rate_limit: 1000ms`

识别每秒只跑 1 次 —— "识别不够快"的第二号原因。

### P2-5 OCR 逐像素 JNI

`OcrEngine.kt:163-170` — 每个 box 逐行 `mask.put()`，全屏时 **544 次 JNI**。
`MatToTensor.nchw` 是纯 Kotlin 逐像素循环，全屏 8–25ms。

---

## 五、100→1080p 移植问题（720p 恰好掩盖）

| # | 位置 | 问题 |
|---|---|---|
| P3-1 | `ScreenAdapter.kt:83` | `scale = screenHeight/720`，但 Maa 语义是**短边**。竖屏截图会得到 2.667 而非 1.5 |
| P3-2 | `Recognizer.kt:243-244` | `count` 是物理像素阈值，ROI 面积随 scale² 变化 |
| P3-3 | `Recognizer.kt:232-242` | `method != 40` 分支把 4 通道 BGRA 交给 `inRange`，Scalar 只给 3 分量 → alpha 当第 4 通道比 → 掩码恒 0 |
| P3-4 | `Recognizer.kt:316-321` | 放大也用 `INTER_AREA` —— OpenCV 文档明确它只适合缩小，放大等价 `INTER_NEAREST` |
| P3-5 | `OcrEngine.kt:197-199,225,349-353` | 膨胀核 9×3、`MIN_BOX`、`y0/10` 分桶全是物理像素写死 |
| P3-6 | `Recognizer.kt:286` | `val current = adapter ?: return null` **遮蔽了同名参数** |
| P3-7 | `VirtualDisplaySession` vs `Recognizer` | VD 帧是 `CV_8UC3`，识别要 `CV_8UC4` —— **接进去会 `type == _templ.type()` 断言炸** |

---

## 六、已推翻 / 不修

| 结论 | 理由 |
|---|---|
| OpenCV 拒绝 `mask + method=5` | 实测断言字符串无此限制 |
| `green_mask` 导致 boss 永不命中 | 引用的模板零纯绿像素，那条路是死的 |
| `FakeContext.checkCallingPermission` 能骗过系统权限 | 该覆写只影响**本进程内**自检；system_server 用真实 uid 判定。真正有用的只有 `getPackageName` 系列 |
| `/data/local/tmp` App 可读 | SELinux `shell_data_file`，untrusted_app 读不到。**现在能跑是因为 open 在 shell 侧**，fd 过 Binder 不受路径检查 |

---

## 七、修复顺序

**第一批（会崩 / 会静默失败）** — ✅ 已完成（`60e8583`）
1. ✅ native 采集器加锁
2. ✅ cancelAndJoin
3. ✅ pfd 关闭
4. ✅ shell 侧缓冲复用
5. ✅ `Hit.box` 填充
6. ✅ `ANativeWindow_release`
7. ✅ `releaseVirtualDisplay` 提前 return

**第二批（用户要的"快"）** — 部分完成（`96e411f`）
8. ✅ 识别改接虚拟屏帧（干掉 screencap/PNG/fork）
9. ✅ `tap` 去掉 `Thread.sleep`
10. ✅ `rate_limit` 1000ms → 16ms
11. ✅ 预览去掉双重转换

**第三批（平台契约）**
12. ✅ `isAccessible`
13. ✅ display group 互斥
14. ✅ launch 返回码
15. ⬜ Looper 装配（递归 SOE / 假 main Looper）

**第四批（1080p 移植）**
16. ⬜ `scale` 短边
17. ⬜ 其余 P3

---

## 八、真机回归：虚拟屏建不起来（已修）

**现象**：点启动 → `DeadObjectException`，UserService 进程崩溃。

**日志证据**：
```
I/Pvz2Capture: capturer ready: 1280x720, surface=0x7878dbe001
I/Pvz2Capture: capturer released          ← 刚建好就被释放
F/DEBUG: #06 ... createVirtualDisplay+38
F/DEBUG: #08 ... createDisplayWithCapture+72
```

**根因**：`createDisplayWithCapture` 先 `NativeCapture.setup()` 拿到 surface，
再调 `createVirtualDisplay()`，而后者第一行是 `releaseVirtualDisplay()`
——把刚建好的采集端连同 surface 一起释放，随后用已销毁的 Surface 建屏。

**这是"把 `NativeCapture.release()` 移出提前 return"引入的回归**：
审计说的是"拆成 releaseDisplay() 与 releaseCapturer()"，我只做了后半句。

**修复**：拆成三个函数，`createVirtualDisplay` 内部只调 `releaseDisplay()`。

---

## 九、通道顺序：一个藏了很久的错误

**结论（审计实测确认）**：

| 数据源 | CV 类型 | 通道顺序 |
|---|---|---|
| `Bitmap.Config.ARGB_8888` 内存布局 | — | **R,G,B,A** |
| `Utils.bitmapToMat` 输出（模板） | CV_8UC4 | **R,G,B,A**（不交换） |
| `AIMAGE_FORMAT_RGBA_8888` + 逐行 memcpy | CV_8UC4 | **R,G,B,A**（不交换） |

两边一致 —— 所以**删掉 `cvtColor(RGBA2BGR)` 是对的**。

**但下游三处一直按 BGR 解释**，其中两处会静默出错：

| 位置 | 原来 | 后果 | 改为 |
|---|---|---|---|
| `Recognizer.matchColor` HSV 分支 | `COLOR_BGR2HSV` | **红蓝判反**，红色算成蓝色色相 | `COLOR_RGB2HSV` |
| `Recognizer.matchColor` 非 HSV 分支 | 4 通道直接 `inRange` | alpha 当第 4 通道比 [0,0] → **掩码恒空，永不命中** | 先 `COLOR_RGBA2BGR` 降通道 |
| `OcrEngine` 两处 | `COLOR_BGRA2RGB`（=交换） | PP-OCR 收到红蓝反转的图，**识别率静默下降** | `COLOR_RGBA2RGB` |
| `Recognition.GreenMask` | `COLOR_BGRA2BGR`（=不交换） | 变量名叫 bgr 实际是 RGB，**巧合正确**（纯绿 R/B 对称） | `COLOR_RGBA2BGR` |

最后一条特别值得记：它"没炸"不是因为写对了，是因为找的颜色恰好对称。

---

## 十、待办（未修）

| 项 | 说明 |
|---|---|
| Looper 装配 | `SystemThreadWorkarounds` 递归到 StackOverflowError 才收敛；工作线程 `Looper.prepare()` 后不 `loop()`，`sMainLooper` 指向死线程 → 所有主线程回调永不执行 |
| `scale` 用短边 | `ScreenAdapter` 现在用 `screenHeight/720`，竖屏截图会算出 2.667 而非 1.5 |
| OCR 后处理常量 | 膨胀核 9×3、`MIN_BOX`、`y0/10` 分桶全是物理像素写死，1080p 会把一行切成多框 |
| `AImageReader` 监听器 | 现在是轮询，事件驱动可做到空闲 0% CPU |
| 帧传输换 `MemoryFile` | 现在每帧写 3.7MB 到 `/data/local/tmp`（真实 flash），60fps = 221 MB/s |
| ~~副屏注入真机验证~~ | ✅ **已完成**（真机四路对比 + scrcpy 对照，见第十一节） |

---

## 十一、副屏注入：为什么修了六轮（复盘）

### 症状
虚拟屏上游戏画面正常，但点击完全无反应。

### 六轮错误排查（全是我在猜）

| 轮次 | 我的猜测 | 结果 |
|---|---|---|
| 1 | 缺 `INJECT_EVENTS` 权限 | ✗ 权限其实是 granted |
| 2 | 事件没设 `source` | 半对：方式错了（应构造时传） |
| 3 | `displayId` 传成了 0 | 对，但不是唯一问题 |
| 4 | 屏不 trusted | ✗ 日志显示 trusted=true |
| 5 | 窗口不在那块屏上 | ✗ 窗口分布正确 |
| 6 | 换了 shell 路径能通 → 但慢 | 能用，只是 fork 开销大 |

### 真正定位方式：一次四路对比

四条路同时打，失败方式的**组合**才可判读：

```
A  injectInputEvent + display 65 : ✗ 被拒
B  injectInputEvent + display 0  : ✗ 被拒   ← 连主屏都拒 = 实现问题，不是屏/权限
C  input -d 65 tap               : ✓ 接受
D  input tap                     : ✓ 接受
```

### 根因：与 scrcpy 的三处差异

读 `scrcpy/server/.../wrappers/InputManager.java`、`device/Device.java`、
`control/Controller.java` 后确认：

| 差异 | scrcpy | 我之前 |
|---|---|---|
| 取 InputManager | `context.getSystemService(INPUT_SERVICE)` | 反射 `InputManager.getInstance()` |
| 构造 MotionEvent | 14 参数 `obtain(..., source, flags)` | 6 参数 + 事后 `event.source =` |
| 指针类型 | `PointerProperties.toolType = TOOL_TYPE_FINGER` | **未设，默认 TOOL_TYPE_UNKNOWN** |

三处**任何一处**都足以让 `injectInputEvent` 返回 false。我每轮只猜中一处。

### 教训
> **遇到"某条路径永远失败"，第一动作是找一个跑通的同类项目逐行对照，
> 不是自己假设原因。**

已写入 `docs/DEV_CONVENTIONS.md`。
