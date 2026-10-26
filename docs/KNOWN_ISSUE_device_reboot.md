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
