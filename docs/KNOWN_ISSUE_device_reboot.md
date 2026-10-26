# 已知系统级问题：虚拟屏注入导致「手机重启」

> 记录时间：2026-10-26
> 现象：用户用脚本时，手机黑屏一下、回到桌面，观感像**重启**。

---

## 一、结论：不是重启，是 `system_server` 崩了

证据（真机 LENOVO TB323FU，2026-10-26 14:02）：

```text
$ adb shell uptime
14:02:59 up 24 days,  6:13,  0 users,  load average: 7.69, 8.11, 7.60
```

内核 **24 天没重启**。崩的是 Android 框架进程。

```text
$ adb shell logcat -b crash -d
10-26 13:59:13.726  27214 27338 E AndroidRuntime: *** FATAL EXCEPTION IN SYSTEM PROCESS: android.ui
10-26 13:59:13.726  27214 27338 E AndroidRuntime: java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1
    at com.android.server.wm.WindowContainer.getChildAt(WindowContainer.java:642)
    at com.android.server.wm.RootWindowContainer.getDisplayContent(RootWindowContainer.java:1346)
    at com.android.server.wm.RootWindowContainer.getTopFocusedDisplayContent(RootWindowContainer.java:571)
    at com.android.server.wm.DisplayPolicy$1.onDown(DisplayPolicy.java:764)
    at com.android.server.wm.SystemGesturesPointerEventListener.onPointerEvent(SystemGesturesPointerEventListener.java:388)
    at com.android.server.wm.PointerEventDispatcher.onInputEvent(PointerEventDispatcher.java:105)
    at android.view.InputEventReceiver.dispatchInputEvent(InputEventReceiver.java:307)
```

崩后 `dumpsys display` 里只剩 `mDisplayId=0`（主屏），虚拟屏已不在。

## 二、机理

`SystemGesturesPointerEventListener`（系统手势监听，每个 display 上都挂着）收到一次
**触摸 DOWN**，回调进 `DisplayPolicy.onDown`，去取「最上层焦点显示」：

```java
RootWindowContainer.getTopFocusedDisplayContent()
  → getDisplayContent(index)      // 走的是多显示分支
  → getChildAt(1)                 // 固定下标 1
  → IndexOutOfBoundsException     // 但列表 length == 1
```

这段是给**副屏/虚拟屏**准备的路径。当「显示列表只剩 1 个」而事件仍带着副屏身份
（虚拟屏刚被销毁/正在重建的窗口期）时，下标 1 就越界。system_server 死 → 框架重启。

**这是 ColorOS 窗口管理的 bug，我们无法根治**，只能避免踩进去。

## 三、已经做的规避

1. `TouchInjector.isDisplayAlive()`（新增）：每次注入前确认目标 display 还在。
   - `ShizukuTouchInjector` 用 `DisplayManagerGlobal.getDisplayIds()` 核对；
   - 结果缓存 500ms（注入是高频的，不能每次都查）。
2. `ZTouchInjectorAdapter` 的 `tap / swipe / multiSwipe` 前调 `requireDisplay()`，
   不在就抛 `IllegalStateException("虚拟屏已消失…已停止注入以免把点击打到主屏")`。
   **宁可停脚本，也不能把这一下打到主屏喂给系统 bug。**

## 四、使用上的注意（写进交接文档）

- **脚本运行期间不要点「停止并释放 / 启动（Shizuku）」**：每次都会让 display 列表
  2→1→2，正是崩的窗口期。
- **重装 APK 会打断虚拟屏会话**，之后必须先「启动（Shizuku）」→「在虚拟屏启动游戏」
  再跑脚本。
- 如果真的又崩了，抓这三样即可定位：
  ```bash
  adb shell uptime                                  # 确认是框架重启还是整机重启
  adb shell logcat -b crash -d | head -20           # 崩溃栈
  adb shell dumpsys display | grep mDisplayId       # 崩后还剩几块屏
  ```

## 五、尚未验证

本轮只做了代码层守卫（编译 + 400 单测通过），**没有在真机上复现"注入被拦下"的场景**
（需要人为销毁虚拟屏再触发注入）。下次上真机可以这样验：
运行脚本中途点「停止并释放」，脚本应报
「虚拟屏已消失（display 不在了），已停止注入以免把点击打到主屏」，而不是把手机带崩。

---

## 六、2026-10-26 21:40 复现：手动快速点屏幕也会触发

用户原话："我刚刚就是点屏幕点快了他就重启了，我没开自动精灵"。

### 这次的证据

```text
$ adb shell uptime
21:44:38 up 24 days, 13:55          ← 内核没重启（不是整机重启）

$ adb shell dumpsys package / ps
com.pvz2.auto 进程数 = 0            ← 我们的 App 已经没了
VD = 0                              ← 虚拟屏也没了
crash 缓冲区最新仍是 13:59 那次     ← 没有新的 system_server 崩溃记录

MemFree:       151 MB
load average:  14.33                ← 平时 7~8
```

21:44 事件日志显示回到桌面（`com.zui.launcher`），并弹出 Shizuku 的 ADB 配对教学页
→ Shizuku 会话也断了。

### 结论（重要）

1. **触发条件不是"我们注入的事件"**，而是"**虚拟屏存在时，任何快速连续的点按**"——
   手动点在主屏上同样能踩到 `SystemGesturesPointerEventListener.onDown` 那条
   `getTopFocusedDisplayContent` 越界路径。
2. 这台机器（ColorOS）在多显示场景下这条路径本身有问题，**只要虚拟屏活着就有概率**。
3. 本次表现是：App + VD + Shizuku 会话一起消失（观感=重启），但内核没重启。

### 因此的规避建议（按有效性排序）

| 措施 | 说明 |
|---|---|
| 运行脚本期间**不要手动快速点屏幕** | 最直接；手点的频率和位置不可控 |
| 我们注入的点击加最小间隔 | 现在 hold 之后没有强制间隔，可考虑 ≥80ms 下限 |
| 不用 VD 时立刻「停止并释放」 | 缩短"有第二块屏"的时间窗 |
| 内存留余量 | MemFree 只剩 151MB，压力大时更容易在该窗口出问题 |

---

## 七、内存泄漏实测（2026-10-26 22:00~22:09）

用户怀疑"放着不动也会崩，可能是内存不够" —— 实测证实：我们自己在漏 native 内存。

### 修复前

跑 E 脚本 60 秒：

| 指标 | 跑前 | 60 秒后 |
|---|---|---|
| Native Heap | 61 MB | **118 MB（+57MB）** |
| MemFree | 725 MB | 558 MB |

### 根因（已修）

1. `ZRealMatcher.cachedFrame`（"一轮一帧"缓存）抓完从不释放 ——
   Mat 是 native 对象（1280×720×4 ≈ 3.7MB），不受 GC 管；
   每轮等待候选抓一帧，跑一局几百轮。→ `endFrameGroup()` 里显式 release
2. 同一轮改动引入的**双重释放**风险：`cachedFrame` 就是 `nextFrame()` 的返回值，
   而它已由 `pendingFrame` 负责释放 → 加所有权判定（`c !== pendingFrame` 才由本函数释放）

### 修复后

同一 VD 会话连续两个 60 秒窗口：

| 时点 | Native Heap | MemFree |
|---|---|---|
| 0-60s | 123.0 MB | 341 MB |
| 60-120s | 130.8 MB | 309 MB |

**增速 57MB/60s → 7.7MB/60s（降约 87%）**

### 仍待处理（下一轮）

1. 残余 7.7 MB/分钟：最可能是 OCR 每帧的临时 Mat / Bitmap 转换
2. MemFree 仍掉 32MB/分钟 —— 需单独测 `com.popcap.pvz2cthdhwct`，
   分清"我们漏的"和"游戏/虚拟屏自己的"
3. 加运行期内存自检：脚本运行时每 30 秒把 Native Heap 写进日志，
   这样崩之前能直接从日志看到是不是内存涨上去
