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

### 会话 6 — 用逆向出的分辨率算法修正坐标系

**背景**：用户提供了 `C:\Users\29374\Desktop\pvz算法\` —— 从 PVZ2 中文版
`libSrc.so` 逆向出的分辨率适配算法（A 级实证，全部来自原始反汇编）。

#### 先确认基准分辨率（原先只是猜测，现在是实证）

`MaaPVZ/interface.json:25`：

```json
"display_short_side": 720
```

即 **MaaFramework 把截图短边归一到 720** 再识别。横屏下短边 = 高，
所以 `ScreenAdapter.scale = screenHeight / 720` **一直是对的**，可以放心。

顺带确认：`MaaPVZ/agent/` 自己**不做任何缩放**，`my_action.py:360` 只是把
`controller.resolution` 打日志，缩放全交给 MaaFramework 控制器。

#### 🔴 修掉一个真 bug：`offsetX` 的算法是错的

旧实现假设「宽度不够就水平居中留边」：

```kotlin
offsetX = (screenWidth - 1280 * scale) / 2      // ← 错的
```

游戏实际用的是 `GetScreenContentOffset`（**ELF 0x22E7C24**，92 字节），
反汇编关键三行：

```asm
ELF 0x022e7c48  fmaxnm s0,s2,s0    ; max(内容区宽, (float)mWidth)   ← 漏这个就错
ELF 0x022e7c64  cinc   w9,w9,lt    ; 负数补偿
ELF 0x022e7c68  asr    w9,w9,#0x1  ; 算术右移，不是 /2
```

因为 `mWidth = (int)(origW × newH / origH)` **恒等于屏宽**，所以只要
宽高比 ≥ 4:3，`fmaxnm` 就取 `mWidth`，差值恒为 0
⇒ **`offsetX = 0`：游戏内容左对齐，不做居中留边。**

**影响量化**（固定点取 `加速(1171,35)`）：

| 分辨率 | 宽高比 | 旧 offsetX | 新 offsetX | 固定点偏差 |
|---|---|---|---|---|
| 1280×720（MuMu） | 1.778 | 0 | 0 | **0** |
| 1920×1080 | 1.778 | 0 | 0 | **0** |
| 2340×1080（19.5:9 手机） | 2.167 | 210 | 0 | **210 px** |
| 2400×1080（20:9 手机） | 2.222 | 240 | 0 | **240 px** |
| 1600×1200（4:3） | 1.333 | −266.7 | 0 | **267 px** |
| 1000×1200（窄屏） | 0.833 | −566.7 | **−300** | 267 px |

**为什么之前没暴露**：只在**固定逻辑坐标的点击**和 **ROI** 上出错，
「识别到哪就点哪」的换算里 offset 会来回抵消。而 MuMu 是 16:9，
新旧都是 0 —— 所以前几次真机验证全部通过，问题被掩盖了。

**为什么非改不可**：`BatchSwipe`（种植引擎）正是靠 `coords.json` 的固定坐标
驱动的，pipeline 的 `roi` 也是逻辑坐标。不修，移植完必错。

**独立验证**：窄屏 1000×1200 我算出 `−300`，与逆向文档里记的实测值
「1000×1200 窄屏 → offsetX=−300（漏 fmaxnm 会算错）」**完全一致**，
说明实现忠实还原了游戏公式。

#### 刻意**没有**实现的部分

超长屏特例（宽高比 ≥ 2.1）在游戏里走 `FUN_023e7850`，会按
`adj = K_w × i`（i = 399/402/409）做额外微调。但**该函数的设备判定条件
（`NEON_umaxv` 归约）尚未闭合**，逆向文档列为待验项。
**没有确证的算法宁可不要** —— 免得引入猜测性偏移。已在代码里写明。

#### 还没用上的东西（留给下一步）

`pvz2算法\算法\ui_inventory.txt` 有 **44 个 Lua UI 布局 / 148 个按钮的完整坐标**，
`UI位置算法.md` 归纳了四种定位模式。对**固定 UI**（暂停、加速、下一波、阳光计数器）
这比模板匹配可靠得多 —— 不需要模板，不会因画面变化误判。
真要接管无尽挑战的操作层时值得用。

#### 验证到哪

| 项 | 状态 |
|---|---|
| `assembleDebug` | ✅ 0 警告 0 错误 |
| MuMu 1280×720 回归 | ✅ `offset=0.0,0.0`，11 个模板全部加载，无变化 |
| 窄屏公式对拍 | ✅ 与逆向文档实测值 −300 一致 |
| **真机跨分辨率验证** | ❌ **未做** —— 需把 MuMu 分辨率改成非 16:9 再跑 |

---

### 会话 7 — 识别层首次真机验证 + 虚拟屏（方案 B）三路调研

本会话解决了两件长期悬着的事：**识别层到底能不能用**（有答案了），
以及**「游戏后台跑」这个需求到底怎么实现**（有完整配方了）。

#### 7.1 ★ 识别层验证通过 —— 拖最久的未知数解决了

游戏停在主界面时 `screencap` 抓 1280×720，对 11 个模板算 `TM_CCOEFF_NORMED`：

| 模板 | 分数 | 位置 | 判定 |
|---|---|---|---|
| **`create_yard/竞技.png`** | **0.9920** | (800, 673) | **强命中** |
| `Start_game/叉叉.png` | 0.7640 | (1052,524) | 偏弱 |
| `Endless/frame/boss/僵王的头.png` | 0.7330 | (1147,225) | 偏弱 |
| `Endless/frame/僵尸头像.png` | 0.6449 | (139,482) | 未命中 |
| `Endless/frame/boss/功夫僵王.png` | 0.6443 | (437,408) | 未命中 |
| 其余 6 个 | 0.25 ~ 0.50 | — | 未命中 |

**MaaPVZ 阈值是 0.8，0.9920 远高于它。**

这幅结果有两层意义：
1. **模板、assets 布局、720p 基准 + `scale=1.0`、算法实现 —— 全部正确。**
2. 其余 10 个模板低分是**正确行为**：它们属于无尽挑战局内/结算界面，
   当前屏没有那些元素。**这反过来证明不会乱匹配。**

**方法（重要，因为 App 内的路径现在走不通）**：见 7.5。

#### 7.2 方案 B（虚拟屏后台跑）—— 三路独立调研，结论收敛

需求：真机、无 root、游戏不占物理屏。派了三个研究员分头查
MaaFramework / scrcpy / AOSP 能力边界，**三条独立路线收敛到同一套 API**。

**建屏**
```kotlin
DisplayManager(FakeContext).createVirtualDisplay(name, w, h, dpi, surface, flags)
// packageName 必须传 "com.android.shell"（validatePackageName）
```

**flags（三份实现一致；常量值来自 AOSP `android14-release`）**

| flag | 值 |
|---|---|
| PUBLIC / PRESENTATION / OWN_CONTENT_ONLY / SUPPORTS_TOUCH | 1 / 2 / 8 / 64 |
| ROTATES_WITH_CONTENT / DESTROY_CONTENT_ON_REMOVAL | 128 / 256 |
| TRUSTED / OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED / TOUCH_FEEDBACK_DISABLED | 1024 / 2048 / 4096 / 8192 |
| OWN_FOCUS / DEVICE_DISPLAY_GROUP / STEAL_TOP_FOCUS_DISABLED | 16384 / 32768 / 65536 |

基础集 **459**，API33+ 加 **15360**，API34+ 再加 **49152** → 总计 **74971**。
开 system decorations 再 `+512`。

**★ `PUBLIC` 为什么必需（机制，不是拍脑袋）**：
缺 PUBLIC → 虚拟屏被标 `DisplayDeviceInfo.FLAG_PRIVATE` →
`DisplayInfo.hasAccess(uid)` 对**非 owner uid** 返回 false。而 ownerUid 是
`com.android.shell`，游戏是另一个 uid → **挂不上窗口**。
（Mi-FreeForm / MaaScreen 只传 `PRESENTATION` 也能跑，是因为它们把自己 App
的窗口放上去，`uid == ownerUid`；换成搬别的 App 就不行。）

**权限为什么能过 —— 不是绕，是 shell 的既有特权**
`packages/Shell/Android.bp`：`certificate: "platform"`、`privileged: true`。
其 manifest 显式持有 `ADD_TRUSTED_DISPLAY`、`INJECT_EVENTS`、`ACCESS_SURFACE_FLINGER`、
`MANAGE_ACTIVITY_TASKS`、`INTERNAL_SYSTEM_WINDOW` 等，**但没有 `CAPTURE_VIDEO_OUTPUT`**。

**★ 关键**：`DisplayManagerService.createVirtualDisplay` 里那条权限检查的开关是
**`AUTO_MIRROR`**，不是 PUBLIC：
```java
if ((flags & FLAG_OWN_CONTENT_ONLY) != 0) flags &= ~FLAG_AUTO_MIRROR;   // 覆盖掉
if (callingUid != SYSTEM_UID && (flags & FLAG_AUTO_MIRROR) != 0) {
    if (!canProjectVideo(projection)) throw new SecurityException(...);  // 我们永远走不到这
}
```
我们的 flags 永不含 `AUTO_MIRROR`，所以这条检查直接跳过。
**推论（高置信、未取到原文行）**：Android 13+ 为 `TRUSTED` 另加了一道
`ADD_TRUSTED_DISPLAY` 检查 → **API 33+ 必须带 `TRUSTED`**，否则会落到需要
`CAPTURE_VIDEO_OUTPUT` 的分支而被拒。

**这条路走不通的**（已排除，别再试）：
- `MediaProjection.createVirtualDisplay` —— 服务端对普通 App **强制**改写 flags 为
  `AUTO_MIRROR | PRESENTATION`，注释原文「Do not declare a display id to mirror;
  default to the default display」，**只能镜像默认屏**。Android 14 的
  partial screen sharing 因此也无关。
- `cmd display` —— 全量子命令里**没有** create-virtual-display。
- `SurfaceControl.createDisplay` —— 只用于「镜像主屏」的 fallback；Android 12 起
  shell 不能建 secure display。

**版本底线：Android 11+，不是 10。**
scrcpy 代码写的是 `SDK_INT < 29` 拒绝，但 issue #5541 实测推翻：
Android 10 上**能建屏、但任何 App 都被拉回主屏**（弹 "app doesn't support to
launch on secondary display"）。维护者同意把条件改成测 Android 11。

**其余环节**

| 环节 | 做法 |
|---|---|
| 拉 App | `ActivityOptions.makeBasic().launchDisplayId = id` → `IActivityManager.startActivityAsUser`；失败退 `am start --display N` |
| 截屏 | **`screencap -d <物理id>` 裸格式**：头 `w,h,format,colorspace` 4×u32 + RGBA8888 行。省掉 PNG 编解码 |
| 也有 | MaaFwApp 用 NDK `AImageReader(RGBA_8888, CPU_READ_OFTEN\|GPU_SAMPLED_IMAGE, 5)` 当 surface 消费端 |
| 注入 | `InputEvent.setDisplayId(<逻辑id>)` → `InputManager.injectInputEvent`；**DOWN 用 `WAIT_FOR_FINISH(2)`，MOVE/UP 用 `ASYNC(0)`** |
| 保活 | 虚拟屏不需要 `setDisplayPower`；用 `PowerManager.userActivity(displayId)` |

**★ 三个 id 不是同一个东西（会踩）**
- `screencap -d` → **物理 display id**（`ScreenshotClient.captureDisplay(PhysicalDisplayId)`）
- `am start --display` / `input -d` → **逻辑 display id**
- `input -d` 是**全局选项**，必须写在子命令**之前**：`input [-d ID] tap x y`

**★ 不传 `-d` 会点到用户主屏**：`displayId == INVALID_DISPLAY` 且 source 是指针类时，
会自动回落 `DEFAULT_DISPLAY` —— 调试时极易误点到用户正在用的界面。永远显式传 `-d`。

**旋转：不要用 `freezeRotation`**
它不是 `wm` 子命令（真名 `wm user-rotation -d <id> lock <rot>` → `IWindowManager.freezeDisplayRotation`），
且**方法签名在版本间碎了三次**，老版本只支持默认屏。scrcpy 完全不用它，走纯客户端方案：
读 `DisplayInfo.rotation` → `rotation%2!=0` 时交换 w/h → 截帧做 AffineMatrix 逆变换 →
**注入坐标用物理尺寸坐标系**。跟这个走，可移植性好得多。

根因（scrcpy 注释）：**`createVirtualDisplay` 给你的 Surface 永远是「未旋转」的原始朝向，
而 `DisplayInfo` 报的是「旋转后」的尺寸** —— 两者不一致时截图会歪、注入要补逆变换。

#### 7.3 ★ 两个会咬人的坑（都直接影响现有代码）

**① `input tap` 在低帧率下会丢点击**
MaaFwApp 的 `TapHoldPolicy.minHoldMs(fps)` 按帧率补足最短按住时长。
游戏在副屏跑不满帧时，**down/up 落进同一帧间隙 → 整次点击丢失**。
表现是「偶尔点不中」，最难查。
→ **必须改成 DOWN/UP 分离 + 可控最短按住**，不能用 `input tap`。
（这也解释了 MaaFramework 的 `MaaControllerFeature_UseMouseDownAndUpInsteadOfClick`。）

**② 虚拟屏「没人画就没帧」**
scrcpy 文档原文：*"if no app is started, no content will be rendered, so no video
frame will be produced at all"*。MaaFwApp 因此有 `awaitFirstFrame()`（5s 超时）并
**拒绝在虚拟屏空置时启动任务**。
→ **顺序是硬约束：先拉 App → 等首帧 → 之后才截图。**

#### 7.4 OEM 风险清单（都有出处，全部需真机实测）

| 机型/ROM | 现象 | 对策 |
|---|---|---|
| **小米/MIUI** | `INJECT_EVENTS` 被拆成开发者选项「USB 调试(安全设置)」，关着就 `SecurityException` | 根因属性 **`persist.security.adbinput`**，打开或直接查这个值 |
| **三星 One UI** | 启动后把游戏从虚拟屏挪回主屏 | MaaFwApp 的 `ensureAppOnDisplay` 三连：`moveRootTaskToDisplay` → `am display move-stack` → 重投 intent |
| iQOO NEO6 / realme GT Neo5 SE (A14) | `--new-display` 白屏（无 launcher） | `--no-vd-system-decorations --start-app=<app>` |
| 魅族 Flyme / Android 16 | 副屏上 `TOUCH_DOWN` 返回 `injectInputEvent=false` | 只能靠完整元数据日志区分是谁拒的 |
| AYN Odin2 等横屏原生机 | `freezeRotation` 对副屏无效 | 补 `setForcedDisplaySize(vdId,w,h)` |
| Android 12+ | shell 无法建 **secure** 虚拟屏 | DRM/受保护内容必黑。PVZ2 不涉及 |
| Android 15 QPR2 | 有「shell 建虚拟屏被改坏」的回归报告（仅标题级证据，未确证范围） | 待实测 |
| 部分 ROM | `config_supportsMultiDisplay` 被关 → 副屏起 App 整机性失败 | **与我们写不写 binder 无关，必须先测这一项** |

**未找到**「某游戏在虚拟屏上必然黑屏」的实例报告 —— 但**必须实测**。
近似风险：[flutter#172641] VirtualDisplay 内的 SurfaceView 解锁后不可用
（若 PVZ2 用 SurfaceView，配合「熄屏挂机+定时亮屏」要测）。

#### 7.5 为什么识别验证跑到 PC 上做

App 内的两条触发路径现在都不通：
- 点 App 里的按钮 → **App 变前台** → `screencap` 截到 App 自己。
  **这就是会话 5 那批低分的真正原因**，当时误判成「人没停在正确界面」。
- `am startservice` 被 Android 12 挡：`Error: app is in background uid null`。

**绕法**：`screencap` 抓游戏画面 → 复制到 PC → 用 MaaPVZ 自带 Python(numpy) 跑
自己实现的 `TM_CCOEFF_NORMED`（FFT 求交叉项 + 积分图求窗口和，与 OpenCV 同思路）。
脚本：`_probe/ncc_probe.py`。

**已知差异**：灰度而非 app 的 CV_8UC4 四通道 → 数值不会与 app 完全一致，
但对「能不能匹配上」够用。

**遗留**：App 内仍没有「不抢前台的触发方式」。正解是**通知栏操作按钮**或
**悬浮面板按钮**（悬浮窗/通知都不改变前台 App），见 7.6。

#### 7.6 修掉的 bug（三个，都是我自己引入或遗漏的）

**① `coords.json` 静默消失** —— 同步脚本第 1 步复制 coords.json，第 2 步
`Copy-Into` 把**整个 assets/ 删掉重建**，把它抹了。
症状极阴：目录看着都在，只有 `CoordsTable.point()` 会在很久之后抛「坐标不存在」。
已改成遍历 `image/` 的子项逐个复制，不动 assetsDir 本身。

**② `jobs/*.json` 被 git 跟踪** —— `.gitignore` 对**已跟踪**文件无效，
所以那两个 MaaPVZ 作业集从初始提交就一直在仓库里。已 `git rm --cached`（磁盘保留）。

**③ `edit` 工具会弄丢 UTF-8 BOM** —— PowerShell 5.1 随后按 GBK 读，
中文注释直接把解析器搞崩（报「意外的标记 `}`」「字符串缺少终止符」）。
**这是第二次踩**。`tools/*.ps1` 改完必须补 BOM。

#### 7.7 工具链备忘（换机器时不用重新试错）

**GitHub 被 hosts 屏蔽**（`github.com` / `raw.githubusercontent.com` → 127.0.0.1），
但这两条通道可用（已实测）：
```
https://gh-proxy.com/https://raw.githubusercontent.com/<user>/<repo>/<branch>/<path>
https://gh-proxy.com/https://api.github.com/repos/<owner>/<repo>/git/trees/<branch>?recursive=1
AOSP: https://gh-proxy.com/https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/<branch>/<path>
      branch 实测可用：android10-release / android11-release / android14-release
```
`maafw.com` 有 http↔https 重定向循环、取不到；`*.github.io` 可访问；
`cs.android.com` / `android.googlesource.com` 不可达。

**会话工作目录**（已过时，见会话 8）：项目曾叫 `桌面\pvz安卓自动化`，当时为了
让 DSH 会话的工作目录能落到真实项目上，曾把 `桌面\pvz` 做成指向它的**目录联接
（Junction）**。**该联接已在会话 8 连同中文目录一起删除** —— 项目现在就在
`C:\pvz2auto`，不需要任何别名。这里保留记录只是解释历史上为什么会看到那个联接。

#### 验证到哪

| 项 | 状态 |
|---|---|
| **模板匹配（真机画面）** | ✅ **0.9920，决定性验证通过** |
| assets 完整性 | ✅ coords/ocr/jobs/pipeline 全在 |
| 虚拟屏方案可行性 | ✅ 三路源码级确证（MaaFramework / scrcpy / AOSP） |
| 虚拟屏**实际跑通** | ❌ **未做** —— 需要先真机实测 `config_supportsMultiDisplay` |
| 游戏能否在虚拟屏正常渲染 | ❌ **未做** —— 唯一没被源码证明的环节 |
| OCR | ❌ 仍未验证 |
| App 内不抢前台的触发方式 | ❌ 未实现（通知栏按钮 / 悬浮面板按钮） |

---

### 会话 8 — 迁到 ASCII 路径 + 脚本系统起步

#### 8.1 为什么搬家（有硬证据，不是理论）

项目原来叫 `桌面\pvz安卓自动化`。AGP 默认拒绝对含非 ASCII 字符的路径构建。
当时加了 `android.overridePathCheck=true` 试探，结论明确：

| 从哪构建 | `assembleDebug` | `testDebugUnitTest` |
|---|---|---|
| 中文路径 | ✅ 成功 | ❌ **`ClassNotFoundException`** |
| ASCII 联接路径 | ✅ 成功 | ✅ **7/7 通过** |

`.class` 文件明明在 `build/tmp/kotlin-classes/debugUnitTest/` 下，JVM 测试进程就是
加载不到 —— 中文路径卡在类加载器那一层。

**那个开关只放行了编译，没放行工具链。** 所以最终搬到 ASCII 路径，而不是长期
靠开关。`gradle.properties` 里已撤掉该开关并写了原因，别再改回来。

#### 8.2 搬迁：`C:\pvz2auto`

**过程中出过一次半搬事故，值得记住**：

`Move-Item` 对目录是**逐项移动**的，不是原子重命名。它在 Android Studio 占着
文件锁时中断，结果顶层文件（含 `.git` 145 个对象）搬走了，`_toolchain` 被劈成
两半（解压好的在源、压缩包和日志在目标），源里还留了个空 `.git` 壳。

修复方式是 `robocopy $src $dst /MOVE /E` —— 它能**合并**到已存在的目标目录，
而 `Move-Item` 遇到已存在的目标会直接失败。

**教训：大目录搬迁前先关掉 IDE，并且用 robocopy 而不是 Move-Item。**

最终状态：
- 项目在 `C:\pvz2auto`
- `_toolchain` 完整：12831 文件 / 1727.8 MB，`jdk17`、`gradle-8.13`、
  `android-sdk` 三个关键组件都验过存在
- `Desktop\pvz` 那个联接**已删除**（不再需要别名）
- `local.properties` 的 `sdk.dir=D:\sdk`（AS 托管的那份，含 android-35
  + build-tools 35，是从 `_toolchain` 复制过去的）

> ✅ **搬迁后已在新路径验证通过**（会话 9 补跑）：
> ```
> $env:JAVA_HOME="C:\pvz2auto\_toolchain\jdk17\jdk-17.0.20.1+1"
> .\_toolchain\gradle-8.13\gradle-8.13\bin\gradle.bat :app:testDebugUnitTest :app:assembleDebug -PincludeX86=true
> ```
> 结果：`ScriptJsonTest` 7/7 通过、0 失败，`assembleDebug` BUILD SUCCESSFUL，
> APK 193 MB 正常产出（含 x86 native 库）。**forked JVM 的类加载在 ASCII 路径下不再
> 抛 ClassNotFoundException** —— 8.1 那个判断被坐实了。
>
> ⚠️ **踩坑记录：第一次跑 `testDebugUnitTest FROM-CACHE`，等于没验。**
> `org.gradle.caching=true` 让测试任务直接吃了缓存，日志里一行 `FROM-CACHE` 就跳过去了，
> 看起来 BUILD SUCCESSFUL 但 fork 出来的 JVM 根本没执行。要真验证必须强制重跑：
> ```
> gradle.bat :app:testDebugUnitTest -PincludeX86=true --rerun --no-build-cache
> ```
> 只认执行标记：日志里 `:app:testDebugUnitTest` 后面**不带** `UP-TO-DATE` / `FROM-CACHE`
> 才是真跑过；再核对 `app/build/test-results/testDebugUnitTest/*.xml` 的
> `tests="7" failures="0"` 和时间戳。

#### 8.3 脚本系统 · 识别目标库

新包 `com.pvz2.auto.script`。第一层是**内置识别目标库**：

```
app/src/main/assets/recognize/targets.json   ← 数据，你改这个
app/src/main/java/.../script/RecognizeTarget.kt  ← 读它的代码
```

**为什么要有这层**：脚本里不该出现「模板路径 + 阈值 + ROI」。写脚本的人只想
知道「这关打完了没有」。所以把「长什么样」和「怎么用」分开，脚本只写一个 id。

**加一个识别目标 = 两步，不用改 Kotlin**：
1. 把 png 丢进 `assets/`（自己截的放 `assets/recognize/`）
2. 在 `targets.json` 加一条：
```json
{ "id": "my_button", "name": "我的按钮", "type": "template",
  "image": "recognize/我的按钮.png", "roi": [0,0,1280,720], "threshold": 0.8 }
```
`image` 一律写**相对 `assets/` 的完整路径**。

两个设计决定：
- **`roi` 用 `List<Int>` 而不是 `IntArray`**。data class 的自动 equals 对数组是
  按引用比较的，两个内容相同的 IntArray 会被判为不相等 —— 而且不报编译错，
  只在某处突然认不出目标。只在交给 `Recognition` 时才 `toIntArray()`。
- **解析失败整份报错并列出每一处问题**，不静默跳过。静默跳过会表现为
  「脚本跑到那里就不动了」，极难查。

初始 4 条用的是已同步的 MaaPVZ 图片，现在就能用：
`ji_competitive`(create_yard/竞技.png，实测 0.9920) / `endless_entry` /
`endless_settlement` / `endless_out`。

#### 8.4 脚本系统 · 数据模型 + JSON 读写

```
ScriptDef.kt        Script / Action / Condition / Variable + 枚举 + dump()
ScriptJson.kt       String ↔ Script，纯 Kotlin 不依赖 Android
ScriptJsonTest.kt   7 个单测
```

**结构对照自动精灵**（模型是逆向 `D:\自动精灵\zdjl` 的前端 bundle 得到的）：

- 动作全集 P0 七个：**种植物 / 喂能量豆 / 铲植物 / 点击 / 滑动 / 跳转 / 组合动作**
- **「识别」不是动作，而是条件的通用字段** —— 这是关键设计。用户要的
  「这关打完了识别结算再点」= `点击` 动作 + `识别:endless_settlement` 条件，
  不需要单独的识别动作。动作库因此能保持极少，表达力却不受限。
- 每个动作带：`preDelayMs` / `postDelayMs`（`300` 固定 或 `[200,500]` 区间随机）、
  `repeat`、`repeatIntervalMs`、`conditions`、`label`、`children`、
  四个监听钩子 `onBefore/onSuccess/onFail/onAfter`、`enabled`
- `params` 用 `Map<String, Any?>` 而不是每种动作建一个类。动作类型会一直加，
  一类型一类意味着每次改三处。代价是没有编译期键名检查，所以
  `Action.string/int/bool/intList` 在缺键时抛带动作描述的异常。
- 跳转语法抄自动精灵：`3` 第3条 / `+2` 后跳2 / `-1` 前跳1 / `-0` 重跑当前 /
  `:A` 跳标识

**7 个单测全过**（在旧路径跑出来的）。它们抓到过一个真错：我把
`children[0].col` 断言成 2，而那份 json 里写的是 1 —— 是测试写错，代码是对的。
这就是写测试的意义。

#### 8.5 Kotlin 坑：`"$indent脚本"` 编译不过

Kotlin 里**中文是合法的标识符字符**，所以这整串被解析成一个叫 `indent脚本` 的
变量，报 `Unresolved reference`。必须写 `"${indent}脚本"`。

**凡是变量后面紧跟中文，一律加大括号。** 从 C 过来不会有这个直觉。

#### 8.6 执行层故障（与项目无关，但会挡路）

会话后期 shell 反复不可用：

```
Error: spawn C:\WINDOWS\System32\WindowsPowerShell\v1.0\powershell.exe ENOENT
Error: spawn C:\Program Files\PowerShell\7\pwsh.exe ENOENT      ← 后来变成这个
Error: grep subprocess failed (ripgrep provider failure)
```

特点：**文件读写（read/write/edit）正常，但所有需要起子进程的工具全废** ——
shell、grep、git。所以本会话最后**没能 commit / push**，也没能跑构建验证。
（**会话 9 已补上**：新会话里执行层正常，改动已提交推送 `cfed6f9` 并补跑了验证。）

这是 harness 执行层的问题（它想启动的 shell 路径不存在，两次指向不同的
PowerShell），不是项目代码问题。遇到时：重启 DSH Desktop；若仍不行，
检查它配置的 shell 路径。

#### 8.7 验证到哪

| 项 | 状态 |
|---|---|
| 识别层模板匹配（真机画面 0.9920） | ✅ 已验证（会话 7） |
| 识别目标库 `targets.json` + 解析 | ✅ 编译通过、4 张图存在 |
| 脚本数据模型 + JSON 读写 | ✅ 编译通过、7/7 单测（旧路径 + 新路径都过） |
| **搬迁后构建/单测** | ✅ **已验证** —— 7/7 + BUILD SUCCESSFUL，且是 `--rerun` 真跑的（见 8.2 踩坑记录） |
| 脚本执行器 `ScriptRunner` | ❌ 未写（下一步） |
| 虚拟屏 | ❌ 未实现（配方见会话 7） |
| OCR | ❌ 仍未验证 |

### 会话 9 — 补提交会话 8 的搬迁改动 + 搬迁后验证

**做了什么**：把会话 8 遗留的两个文件（`gradle.properties`、`docs/HANDOFF.md`）
提交并推送，然后补跑搬迁后一直没跑的那一步验证。

**提交**：`cfed6f9`，`afe0d6a..cfed6f9  main -> main`。
提交前 `git status --short` 只有两个 `M`，与交接说明一致，没有多余文件被 `add -A` 带进来。

**验证（这次是真跑的）**：

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | ✅ `ScriptJsonTest` 7/7，0 失败 0 错误 |
| `:app:assembleDebug -PincludeX86=true` | ✅ BUILD SUCCESSFUL，APK 193 MB（含 x86） |

**坑（值得记）**：第一次跑出来 `:app:testDebugUnitTest FROM-CACHE` —— Gradle 构建缓存
把测试任务整个短路了，日志上是 BUILD SUCCESSFUL 但 fork 的 JVM 一次都没启动，
等于什么都没验。必须 `--rerun --no-build-cache` 强制重跑，并且核对
`app/build/test-results/testDebugUnitTest/*.xml` 里的 `tests=7 failures=0` 和时间戳。

**结论**：8.1 的判断成立 —— 中文路径确实会破 forked JVM 的类加载，ASCII 路径下正常。
`android.overridePathCheck` 不需要回归。

**验证到哪**：纯本地构建 + 单测，无真机。

---

### 会话 10 — 回读会话 8 的完整记录 + 虚拟屏第一步（建屏 + 拿首帧）

**起点**：把上一会话（`session-c9b51702`）的原始记录整份读了回来（2260 个 zstd 帧、
24 MB、95 条用户消息、70 轮 turn）。**读完才发现会话 9 的方案方向是错的**，因为当时
只有二手摘要，漏掉了 10-18 上午那几轮定案。

#### 10.1 被补回来的四条决定（会话 8 的 10-18 12:00–13:31）

| 问题 | 定案 |
|---|---|
| 引擎怎么办 | **不移植 MaaPVZ 引擎**。改做「自动精灵」式的通用节点脚本系统；`NodeGraph`/`NodeDef`/`PipelineRunner` **逐渐废弃**；**不兼容** MaaPVZ 作业集 |
| 目标形态 | **通用脚本平台**。动作库 7 个（种植物/喂能量豆/铲植物/点击/滑动/跳转/组合动作），其余靠节点通用字段；导出单个 json 发 QQ 给别人导入 |
| 虚拟屏优先级 | **先做虚拟屏**，理由是「这样还不用适配不同分辨率了」—— 它同时解决分辨率适配和占用物理屏两件事 |
| 图片与 ROI | **全部由软件内置封装**，不让用户自己截图。UI 位置固定，配图时直接把 ROI 一起定好 |

会话 10 又追加一条：**目标平台改成 Android 12+**（原定 11），因为要适配当前主流机型。
所以 `AutomationService` 里 `UPSIDE_DOWN_CAKE` 那类分支要保留，11 及以下不再管。

#### 10.2 这一轮做的事：虚拟屏的「建得出来 + 出得了帧」

先只做最小闭环，**不搬 App、不注入**。理由是这两种失败方向完全相反，必须先分开：

1. 拿得到 `displayId` → 权限与 flags 对了；
2. 3 秒内出得了帧 → Surface 通路真的通了（**空屏不出帧是预期，不是失败**）。

改动：

- `IPvz2Service.aidl` 新增 `createVirtualDisplay` / `releaseVirtualDisplay` /
  `getVirtualDisplayId`（事务号 6/7/8）。`Surface` 是 Parcelable，所以**整块通过 Binder
  传给特权侧**，画面在 shell 侧产生、帧在 App 进程消费，中间不落文件、不用 socket。
- 新增 `shizuku/VirtualDisplayFlags.kt`：flags 位定义 + 版本阶梯 + `describe()` 拆位打印。
- 新增 `shizuku/VirtualDisplaySurface.kt`：`ImageReader`(RGBA_8888) 当消费端，
  `acquireLatestImage()` 轮询取「此刻最新帧」，不引线程/回调。
- `Pvz2UserService`：反射调 `DisplayManager.createVirtualDisplay`，
  系统 Context 走 `ActivityThread.systemMain().getSystemContext()`（scrcpy / MaaFwApp 同款）。
- `AutomationService` 新增 `ACTION_VD_PROBE`，`MainActivity` 调试区加按钮。
- `USER_SERVICE_VERSION` 1 → 2（**改了 UserService 就必须提，否则 Shizuku 复用旧实例**）。

#### 10.3 顺手修正了会话 7 的一个数

会话 7 记的 API34+ 总计 **74971 是笔误**。按 AOSP 常量重算：
`459 + 15360 + 49152 = 64971`（`74971 - 64971 = 10000` 不对应任何 flag 位）。
新单测把三档合计钉在 459 / 15360 / 49152、阶梯值 459 / 15819 / 64971，
并断言「任何版本都不含 AUTO_MIRROR(4)」「任何版本都带 PUBLIC(1)」。
真机上再按 logcat 里 `Pvz2UserService` 打出的实际 flags 复核一次。

#### 10.4 验证到哪

| 项 | 状态 |
|---|---|
| `:app:testDebugUnitTest` | ✅ **14/14**（`ScriptJsonTest` 7 + `VirtualDisplayFlagsTest` 7），`--rerun --no-build-cache` 真跑 |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL，**0 警告** |
| 虚拟屏真机建屏 | ✅ **已打通**（MuMu Android 12，见 10.6） |
| 出帧 | ✅ 首帧 1280×720（空屏全黑，预期）；搬游戏与副屏注入 ❌ 未做 |

#### 10.5 悬浮窗与无障碍降级方案全部移除

两条需求，都指向同一件事：**只保留 Shizuku 一条路，所有操作都在界面内。**

> 「不要有悬浮窗，开始、暂停等等操作全在软件里面操作。」
> 「把无障碍的降级方案去掉。」

悬浮窗：虚拟屏方案下游戏跑在副屏，主界面留在前台不遮挡它，所以不需要再叠一层。

删掉的东西：

| 类别 | 删除内容 |
|---|---|
| 悬浮窗 | `overlay/FloatingWindow.kt`、`res/layout/overlay_panel.xml`、`SYSTEM_ALERT_WINDOW` 权限、`ACTION_SHOW_PANEL`、`showPanel()`、状态镜像、主界面第 2 步与「显示悬浮面板」按钮 |
| 无障碍降级 | `fallback/Pvz2AccessibilityService.kt`、`fallback/MediaProjectionCapturer.kt`、`res/xml/accessibility_service_config.xml`、manifest 里的 accessibility service 声明、`FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限、`ACTION_START_FALLBACK`、`runFallbackLoop()`、`realScreenSize()`、`startFallback()`、MediaProjection 授权回调、主界面的「启动（MediaProjection 降级）」按钮 |

连带简化：`startForegroundCompat()` 不再带 `mediaProjection` 布尔参数，固定
`FOREGROUND_SERVICE_TYPE_SPECIAL_USE`；manifest 的 `foregroundServiceType` 只剩
`specialUse`。准备清单从 4 步 → 3 步 → **2 步**（Shizuku、通知）。

#### 10.6 ★ 虚拟屏真正打通：连续四个坑，根因各不相同

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `Can't create handler inside thread Thread[Binder:xxx]` | `ActivityThread.systemMain()` 内部要 `new Handler`，而 Binder 线程没有 Looper | 初始化挪到专用线程并先 `Looper.prepare()` |
| 2 | `SecurityException: packageName must match the calling uid` | 用系统 Context（包名 `android`）；服务端用 `validatePackageName(callingUid, packageName)` 校验「名字必须挂在这个 uid 名下」 | 改用 `ContextWrapper(null)` 覆写 `getPackageName()`，返回**属于本 uid** 的包名 |
| 3 | `NoSuchMethodException`（App 进程内建屏时） | **隐藏 API 限制**：App 进程里反射 `@hide` 的 `DisplayManager.<init>` 被拦 | App 自建这条路在 API 32 上走不通，必须走 shell 侧 |
| 4 | `ClassCastException: VirtualDisplay cannot be cast to Display` | 两者是不同类：释放要 `VirtualDisplay.release()`，读尺寸要 `Display` | `Created` 同时持有两者 |

**最重要的环境结论（会反复踩）**：

> **Shizuku 以 root(uid 0) 运行时，虚拟屏永远建不出来。**
> `pm list packages --uid 0` 在 MuMu 上返回**空** —— uid 0 名下没有任何包，
> 于是 `validatePackageName` 无论传什么名字都必然失败。这不是代码能绕的。
>
> 正解：让 Shizuku 跑成 shell(uid 2000)。MuMu 上的具体做法（脚本上一会话已备好）：
> ```powershell
> # 1) 杀掉 root 的 server。注意 su -c "pkill -f x" 里的 -f 会被 su 自己吃掉，
> #    要么改用 PID，要么把引号写成 su -c '...' 的嵌套形式
> adb shell "su -c 'kill -9 <root_pid>'"
> # 2) 以 adb shell 身份启动 -> server 就是 uid 2000
> adb shell "nohup sh /data/local/tmp/start_server.sh > /data/local/tmp/shizuku.log 2>&1 &"
> adb shell "ps -A -o USER,PID,NAME | grep shizuku"   # 应显示 shell ... shizuku_server
> ```
> 真机无 root 走的本来就是 ADB/无线调试，所以**这条就是生产路径**。

**实测数据（MuMu，Android 12 / SDK 32）**：

- `flags = 0x1CB = 459` 就够（`PUBLIC+PRESENTATION+OWN_CONTENT_ONLY+SUPPORTS_TOUCH+
  ROTATES_WITH_CONTENT+DESTROY_CONTENT_ON_REMOVAL`）；
  **API 32 不需要 TRUSTED** —— 10.3 那条版本阶梯在 Android 12 上只用得到基础集。
- 建屏返回 `displayId`，`Display.mode = 1280x720`。
- 首帧在 **约 110–140 ms** 内到达；帧内容 1280×720 **全黑**
  （空屏无内容渲染，符合预期：平均亮度 0.0、采样内只有 1 种颜色）。

**新增诊断入口** `shizuku/VdProbeMain.kt`：用 `app_process` 直接在设备上建一次屏，
完全绕开 App 与 Shizuku。当「建不出屏」时用它把「系统/ROM 允许不允许」这一层单独摘出来：

```powershell
$apk = (& adb shell pm path com.pvz2.auto).Replace("package:","").Trim()
adb shell "CLASSPATH=$apk app_process /system/bin com.pvz2.auto.shizuku.VdProbeMain"
```

#### 10.7 验证到哪（会话 10 收尾）

| 项 | 状态 |
|---|---|
| 单测 | ✅ 14/14（`ScriptJsonTest` 7 + `VirtualDisplayFlagsTest` 7），`--rerun --no-build-cache` 真跑 |
| 编译 | ✅ 0 警告 |
| **虚拟屏建屏（shell 身份）** | ✅ **真机实测**，`displayId=4/5`，1280×720 |
| **虚拟屏出帧** | ✅ 首帧 1280×720，ImageReader 通路打通 |
| 界面 | ✅ 准备工作 2/2；无悬浮窗、无无障碍、无降级按钮 |
| 把游戏搬到副屏 | ✅ **已通**（见 10.8） |
| 副屏定向注入 | ⚠️ **代码就位但被焦点挡住**（见 10.8） |
| App 自建屏 | ❌ **走不通**（隐藏 API），已确认无需再试 |

#### 10.8 第二步通了，第三步卡在「屏上没有可触摸窗口」

**① 把游戏搬到副屏 —— 通了**

```powershell
# 先解析 launcher activity，再用 --display 启动
cmd package resolve-activity --brief -c android.intent.category.LAUNCHER com.popcap.pvz2cthdop
am start --display <displayId> -n <component>
```

判据是**帧亮度**：空屏 `0.0`，启动后 **7.12** —— 游戏确实渲染到这块屏上了。

`am display move-stack 0 <id>` 在 Android 12 上**不可用**（`Unknown rootTaskId=0`），
它要的是真实 taskId。这条路留作候选，但 `am start --display` 已经够用。

**② 定向注入 —— 卡住了，但原因很具体**

代码已经就位：`DisplayInputInjector`（`injectInputEvent` + 反射 `setDisplayId`）、
AIDL 的 `tapOnDisplay` / `swipeOnDisplay`。排查过程中确认两件事：

- **必须设 `event.source = SOURCE_TOUCHSCREEN`**。`MotionEvent.obtain()` 造出来的事件
  source 是 0，InputDispatcher 无法据 source 路由，注入直接返回 false。
  （这个坑很误导：日志只说「没有可触摸窗口」，看着像窗口问题。）
- 方法名是 `recycle()` 不是 `release()` —— `InputEvent` 上没有后者。

但即使 source 和 displayId 都对了，事件仍被丢弃：

```
InputDispatcher: Dropping event because there is no touchable window
                 or gesture monitor at (640, 360) in display 12.
```

**关键判断**：`input -d <id> tap` 打过去**同样被丢弃**（它不报错，但日志里一样是 drop）。
所以这不是我们构造事件的问题，而是**那块屏上确实没有可触摸窗口**。

**③ 焦点是唯一的钥匙，但它的表现不稳定**

给 flags 加上 `OWN_FOCUS`(16384) 之后，**有一次**观察到游戏窗口真的拿到了焦点：

```
mCurrentFocus=Window{7148526 u0 com.popcap.pvz2cthdop/...SexyAppActivity}
```

但下一次运行焦点又落回宿主桌面（`app.lawnchair`），游戏任务在副屏上却没有输入焦点。
所以「加 OWN_FOCUS」这个方向是对的，还缺**稳定把任务变成该屏顶层**的那一步。

**下一步三条探针（按把握排序）**：

1. 用**真实 taskId** 搬：`dumpsys activity recents | grep pvz2` 取 taskId →
   `am display move-stack <taskId> <displayId>`（而不是 `0`）。
2. `am start --display <id> --activity-clear-task -S -n <component>`，强制新任务落在该屏顶层。
3. 核实 Android 12 是否真的支持 `OWN_FOCUS`（16384 在 S 上可能是未知位被直接忽略；
   若如此，就得靠 `ActivityTaskManager` 把任务移过去并让系统重新分配焦点）。

调试入口：`VdProbeMain` 现在支持 `--tap`（对照实验：自建事件 vs `input -d`）
和 `--own-focus`（额外带 OWN_FOCUS 位），改一行就能重跑，不必重启 App。

---

## 未验证项与已知风险

### 🔴 高优先级

1. **OCR 从未在真机跑过。** `OcrEngine` 是手写 ONNX 推理，三处都可能错：
   - `keys.txt` 是 CRLF（已剥 `\r`），但**类别数对齐**（18385 vs 18383）未实测；
   - CTC 解码的 blank / 重复字符处理；
   - PP-OCR 的 NCHW 布局与归一化参数。
   **若模板匹配正常但 OCR 全灭，问题一定在这三处。**

2. **识别分数 —— 已解决（会话 7）。** 模板匹配在真实游戏画面上
   `create_yard/竞技.png` = **0.9920**，远高于 0.8 阈值。
   会话 5 那批低分是**方法错误**（App 抢了前台，截到的是 App 自己），已作废。

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

**状态**：识别层已验证通过；虚拟屏方案已完成调研、尚未实现。
不再卡在「无设备」—— 本会话有设备（MuMu）且脚本环境已恢复。

### 技术债，按优先级

| # | 事项 | 为什么这个顺序 | 依赖 |
|---|---|---|---|
| **1** | **`TouchInjector` 改成 DOWN/UP + 可控最短按住** | 见 7.3①。**现有前台路径同样有丢点击风险**，且表现是「偶尔点不中」——最难查。纯本地改动，不需要虚拟屏、不需要真机 | 无 |
| **2** | **App 内「不抢前台」的触发方式**（通知栏操作按钮，或悬浮面板按钮） | 见 7.5。现在识别自检/干跑只能靠点 App 按钮，一点就抢前台 → 截到 App 自己。没有这个，App 内的识别链路**永远验不了** | 无 |
| 3 | 虚拟屏（方案 B）实现 | 配方齐全（7.2），但先做 1、2 才有意义 —— 没有 2 就无法在 App 内验证 | 真机实测 |
| 4 | `screencap` 加 `-d` + 换裸格式 | 同时是现有路径的性能优化（省 PNG 编解码） | 3 |
| 5 | OCR 验证 | 仍未跑过一次（见「已知风险」第 1 条） | 2 |

### 虚拟屏落地前必须先测的（顺序有讲究）

```powershell
# ① 最关键：目标机型的 config_supportsMultiDisplay 有没有被 OEM 关掉
adb shell dumpsys activity settings | findstr -i multidisplay
#    若为 false，副屏起 App 会整机性失败 —— 与我们写不写 binder 无关，早发现早换路

# ② 用现成 scrcpy 验证整条架构（零代码，需要 scrcpy 3.x）
scrcpy --new-display=1280x720 --no-vd-system-decorations --start-app=<游戏包名>

# ③ 确认 screencap -d 对虚拟屏有效
adb shell dumpsys SurfaceFlinger --display-id    # 拿物理 id
adb shell screencap -d <物理id> -p /sdcard/vd.png

# ④ 确认注入能到副屏（注意 -d 在子命令之前）
adb shell input -d <逻辑id> tap 640 360

# ⑤ 顺带验证权限推断：去掉 TRUSTED flag 是否被拒（API 33+）
```

**先在 MuMu（Android 12）上跑通再上真机** —— 出问题好排查。

### 现在就能验证识别（不需要 App）

```powershell
adb shell screencap -p /sdcard/f.png
adb pull /sdcard/f.png _probe\vframe.png
& "C:\Users\29374\Desktop\MaaPVZ-win-x86_64-v0.5.4-ci.261001-670c36e\python\python.exe" `
    _probe\ncc_probe.py _probe\vframe.png
```
脚本在 `_probe/ncc_probe.py`（不在版本库里）。

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
| `5bc4f22` | fix(assets): mirror MaaPVZ resource/image at the assets root so pipeline paths resolve（会话 5） |
| `7fa9fb4` | fix(coords): follow the game's own screen-offset algorithm; rework the UI（会话 6） |
| `c78af39` | style(ui): ground the palette in the game's own world; make the setup a checklist（会话 6） |
| `2bb0352` | fix(sync): stop the image copy from deleting coords.json; untrack the job set（会话 7） |
| `61b1877` | docs: record the virtual-display research and the first real recognition check（会话 7） |
| `72ed566` | build: allow the non-ASCII project path, now that it is verified to work（会话 8 已撤销该改动） |
| `1bb575b` | feat(script): built-in recognition-target library, so scripts refer to names（会话 8） |
| `afe0d6a` | feat(script): script data model and json codec, with unit tests（会话 8） |
| `cfed6f9` | chore: move the project to `C:\pvz2auto` and drop the path-check override（会话 8 的改动，会话 9 补提交并推送） |
| `git log -1` | docs: mark the post-move build as verified, and record the FROM-CACHE trap（会话 9；本条即本文件自身，哈希不写死） |
| `git log -1` | feat(vd): create a 720p virtual display from the shell side and consume its frames（会话 10；同上，哈希不写死） |

> **会话 8 那批改动已由会话 9 提交并推送** —— `afe0d6a..cfed6f9  main -> main`。
> 当时因为执行层坏掉（见 8.6）没能 commit，所以这批是跨会话补的。
> `72ed566` 加的 `android.overridePathCheck` 也随之**撤掉**了：ASCII 路径下不需要它，
> 而且它只放行编译、不放行 forked JVM 的类加载（见 8.1）。
> 推送后在新路径补跑了单测和构建，7/7 通过（见 8.2）。

> **注意**：`app/src/main/assets/` 下的 `coords.json`、`ocr/`、`jobs/`、`pipeline/`
> 以及所有游戏模板图目录都在 `.gitignore` 里（游戏素材版权 + 模型体积），
> **只保留在本机，不入库**。换机器必须重跑
> `tools/sync-maapvz-assets.ps1 -MaaPvzRoot "<MaaPVZ 路径>"`。
>
> 同步脚本改完**必须确认 UTF-8 BOM 还在**（`edit` 工具会弄丢，PowerShell 5.1
> 按 GBK 读会把中文注释读崩，报「意外的标记」）。
