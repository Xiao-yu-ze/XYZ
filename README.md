# PVZ2 Auto

安卓上跑 PVZ2 的自动化框架。用 Shizuku 拿 shell 级权限，建一块虚拟屏把游戏搬上去，
在本机后台完成「看图 → 判断 → 操作」，不占用手机前台。

## 现在是怎么跑的

```
Shizuku (app_process, uid 2000)
  └─ 建虚拟屏 1280×720@240dpi（DisplayManager，FakeContext 过权限检查）
      └─ 输出面 = AImageReader(RGBA_8888)
          └─ 回调只置标志 → 工作线程 acquireLatestImage → 拷进共享内存环
              └─ 脚本引擎读环 → OpenCV 模板/找色/找字 → 决策 → 注入到虚拟屏
```

采集端与虚拟屏在**同一个进程**（UserService）。早先分成两个进程时，屏接一块 reader、
App 自己读另一块空的，预览永远是黑的。

## 关键点（踩过的坑，别再踩）

- **虚拟屏只有 60Hz**。`VirtualDisplayConfig.setRequestedRefreshRate()` 会被系统忽略，
  实测 `supportedModes` 只有 `fps=60.0`、`refreshRateOverride 0.0`。UI 上不做帧率选项。
- **预览不能复用同一张 Bitmap**。硬件加速按 `generationId` 缓存纹理，原地改像素不重传，
  画面会冻住，只有触摸触发的重绘才刷新。每帧新建，或改用 SurfaceView 自绘。
- **帧拷贝必须在 ImageReader 回调之外做**。回调里做 3.7MB memcpy 会让缓冲迟迟不归还，
  生产者被反压。
- **`limitArea` 是 `左 上 右 下`（空格分隔）**，不是 x,y,w,h。写错不报错，会静默退化成全屏。

## 脚本格式

跑的是自动精灵的 `.zjs`（NDJSON，CRLF）。第一行是 baseInfo，其余每行一个动作。
除了协议自带动作，另有一批 PVZ 专用节点：种植物 / 喂豆 / 铲除 / 点击格子 / 点下一波 /
切换编队 / 切换形态 / 使用神器 / 选补给 / 开始战斗 / 进关判定 / 识别结算 / 等待候选。

坐标集中在 `PvzCoords`，脚本里只写「卡槽 / 行列」，不写像素。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ZDJL_ARCHITECTURE.md](docs/ZDJL_ARCHITECTURE.md) | 脚本引擎架构 |
| [docs/zdjl-capture-and-roi.md](docs/zdjl-capture-and-roi.md) | 采集链路与 ROI |
| [docs/DEV_CONVENTIONS.md](docs/DEV_CONVENTIONS.md) | 开发约定 |
| [docs/FPS_ADAPTER.md](docs/FPS_ADAPTER.md) | 帧率相关的改动点 |
| [docs/HEARTTOWER2_DESIGN.md](docs/HEARTTOWER2_DESIGN.md) | 心塔 1:1 复刻设计 |
| [docs/YS_REPLICATION_SPEC.md](docs/YS_REPLICATION_SPEC.md) | YS 复刻规格 |
| [docs/KNOWN_ISSUE_device_reboot.md](docs/KNOWN_ISSUE_device_reboot.md) | 虚拟屏崩溃排查记录 |
| [tools/shizuku/README.md](tools/shizuku/README.md) | Shizuku 服务端启动与坑 |
| [tools/maa2zjs/README.md](tools/maa2zjs/README.md) | Maa 管线转 .zjs |

## 构建

```bash
export JAVA_HOME=<jdk17>
./gradlew :app:testDebugUnitTest :app:assembleDebug
```