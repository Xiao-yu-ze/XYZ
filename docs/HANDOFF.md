# 项目交接文档

> **用途**：记录每一次工作做了什么、验证到什么程度、留下什么坑。
> 每次改动后**追加**一节，不要覆盖历史。
>
> **维护约定**：
> - 「已验证」和「未验证」必须分开写 —— 编译通过 ≠ 真机能跑。
> - 修了什么 bug 要写**怎么发现的**，否则下一个接手的人会重复怀疑一遍。
> - 明确区分「我判断错了并纠正」和「真的修了 bug」。

---

## 项目速览

| 项 | 值 |
|---|---|
| 项目 | PVZ2（植物大战僵尸2）Shizuku 系统级安卓自动化 |
| 语言 / 构建 | Kotlin · Gradle 8.13 · AGP 8.7.3 · Kotlin 2.0.21 · JDK 17 |
| SDK | minSdk 26 · targetSdk 34 · compileSdk 35 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（约 91.8 MB） |
| 远程仓库 | `https://github.com/Xiao-yu-ze/XYZ.git`（分支 `main`） |

### 两条技术路线

| 路线 | 截图 | 触控 | 前台服务类型 |
|---|---|---|---|
| **主路线** Shizuku | UserService 内 `screencap -p`，经 ParcelFileDescriptor 管道回传 | `input tap/swipe` | `specialUse` |
| **降级路线** | MediaProjection + ImageReader | AccessibilityService `dispatchGesture` | `mediaProjection` |

### 关键外部依赖（都不入库）

| 需要什么 | 在哪 | 怎么同步 |
|---|---|---|
| 模板图 / OCR 模型 / 坐标表 / 作业集 / pipeline | MaaPVZ 安装目录 | `.\tools\sync-maapvz-assets.ps1 -MaaPvzRoot "<路径>"` |
| JDK 17 + Android SDK | `_toolchain/`（约 1.9 GB） | 已有 Android Studio 可删，用自带的 |
| `local.properties` | 项目根 | 指向 SDK；**不入库** |

> ⚠️ **配套版本极重要**：作业集 `pvz_20261002_215257.json` 必须与 pipeline 来自
> **同一次 MaaPVZ 构建**。已验证配套的是：
> `C:\Users\29374\Desktop\MaaPVZ-win-x86_64-v0.5.4-ci.261001-670c36e`
> 桌面上的 `MAAPVZ123/` 是**开发分支**（v0.5.4-beta.2-45），**没有 `jobs/` 目录、
> 也没有匹配的节点名**，用它会发现大量节点"找不到"。

---

## 模块地图

```
app/src/main/java/com/pvz2/auto/
├─ MainActivity.kt              权限控制台 + 启动按钮
├─ core/                        ★ 基础层（稳定）
│  ├─ ScreenAdapter.kt          逻辑坐标(720p) ↔ 物理像素，高度对齐+水平居中
│  ├─ TemplateMatcher.kt        旧路径的模板匹配（Pvz2Bot 用）
│  ├─ ScreenCapturer.kt         截图抽象       TouchInjector.kt  触控抽象
│  ├─ Mats.kt / BotState.kt     Bitmap↔Mat / 状态 StateFlow
├─ shizuku/                     ★ 特权层（稳定，真机验证过）
│  ├─ Pvz2UserService.kt        ★ 以 shell/root 身份跑，PFD 管道截图
│  ├─ ShizukuUserServiceClient.kt  bindUserService + await
│  ├─ ShizukuBackends.kt        截图/触控的 Shizuku 实现
│  └─ ShizukuBridge.kt          可用性 + 权限
├─ fallback/                    MediaProjection 截图 / Accessibility 手势
├─ service/AutomationService.kt ★ 前台服务，托管所有长生命周期资源
├─ overlay/FloatingWindow.kt    可拖动悬浮面板
├─ task/                        旧路径：识别到就点的线性规则表
│  ├─ Pvz2Bot.kt / Pvz2Tasks.kt / BotAction.kt
└─ maa/                         ★ MaaFramework 协议移植层（本次重点）
   ├─ NodeDef.kt                Pipeline JSON 解析（v1 扁平 / v2 嵌套 + 注释剥离）
   ├─ NodeGraph.kt              ★ 节点图执行器（next/on_error/timeout/max_hit + 干跑）
   ├─ Recognizer.kt             四种识别：TemplateMatch(含绿幕) / ColorMatch / OCR / AnyOf
   ├─ Recognition.kt            Recognition 密封接口 + Hit + Targets + Boxes
   ├─ OcrEngine.kt              PP-OCRv5 det+rec ONNX 推理 + CTC 解码（手写）
   ├─ CoordTable.kt             81 个命名坐标点 + 9x5 草坪格子公式
   ├─ Pvz2Ui.kt                 高层操作封装（find/tap/swipe/waitFor）
   ├─ PipelineRunner.kt         ★ 方案 C：跑声明式识别链
   ├─ RecognitionProbe.kt       识别自检（打印模板分数）
   └─ ConditionalTapSample.kt   教学示例，不接主流程
```

### 当前主循环接的是哪条路径

`AutomationService.runLoop()` 跑的是**旧路径** `Pvz2Bot` + `Pvz2Tasks.defaultActions()`。
`templates/` 目录是空的，所以旧路径实际命中 0 个模板。

**`maa/` 层目前只有两条入口在跑**：
- `AutomationService.probe()` → `RecognitionProbe`（识别自检，只读）
- `AutomationService.runVerifyChain()` → `PipelineRunner`（方案 C 识别链）

---

## 变更历史

### 会话 1 — 项目初读与代码审查

**做了什么**：通读全项目，定位 `maa/` 层与主循环脱节。

**发现的问题（当时只记录，未修）**：
1. `maa/` 整个包（`NodeGraph`/`Recognizer`/`OcrEngine`/`CoordTable`/`Pvz2Ui`）**没有任何生产代码引用**，是死代码。主循环仍跑 `Pvz2Bot`。
2. `assets/jobs/` 有作业集数据，但**没有代码加载它**。
3. `Recognizer.kt` 的 `box` 计算量纲不一致（`size` 已是物理像素却又 `/ current.scale`，而 `topLeft` 是逻辑坐标）。**至今未修**。

**验证到哪**：纯静态阅读，无编译、无真机。

---

### 会话 2 — 加固 `Pvz2Ui` 操作封装

**背景**：用户已经把点击/滑动封装成了 `Pvz2Ui`，要求审查。

**修改了 5 处**（`maa/Pvz2Ui.kt`）：

| # | 问题 | 修法 |
|---|---|---|
| 1 | `waitForHit`/`waitUntilGone` 里 `delay(intervalMs)` 是**叠加**在识别耗时之后，真实周期变成 `识别耗时+intervalMs`；且超时靠循环内检查时间戳，`capture()` 卡死就**永久挂起** | 改为从本轮起点计时补偿，整体包 `withTimeoutOrNull` |
| 2 | `tapFirstFound` 逐个调用 `find()` → **每个候选一次截图**，画面可能已变（正是 `findFirstOf` 注释批评过的坑） | 改成共用同一帧；**点击移到 `withFrame` 外面**（`Mutex` 非可重入，持锁内调 `tap` 会死锁） |
| 3 | `swipe` 把 `PointF.x` 直接传给 `TouchInjector`，其内部 `Float.toInt()` 是**截断**，每个端点稳定少 1px；且 `tap` 有抖动而 `swipe` 没有 | 补 `roundToInt()` + 抖动 |
| 4 | 缺长按 | 新增 `longPress()`（同点 swipe 即长按） |
| 5 | `calibrate()` 的 `{ true }` 读起来像没做事 | 补 KDoc 说明它建的是 `ScreenAdapter` |

**诚实边界**：`withTimeoutOrNull` 只能保证**调用方**按时返回；卡在阻塞 AIDL 往返上的 IO 线程无法被协程取消。这点写进 KDoc 了，没假装解决。

**踩到的编译错误**：`val (a, b) = found ?: return null` 报 `Non-nullable value required to call 'component1()'` —— `withFrame` 返回 `T?`，`?:` 之后智能转换带不进解构声明。改用显式 `found?.first` / `found.second`。

**验证到哪**：`assembleDebug` **编译通过，0 警告**。**未真机验证。**

---

### 会话 3 — 勘察 MaaPVZ 作业集与真实引擎

**做了什么**：读桌面上的 `skill/`、`MAAPVZ123/`，最后定位到**第三处**才是关键。

**核心发现**：

1. **编码坑**：作业集 JSON 无 BOM、UTF-8。`Get-Content -Raw | ConvertFrom-Json` 按 GBK 读会全乱码（`鏃犲敖`=`无尽`）。Kotlin 侧必须显式 UTF-8。

2. **配套版本**：`MAAPVZ123/` 是开发分支，**无 `jobs/`**。真正配套的是
   `MaaPVZ-win-x86_64-v0.5.4-ci.261001-670c36e`，其中作业集与该包的 pipeline 逐字节匹配。

3. **作业集结构**：顶层 `code/name/worlds/max_level/everyN/outer_pick/tables[]/format`。
   `tables` 是 3 个关卡段（1-46 / 46-47 / 47-150），每段 38 个字段分四类：配牌、
   棋盘布局（`boardEarly/boardLate`、`non_boss/boss`）、执行参数、`compiled`。

4. **`compiled` 只是冰山一角**：只含 6 个节点，**全是 `Custom: BatchSwipe`**，且引用了
   5 个本文件未定义的节点。这 5 个是"识别节点"，定义在**基础 pipeline 资源**里 ——
   `sync-maapvz-assets.ps1` 原来**漏了同步 `resource/pipeline/`**。

5. **🔴 真正的引擎在 `agent/jobset/`**（之前完全没料到）：
   ```
   engine.py        710 行  作业集载入 / 选表(Table) / 规则翻译
   runtime.py      1683 行  ★ CustomAction 注册（JobSetLoad/Fight/Plan/Stage/AutoCount）
   compile.py       585 行  ★ 把作业集编译成 pipeline override
   dsl.py           508 行  坐标解析 / DSL 生成
   level_tracker.py 351 行  关卡计数器状态机
   ```
   **这意味着作业集与 pipeline 之间还隔着一个「编译器 + 运行时改写层」**，
   而它在 Kotlin 侧完全不存在。`NodeGraph` 只是**执行器**，不是完整引擎。

6. **入口不是 `进入到局内`**。`interface.json` 里是
   `{ "name": "无尽挑战（重构）", "entry": "无尽挑战_前置检查_ref" }`。

**给出的方案选项**：
- A 全量 Kotlin 移植（约 5000 行 Python）→ **不现实**
- B 保留 Python Agent，只做截图/触控桥接
- C 先只跑纯声明式识别链，验证识别层 ⭐ 用户选了 C

**验证到哪**：纯勘察（读文件 + 字符串检索 + 字节比对），无代码改动。

---

### 会话 4 — 方案 C：识别链验证（本次）

**目标**：回答「模板匹配和 OCR 在真机上到底准不准」——这是决定要不要移植引擎的前提。

#### 新增文件

**`maa/PipelineRunner.kt`**
- 载入 `assets/pipeline/Endless_ref/` 下所有 JSON（按文件名排序，后者覆盖前者，与 MaaFramework 一致）
- 用 `PipelineParser.parseAll()` 合并，交给 `NodeGraph` 执行
- `VerifyChain.ENTRY = "无尽挑战_点击竞技"` —— 从识别链中段切入（入口第一步是
  `JobSetLoad`，Custom 动作，我们跑不了）

**`docs/VERIFY_CHAIN.md`** —— 真机验证操作手册（含分数档位判读表、已知风险、下一步分支）

**`docs/HANDOFF.md`** —— 本文件

#### 修掉的 4 个真 bug

**① `CoordTable` 编码 bug**（`maa/CoordTable.kt`）
```kotlin
// 之前：bufferedReader() 不带参数 → 平台默认编码 → 中文 Windows 上按 GBK 解
val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
// 现在：显式 UTF-8
val text = context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
```
**症状**：81 个中文键名全乱码 → `point()` 抛「坐标表中不存在」，但表里明明有 —— 极难排查。

**② pipeline JSON 的 `//` 注释（阻断性）**（`maa/NodeDef.kt`）
MaaPVZ 的 pipeline 大量用 `//` 写设计说明（`0300Endless_fight.json` 40+ 行）。
Android 的 `org.json.JSONObject` 是**严格解析器**，遇注释直接抛 `JSONException`
→ **整个文件作废、节点全消失、流程静默跑不起来**。
新增 `stripJsonComments()`，**正确处理字符串字面量内部的 `//`**（如 URL，否则会把后面内容全吃掉）。
**实测：14 个 pipeline 文件全部解析通过。**

**③ 不支持的识别类型退化成 `DirectHit`（危险）**（`maa/NodeDef.kt`）
原 `else` 分支虽然是 `Recognition.DirectHit` 且注释写着"宁可当未命中处理"，
但 **`DirectHit` 等价于「永远命中」** —— 一个我们不支持的识别类型会立刻被当成命中、
执行动作、跳到完全错误的节点，比"不命中"危险得多。
改成返回空模板列表（`Recognizer` 对 `templates.isEmpty()` 返回 null = 永不命中）+ 打日志。

**④ 同步脚本的 `Copy-Item` 嵌套 bug**（`tools/sync-maapvz-assets.ps1`）
`Copy-Item srcDir dstDir -Recurse` 在 `dstDir` **已存在**时会把整个 `srcDir` 塞进去，
得到 `dstDir/srcDir/…`。之前 `assets/` 里出现过 `jobs/jobs/`、`ocr/ocr/`
（APK 白多一份 20 MB 的 ONNX 模型）。
改成：先删目标，目录则展开**内容**再拷。
**顺带加了 `resource/pipeline/` 的同步规则**（原来漏了，见会话 3 第 4 点）。

#### 顺带的功能改动

- **`NodeGraph` 新增 `dryRun` 参数**：`true` 时只识别不点击，把「本该点哪里」打进日志。
  首次真机验证必须用它 —— 模板/ROI/坐标任何一处错了都能看出来，而不会在游戏里乱点。
- **`AutomationService`**：抽了 `withBootstrap()`（Shizuku 绑定 + `getDisplaySize` 端到端探活）；
  新增 `runVerifyChain(dryRun)` 与两个入口 `verifyDryRun()` / `verifyRun()`。
- **`MainActivity` + `activity_main.xml`**：加两个按钮「① 识别链·干跑」「② 识别链·实跑」。
- **`.gitignore`**：加 `app/src/main/assets/pipeline/`。

#### 我怀疑错了、并主动纠正的两处

**① 文件名乱码（不存在的问题）**
在 APK 里看到 `绔炴妧.png`，一度判定「真乱码」。查 ZIP 中央目录原始字节后发现：
名字字节是**正确的 UTF-8**（`竞技` = `31454,25216`），只是 UTF-8 flag 位为 `0x0000`。
是我的 PowerShell（`System.IO.Compression`）把已是 UTF-8 的字节**又按 GBK 解了一遍**造成的显示假象。
**结论：没有这个 bug。** 教训：验证编码问题要看原始字节，不要信控制台显示。

**② `swipe` 的 `roundToInt`（上一轮说重了）**
上一轮我说"致命"，实际 `Float.toInt()` 是截断、不会崩，只是每个端点偏 1px。
**当时已收回该措辞。**

#### 验证到哪

| 项 | 状态 |
|---|---|
| `clean :app:assembleDebug` | ✅ **通过，0 警告 0 错误** |
| 14 个 pipeline 文件经注释剥离后解析 | ✅ **全部通过**（用等价算法实测） |
| 三个链节点解析结果 | ✅ 节点名/识别类型/`next` 均正确，66 个根级节点合并 |
| APK 资源完整性 | ✅ 无嵌套重复；`pipeline` 14 / `maapvz` 11 / `ocr` 4 / `jobs` 2 / `coords.json` |
| **真机运行** | ❌ **未验证** |

---

### 会话 5 — 真机验证（第二位接手，有设备）

**背景**：会话 4 卡在「无设备」，而这次接手的人有 MuMu 模拟器 + PVZ2。
所以直接推进了真机验证。

#### 环境

| 项 | 值 |
|---|---|
| 设备 | MuMu 模拟器，Android 12 / x86_64 / **1280×720**（与模板基准完全一致） |
| Shizuku | 13.6.0，以 **shell (uid 2000)** 启动（普通手机路线，非 root） |
| 游戏 | `com.popcap.pvz2cthdop`（PVZ2 国服） |

#### 🔴 修掉的阻断性 bug：assets 目录布局不对

**症状**：干跑时每个模板都 `FileNotFoundException`，**一个都加载不到**。

```
W/Recognizer: 无法加载模板：Endless/无尽挑战.png
W/Recognizer: java.io.FileNotFoundException: Endless/无尽挑战.png
```

**根因**：素材被同步到 `assets/maapvz/Endless/无尽挑战.png`，
而 pipeline JSON 里写的是 `Endless/无尽挑战.png` —— 两者目录基准不一致。

**正确修法（用户指出的）**：**把图片资源直接铺到 `assets/` 根下**，
让 `assets/` 与 MaaPVZ 的 `resource/image/` 一一对应，路径原样就能打开。

> 我一开始的修法是「在 `Recognizer` 里补 `maapvz/` 前缀」——**这是错的**，
> 等于用代码去迁就错误的目录布局。应让布局本身正确。
> 这一点由用户当场纠正。

改动：
- `tools/sync-maapvz-assets.ps1`：第 2 步改为 `Copy-Into resource/image -> assets`
- `RecognitionProbe.PREFIX` 删除，路径不再加前缀
- `.gitignore`：改为逐个列出 `resource/image/` 下的子目录

#### ⚠️ 我自己走错又收回的一步（记录下来避免重犯）

设备侧一直报 `FileNotFoundException`，我用 Windows 的 `apkanalyzer` 去核对
APK 内的中文文件名，看到输出是 `鏃犳敖鎸戞垬.png`（乱码），
**一度判定为「APK 打包时 zip 条目 UTF-8 标志位丢失」**，
并动手写了一套「同步时把中文名重命名为 ASCII + 别名表」的机制。

**这是错的。** 用设备上的 `unzip -l` 复核后真相是：

```
assets/maapvz/Endless/无尽挑战.png     ← 文件名是正常 UTF-8，而且带着 maapvz/ 前缀
```

即：**它一直是旧版 APK**（我删掉 `maapvz/` 后没有重新安装成功），
`apkanalyzer` 的乱码只是它自己的控制台显示问题。

**教训**：
1. 核对 APK 内文件名要用**设备侧 `unzip -l`**，不要信 Windows 工具的控制台输出。
2. `install -r` 可能静默沿用旧包；关键验证前应 `uninstall` 再 `install`。
3. 在写下「机制性绕行」（重命名 + 别名表）之前，先确认前提成立。

那套 ASCII 重命名机制已**完全撤销**，未留在代码里。

#### ✅ 真机验证结果

**基础设施全部打通**（这部分是确定的）：

| 项 | 结果 |
|---|---|
| pipeline 解析 | ✅ 7 个文件、66 个节点全部载入 |
| OCR 初始化 | ✅ 字典 18383 字符，det/rec 模型加载成功 |
| Shizuku 截图 | ✅ 帧尺寸 1280×720，`scale=1.0 offsetX=0.0` |
| 节点图执行 | ✅ 正常轮询、超时、退出，无崩溃 |
| 模板加载 | ✅ **不再 `FileNotFoundException`** |

**模板分数（首次拿到真实数据）**：

| 模板 | 分数 | 位置 |
|---|---|---|
| `create_yard/竞技.png` | 0.4364 | (67,317) |
| `Endless/无尽挑战.png` | 0.2829 | (1216,318) |
| `General/能量豆.png` | 0.2099 | (30,180) |
| `General/暂停.png` | 0.1645 | (38,250) |
| `Endless/frame/boss/僵王的头.png` | 0.5243 | (1237,25) |
| `Endless/frame/boss/功夫僵王.png` | 0.6743 | (1233,17) |
| `Endless/frame/僵尸头像.png` | 0.5184 | (14,22) |
| `Endless/继续挑战_恭喜过关.png` | 0.2489 | (143,222) |
| `Endless/out.png` | 0.1973 | (468,169) |
| `Endless/yes.png` | 0.2438 | (1054,218) |
| `Start_game/叉叉.png` | 0.6479 | (61,228) |

**⚠️ 这批分数不能判定识别层好坏** —— 因为**当时游戏不在对应界面**
（探测时游戏停在某个非主菜单画面）。分数普遍偏低是正常的：
没有对应 UI 元素时，`TM_CCOEFF_NORMED` 就是会给出低分。

**但有一处值得警惕**：`功夫僵王.png` 拿到 0.6743、`叉叉.png` 0.6479，
都接近 0.7 的默认阈值。**如果它们在错误画面上就能拿这么高，
说明这些模板本身区分度不够**（图案简单或与背景色块相似），
真实使用时容易误判。移植前建议先用 `green_mask` 或收紧 ROI 压制。

#### 下一步

**必须先把游戏停到「能看到『竞技』按钮的主界面」再跑干跑**，
才能得到有意义的分数。这一步需要人操作游戏（我无法自动推进游戏内的新手引导/登录）。

判读标准见 `docs/VERIFY_CHAIN.md`。

---

### 🔴 高优先级

1. **OCR 从未在真机跑过。** `OcrEngine` 是手写 ONNX 推理，三处都可能错：
   - `keys.txt` 是 CRLF（已剥 `\r`），但**类别数对齐**（18385 vs 18383）未实测；
   - CTC 解码的 blank / 重复字符处理；
   - PP-OCR 的 NCHW 布局与归一化参数。
   **若模板匹配正常但 OCR 全灭，问题一定在这三处。**

2. **识别分数未知。** ~~没有任何真实分数数据~~
   **已有首批数据（会话 5）**，但当时游戏不在对应界面，分数不可用于判定。
   需要在正确的界面上重测。

3. **`Recognizer.kt` 的 `box` 量纲 bug**（会话 1 第 3 点）。
   **会话 5 复核：不是 bug，是写法绕。**
   `topLeft` 经过 `toLogical` 是逻辑坐标，而 `size/scale` 数学上恰好抵消回
   逻辑尺寸，**结果是对的**。已改为直接写逻辑尺寸并加注释说明两套坐标系，
   消除"改一半就静默错位"的隐患。**功能无变化。**

### ⚠️ 中优先级

4. **作业集 → pipeline 的编译层不存在。** 见会话 3 第 5 点。
   没有它，作业集只是一堆数据，跑不起来。

5. **`AutomationService.runLoop()` 仍跑旧路径**，`templates/` 是空的 → 命中 0 个模板。
   旧路径是保底退路，等 pipeline 路径稳定后再删。

6. **capture 卡死无法被取消。** 阻塞 AIDL 往返不受协程取消影响。
   目前靠 `NodeGraph` 自己的 `timeout` 兜底（表现为该节点超时走 `on_error`）。

### ℹ️ 低优先级 / 说明

7. **`NodeGraph.kt` 与 `Pvz2Ui.kt` 各有一个 `waitForHit`**（签名不同、语义不同），容易看混。
   `NodeGraph` 那个是私有方法、逻辑正确。

8. **APK 91.8 MB** 主要是 OCR 模型（det 4.6 MB + rec 16.1 MB）与 OpenCV native。
   若要减体积，OCR 模型可改成运行时下载。

9. **`_toolchain/` 约 1.9 GB**，已有 Android Studio 可整个删掉。

---

## 下一步

**当前卡在真机验证**（我无设备、无 PVZ2 环境）。

### 你要做的

```powershell
# 1. 同步素材（必须，否则 pipeline 是空的）
.\tools\sync-maapvz-assets.ps1 -MaaPvzRoot "C:\Users\29374\Desktop\MaaPVZ-win-x86_64-v0.5.4-ci.261001-670c36e"

# 2. 编译
.\gradlew.bat :app:assembleDebug

# 3. 装到手机，停在 PVZ2 主界面（能看到「竞技」按钮）
#    点「① 识别链·干跑」（只识别不点击）

# 4. 看日志
adb logcat -s PipelineRunner:I NodeGraph:I Recognizer:I OcrEngine:I
```

### 结果如何判读

| 结果 | 下一步 |
|---|---|
| 三节点命中，分数 ≥0.90，OCR 能认对「开始挑战/继续挑战」 | **识别层可用** → 值得推进移植引擎（方案 A/B） |
| 模板匹配正常，**OCR 全灭** | 先修 OCR，集中在 `OcrEngine.kt` 三处 |
| 模板分数普遍偏低 | 检查模板缩放策略（`Recognizer.scale` vs MaaPVZ 的 720p 裁剪基准） |
| 完全没反应 | 检查截图链路 `ShizukuScreenCapturer`、确认 assets 已同步 |

### 预期会看到「失败」

链走到 `无尽挑战_点击开始挑战` 之后指向 `无尽挑战_自动计数`，它需要
`JobSetAutoCount` 这个 Custom 动作 —— **我们没实现**。届时会打
「未注册的自定义动作：JobSetAutoCount」然后按失败处理。

**这是预期行为**，说明链已跑通到引擎的边界，不是 bug。

---

## 提交流水

| commit | 内容 |
|---|---|
| `6bd1abe` | PVZ2 Auto: Shizuku-based automation scaffold（初始骨架） |
| `e9aa665` | chore: verify credential helper enables automatic push |
| `e201e6c` | docs+tooling: verify Shizuku end-to-end on device, on both root and ADB |
| `193d69e` | feat(maa): port MaaPVZ pipeline layer and harden the Pvz2Ui action wrapper（会话 2） |
| *待提交* | 会话 4：方案 C 识别链 + 4 个 bug 修复 |
| *待提交* | 会话 5：真机验证 + 修 assets 布局（铺到 assets 根下） |

> **注意**：`app/src/main/assets/` 下的 `maapvz/`、`ocr/`、`jobs/`、`pipeline/`、
> `coords.json` 都在 `.gitignore` 里（游戏素材版权 + 模型体积），
> **只保留在本机，不入库**。换机器必须重跑同步脚本。
