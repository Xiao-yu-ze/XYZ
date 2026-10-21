# PVZ2 自动化 · 后续实现计划

> 状态基准：2026-10-21，commit `3c71a13`
> 本文只写**已核实**的事实和**可验收**的目标，不写"预计""大概"。

---

## 一、当前真实状态

### 已经能跑通的（真机验证过）

| 能力 | 验证方式 | 结果 |
|---|---|---|
| 建虚拟屏 | 真机 | `displayId=62/63/65`，`trusted=true`，`autoMirror=false` |
| 游戏搬到虚拟屏 | 真机 | 任务轨迹显示在副屏，加载到登录界面 |
| 采集画面 | 真机 | 60 fps，预览正常显示 |
| **触摸注入到虚拟屏** | 真机四路对比 | 直注 `injectInputEvent` ✓ 接受 |
| 模板匹配 | 真机 | 开始游戏 0.974，孤立度 0.665 |
| OCR / 找色 / Or | 单元测试 | 47/47 通过 |
| Pipeline 解析 | 单元测试 | NodeDef / PipelineParser 可解析 自动精灵 格式 |

### 关键缺口（这是计划的主体）

| 缺口 | 证据 |
|---|---|
| **脚本引擎没有入口** | `PipelineRunner` 只在自身声明处出现，`NodeGraph` 只被 `PipelineRunner` 构造。整条链是死代码，**任何脚本都跑不起来** |
| **识别慢** | 全屏搜 185×73 模板 = 154 ms；自动精灵自己的 pipeline **12/12 个模板节点都带 roi**，说明它也不全屏搜 |
| **OCR 是真正的热路径** | 真实 pipeline 里 OCR 出现 **68 次**，TemplateMatch 只有 **12 次**。优化重点应该是 OCR 不是模板匹配 |
| **动作耗时未测** | 直注无 fork 后应该 ~1 ms，但**没有实测数字** |
| 帧传输写 flash | 每帧 3.7 MB 写 `/data/local/tmp`，60 fps = 221 MB/s |

---

## 二、阶段划分

### 阶段 0：把引擎接上（最高优先级）—— ✅ 已完成（commit 52875e4 / 3d11e8d）

已完成：
- `ScriptActivity` 选脚本 → `AutomationService.scriptRun` → `PipelineRunner.run(dir, entry)`
- `ScriptRunState`（进程级 StateFlow）暴露：正在等哪个节点、第几轮、上轮耗时、各节点命中次数
- 入口推断照 自动精灵 的方式（选不被任何节点引用的），结果显示在可编辑输入框里
- UI 按功能拆页：Main（预览+导航）/ `ScriptActivity` / `DiagnosticsActivity`
- 修掉 `CancellationException` 被 `catch (Throwable)` 吞掉导致"点停止报出错"

下一步：真机验证。

**为什么最先做**：现在没有任何脚本能跑。做完之前，后面所有优化都没有验证载体。

| 任务 | 产出 |
|---|---|
| 给 `PipelineRunner` 一个真实入口 | App 内「运行脚本」按钮 → `AutomationService` → `PipelineRunner.run(entry)` |
| 脚本加载 | 从 assets 读 pipeline JSON，支持选文件 |
| 运行时状态可见 | 当前节点名、命中次数、每轮耗时显示在界面上 |
| 停止 | 可中断，不泄漏帧和注入器 |

**验收**：在虚拟屏上跑通 `01_Endless_plant_ref.json` 的前 3 个节点，界面能看到节点推进。

---

### 阶段 1：性能（识别 + 动作）

**验收标准（可量化）**：
- 单节点识别 **< 5 ms**（有 roi 的模板匹配）
- 单次点击注入 **< 2 ms**
- 连续动作间隔可到 **0 ms**（不含动作本身的硬件下限）

| 任务 | 做法 | 依据 |
|---|---|---|
| **ROI 强制化** | 识别节点没有 roi 时打警告；测量每个节点实际耗时并显示 | 自动精灵 12/12 节点带 roi |
| **OCR 优化** | 按 roi 裁剪后再送 OCR；检测输入上限 960 已存在，确认 roi 小时是否走更小输入 | OCR 是热路径（68 次） |
| **动作耗时实测** | 从"决定点击"到"注入返回"打点，报 P50/P95 | 现在只有猜测 |
| **帧传输换 MemoryFile** | 用 `MemoryFile`/`SharedMemory` 替掉 `/data/local/tmp` 文件 | 消除 221 MB/s flash 写 |
| **AImageReader 事件驱动** | 监听器替代轮询，空闲时 0% CPU | 台账未完成项 |

**硬件下限（必须如实告知）**：`vsync 16.7ms + InputDispatcher ≈ 33ms` 是端到端物理下限。
能压到 1 ms 的是「帧可用 → 注入调用发出」，不是「按下 → 游戏响应」。

---

### 阶段 2：脚本引擎补齐（自动精灵超集）

真实 pipeline 里实际用到的能力（已统计）：

| 类别 | 实际使用 |
|---|---|
| 动作 | `Click` ×56、`Custom` ×28、`DoNothing` ×4、`Swipe` ×4 |
| 识别 | `OCR` ×68、`TemplateMatch` ×12、`Or` ×2、`ColorMatch` ×1、`DirectHit` ×1 |
| 控制流 | `next` ×15、`pre_delay` ×6、`post_delay` ×6、`enabled` ×6、`timeout` ×3、`rate_limit` ×1、`max_hit` ×1 |

| 任务 | 说明 |
|---|---|
| 补全动作集 | `Click`（含 `target` 偏移）、`Swipe`、`Custom`（带参数）、`DoNothing`、`StopTask`、`Wait` |
| 补全识别集 | 已有 OCR/Template/Color/Or/DirectHit，补 `inverse`（反选）与 `AnyOf` 语义确认 |
| 控制流 | `next` 列表、`on_error`、`interrupt`、`is_sub`、`max_hit` 上限 |
| **并行分支 / 汇合** | **我们的差异化能力**，自动精灵没有。设计见下 |

#### 并行分支设计（待确认语义）

自动精灵是单链 `next` 列表顺序尝试。我们要加的是：

```json
{
  "name": "监控僵尸",
  "recognition": {"type": "TemplateMatch", "param": {"template": ["zombie.png"]}},
  "action": {"type": "Click", "param": {"target": "hit"}},
  "parallel": {
    "branch": ["清屏", "保命"],
    "join": "any"
  }
}
```

- `branch`：同时跑多个子链
- `join`：`any`（任一完成即继续）/ `all`（全部完成）
- **需要你确认**：分支之间共享帧还是各自取帧？共享意味着一个分支的点击会影响另一个分支下一帧看到的画面。

---

### 阶段 3：健壮性（台账遗留）

| 任务 | 风险 |
|---|---|
| Looper 装配递归到 SOE | 每次首次装配吃一次 StackOverflowError，能工作但是隐患 |
| `scale` 用短边 | 竖屏 / 分屏时会算错缩放；当前 1280×720 横屏不触发 |
| OCR 后处理常量 | 膨胀核 9×3、`MIN_BOX`、`y0/10` 分桶按物理像素写死，1080p 会把一行切成多框 |

**这三项当前都不触发**（我们跑 1280×720 横屏）。放在阶段 3，因为它们是"换分辨率才炸"的雷，不是现在的阻塞。

---

## 三、不做的事（以及为什么）

| 不做 | 理由 |
|---|---|
| 抄自动精灵的神经网络方案 | 63.5 MB 推理引擎 + 需要训练数据；我们场景是**固定游戏 + 800 张已知素材**，模板匹配够用 |
| 换 ML Kit OCR | 我们用 PaddleOCR + ONNX，对中文够用；换引擎的收益不抵迁移成本 |
| 改注入架构 | 已和商业实现同构（独立可执行 / `app_process`），且真机验证通过 |

---

## 四、需要你确认的三件事

1. **阶段顺序**：是否同意「先把引擎接上（阶段 0）」优先于性能优化？
   现在没有入口，优化无法验证。

2. **并行分支语义**：分支共享帧还是各自取帧？（影响点击时序）

3. **脚本来源**：先只支持导入 自动精灵 的 JSON（改都不用改就能跑），
   还是要做可视化节点编辑？后者工作量大得多。

---

## 五、每个阶段的验收方式

| 阶段 | 验收 |
|---|---|
| 0 | 真机跑通 `01_Endless_plant_ref.json` 前 3 节点，界面可见推进 |
| 1 | 报出单节点识别耗时 P50/P95、注入耗时 P50/P95，且都达标 |
| 2 | 用真实 pipeline 跑完一段完整流程（如无尽挑战入口） |
| 3 | 在 1080p 设备 / 竖屏下不崩、识别不偏 |
