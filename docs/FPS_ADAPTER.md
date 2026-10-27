# 帧率适配清单（改帧率/加档位时必看）

> 记录时间：2026-10-27。背景：用户选 60 帧后**画面仍是 30 帧**，
> 查了半天以为游戏限帧，最后发现是**预览刷新间隔写死 33ms**。
> 下面这 5 处任何一个没跟着改，就会出现"设置了但看不出效果"。

## 一处设置要联动 5 个地方

| # | 位置 | 作用 | 会不会漏 |
|---|---|---|---|
| 1 | `core/GameFps.REQUESTED_REFRESH_RATE` | 虚拟屏刷新率 → **决定游戏出帧** | 单一真源 |
| 2 | `core/GameFps.CAPTURE_FPS` | 采集节流间隔 `1000/fps` | 已改成跟随 ① |
| 3 | `service/VirtualDisplaySession.PREVIEW_INTERVAL_MS` | **预览**刷新间隔 | ★ 曾经写死 33ms=30fps，就是它导致"看着还是 30 帧" |
| 4 | `service/VirtualDisplaySession` 建屏前 `setPendingRefreshRate()` | 把值交给特权侧 | 特权侧 `createDisplayWithCapture` 内部会用 `pendingRefreshRate` |
| 5 | 第 1 页 `spFps` 下拉（`MainActivity.bindFpsSpinner`） | 用户入口 | 仿照「渠道服」Spinner，**不是长按** |

## 120 档位不要加

`GameFps.kt` 里有一条实测记录：虚拟屏 120Hz 时取帧路径每帧一次同步 Binder +
4 次 3.7MB 拷贝 ≈ **1.8 GB/s**，把 system_server 饿死（`wd_restart`）。
所以只提供 30 / 60。

## 验证方法（改完必须走一遍）

```bash
# 1) 虚拟屏实际刷新率（看 renderFrameRate / refreshRateOverride，不是 mRequestedRefreshRate）
adb shell dumpsys display | grep -A2 "pvz2-virtual" | grep -E "renderFrameRate|refreshRateOverride"

# 2) 游戏真实出帧（战斗进行中，两次采样差 / 秒数）
adb shell dumpsys gfxinfo com.popcap.pvz2cthdop | grep "Total frames rendered"

# 3) 日志
adb logcat -d | grep -E "请求虚拟屏刷新率|事件驱动采集已就绪"
```

注意：`dumpsys display` 里第一条 `mRequestedRefreshRate=0.0` 是**主屏 display 0** 的，
不要拿它当虚拟屏的结论（我踩过这个坑，误判成"没生效"）。

## 改完要重建虚拟屏

虚拟屏是常驻的（特权侧 `com.pvz2.auto:pvz2` 进程持有）。改帧率后**必须**
「停止并释放」再「启动（Shizuku）」—— 直接强杀 App 不会释放虚拟屏，看到的还是旧值。
