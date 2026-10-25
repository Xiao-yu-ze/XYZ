package com.pvz2.auto.zjs

/**
 * 执行自动精灵脚本。
 *
 * ## 只做能做的，剩下的明确跳过
 *
 * 26 种动作里有一半在安卓上没法实现（多指手势、JS、变量、加密动作……）。
 * 遇到这些**不静默跳过**，而是记一条"跳过原因"—— 用户的脚本
 * 跑到一半没反应，至少能在日志里看到是哪一条、为什么。
 *
 * ## 执行模型
 *
 * 顺序执行 + 三种控制流：
 *  - `repeatCount` 重复（0 = 无限）
 *  - `jumpId` / `jumpTo` 跳转
 *  - `condition` 条件（不满足就跳过这条）
 *
 * 没有并行 —— 那是后面要加的自己的节点类型，不在协议里。
 *
 * ## 为什么把注入抽成接口
 *
 * 执行逻辑（重复、跳转、条件）是最容易写错的部分，而它和"点一下屏幕"
 * 完全解耦。抽出来以后可以在 JVM 上跑单元测试，不用真机。
 */
class ZExecutor(
    private val inject: Injector,
    private val screen: ZPosResolver.Screen,
    private val matcher: Matcher,
    private val log: (String) -> Unit = {}
) {

    /**
     * 给 JS 引擎准备宿主能力。
     *
     * 只传本机真有的那几样：变量表、日志、持久化存储、屏幕信息、注入、剪贴板。
     * 没传的能力在脚本里调用会明确报"本机未实现"（见 JsEngine.UNSUPPORTED）。
     */
    private fun jsEnv(): JsEngine.Env = JsEngine.Env(
        vars = scriptVars,
        log = { line -> log(line) },
        storageGet = jsStorage?.get?.let { g -> { k: String -> g(k) } },
        storageSet = jsStorage?.set,
        storageRemove = jsStorage?.remove,
        deviceInfo = {
            mapOf(
                "width" to screen.width,
                "height" to screen.height,
                "screenWidth" to screen.width,
                "screenHeight" to screen.height,
                "density" to screen.density,
                "densityDpi" to (screen.density * 160).toInt(),
                "clientType" to "android"
            )
        },
        tap = { x, y, hold -> inject.tap(x, y, hold) },
        swipe = { x1, y1, x2, y2, dur -> inject.swipe(x1, y1, x2, y2, dur) },
        clipboardGet = { jsClipboard?.get?.invoke() },
        clipboardSet = { text -> jsClipboard?.set?.invoke(text) ?: false },
        toast = jsToast
    )

    /**
     * 读另一个脚本（「执行脚本」动作用）。null = 通道未接（单测里就是 null）。
     *
     * 走回调而不是直接依赖 ZjsRunner：执行器不认识 Android 的 Context，
     * 也就能在没有文件系统的单测里跑。和 appLauncher/toaster 是同一个套路。
     */
    var scriptLoader: ((String) -> Z.Script?)? = null

    /**
     * 点运行时用户在弹窗里填的值（对应变量的 `value.showInput`）。
     *
     * 自动精灵的语义：勾了"从键盘输入"的变量，值来自这个弹窗；
     * 弹窗里留空且不是必填 → **变量被删掉**（`zdjl.VAR_DELETE_MARK`）。
     */
    var initialInputs: Map<String, String> = emptyMap()

    /** JS 的持久化存储（zdjl.getStorage/setStorage）。null = 未接通道 */
    var jsStorage: JsStorage? = null

    /** JS 的剪贴板通道。 */
    var jsClipboard: JsClipboard? = null

    /** JS 的 toast 通道。 */
    var jsToast: ((String) -> Unit)? = null

    /** 持久化存储接口（跨运保留：关卡数这类用得上）。 */
    interface JsStorage {
        val get: (String) -> String?
        val set: (String, String) -> Unit
        val remove: (String) -> Unit
    }

    interface JsClipboard {
        val get: () -> String?
        val set: (String) -> Boolean
    }

    /** 一次「设置变量」的交互弹窗请求。 */
    data class AskDialog(
        val title: String,
        val okText: String,
        val cancelText: String,
        /** 自动点什么（"ok"/"cancel"），空 = 不自动 */
        val autoBtn: String = "",
        /** 自动点击的延时（毫秒） */
        val autoDelayMs: Int = 3000
    )

    /** 注入接口。真机实现走 Shizuku，测试实现记录调用。 */
    interface Injector {
        fun tap(x: Int, y: Int, holdMs: Int)
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
        fun key(keyCode: Int, holdMs: Int)
        fun inputText(text: String)

    /**
     * 「设置变量」的交互弹窗（协议 `dialogTitle` / `dialogOKText` / `dialogCancelText` /
     * `dialogAutoClickBtn` / `dialogShowOnce`，FIELDS 576-580）。
     *
     * @return 0=点了确定 / 1=点了取消 / null=本机没有弹窗通道
     */
    fun askDialog(req: AskDialog): Int? = null

    /**
     * 写剪贴板（「识别屏幕」默认结果模式就是这个）。
     *
     * 默认返回 false = 这条通道没接（单测里的假注入器不用管）。
     * 真机实现走 Android 的 ClipboardManager。
     */
    fun setClipboard(text: String): Boolean = false

        /**
         * 多指同时滑动。
         *
         * ## 为什么是"同时"而不是逐条调 swipe
         *
         * 多指手势的语义就是同时 —— 两指缩放、三指下滑，逐条调 swipe
         * 会变成"先滑一根再滑另一根"，完全不是一回事。
         *
         * 默认实现退回逐条 swipe，这样测试用的假注入器不用实现它；
         * 真机实现要覆盖成真正的并发注入。
         */
        fun multiSwipe(paths: List<Pair<ZPosResolver.Hit, ZPosResolver.Hit>>, durationMs: Int) {
            paths.forEach { (a, b) -> swipe(a.x, a.y, b.x, b.y, durationMs) }
        }

        /**
         * 「打开应用」的通道（协议 10.13）。返回 `(包名) -> 是否成功`。
         *
         * 默认 null = **本机没接这条通道**。执行器据此报"本机无法打开应用"，
         * 而不是让这条动作落进"还没实现"——那两种情况的排期完全不同，
         * 但对用户来说都表现为"脚本没打开游戏"，所以必须区分。
         */
        fun appLauncher(): ((String) -> Boolean)? = null

        /** 「打开链接」的通道（协议 10.14）。语义同 [appLauncher]。 */
        fun linkOpener(): ((String) -> Boolean)? = null

        /** 「系统提示」的轻提示通道（协议 10.23 的 toast 部分）。语义同 [appLauncher]。 */
        fun toaster(): ((String) -> Unit)? = null
    }

    /** 识别接口。图片/文字/颜色匹配都走这里。 */
    interface Matcher {
        /** 在 [rect] 范围内找模板图，返回所有命中的左上角。 */
        fun findImage(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit>

        /** 找文字（OCR）。 */
        fun findText(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit>

        /** 找颜色。 */
        fun findColor(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit>

        /** 找节点（无障碍）。 */
        fun findNode(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit>

        /**
         * 上一次识别的**细节**，只写进运行日志：范围/阈值/命中数/最佳分/耗时。
         * 不参与判定。默认 null = 这个匹配器不提供。
         */
        fun lastDetail(): String? = null

        /**
         * 把当前画面存成 PNG（失败现场，对应 Maa 的 debug/on_error）。
         * @return 存下来的路径；不支持返回 null。
         */
        fun saveFrame(dir: String, baseName: String): String? = null

        /**
         * 把 [rect] 里**所有**文字识别出来（「识别屏幕」动作用）。
         *
         * 和 [findText] 的区别：那里是「找一句指定的话、返回位置」，
         * 这里是「把看到的字都读出来」—— 要的是文本本身，不是坐标。
         * 默认返回空表示这个匹配器做不到（单测里的假实现就不用管它）。
         */
        fun ocrTexts(rect: IntArray): List<String> = emptyList()

        /**
         * 上一次查找没命中的**具体原因**。null = 就是没找到。
         *
         * 区分这两件事很重要：「没找到」该去调阈值或换模板；
         * 「我们做不了」（比如节点查找、OCR 模型缺失）调什么都没用。
         * 混成一句"没有匹配到目标"，用户会把时间花在错的方向上。
         */
        fun lastReason(): String? = null
    }

    /** 一次执行的结果。 */
    data class Result(
        val executed: Int,
        val skipped: List<Skip>,
        val error: String? = null
    ) {
        data class Skip(val index: Int, val label: String, val reason: String)
    }

    /** 运行状态，外部可以中断。 */
    /**
     * 停止标志。
     *
     * ★ 必须 @Volatile：写入方是主线程（AutomationService 的 onStartCommand），
     *   读取方是 Dispatchers.IO 上的脚本线程。没有 volatile 的话 JMM 不保证
     *   可见性，JIT 甚至可以把 `while (!stopped)` 的读提到循环外 ——
     *   表现就是"点了停止没反应，只能杀进程"。
     */
    /** Maa 的 pre_delay / post_delay 默认值：没写 delay 时按这个走。 */
    private val DEFAULT_DELAY_MS = 200

    @Volatile
    private var stopped = false

    fun stop() {
        stopped = true
    }

    /**
     * 脚本级的默认等待（`baseInfo.delay`）与它的单位，`run()` 里填。
     *
     * 协议（FIELDS.md:113）：动作没写 `delay` 时**先**取脚本级的 `baseInfo.delay`，
     * 两者都没有才缺省 1000ms。以前只有硬编码的 200ms —— 作者把全局节奏设成 3 秒，
     * 我们照样 200ms 一个动作，界面还没打开就开始点，后面每一步都点在过渡画面上。
     */
    private var scriptBaseDelay: String? = null
    private var scriptBaseDelayUnit: Z.TimeUnit = Z.TimeUnit.SECONDS

    /**
     * 「等待」动作（controlRun:waitDelay）没写 delay 时的兜底。
     *
     * 协议里 `delay` 是这个动作**唯一**的时长来源（FIELDS.md:559）—— 没有它，
     * "等待"就等于一个空操作。以前这里传 0，用户放一条"等 2 秒"根本不生效，
     * 而动作在列表里看起来清清楚楚。
     */
    private val DEFAULT_WAIT_ACTION_MS = 1000

    /** 已经报过"监听动作不执行"的动作下标。避免无限循环里每轮都刷一条。 */
    private val warnedCallbackActions = HashSet<Int>()

    /**
     * 无限重复（`repeatCount = 0` 且能退出）时的**强制最小间隔**。
     *
     * `fastRepeatMode` 的后续轮不做识别，只剩一次 `inject.tap`；如果脚本又把
     * `delay` 写成 0，这里就会变成每秒数百次的连点空转 —— 游戏侧只会把它当成
     * 一次点击，而 CPU/注入通道全被打满。
     */
    private val MIN_INFINITE_GAP_MS = 20

    /**
     * 当前嵌套在「运行多个动作」子列表里的层数。0 = 顶层脚本。
     *
     * 只有一个用途：`ContinueParentExecute`（协议 10.21）要区分"有父可回"和"顶层"。
     */
    private var subShellDepth = 0

    /** 子列表嵌套上限。协议没有这一条，纯粹防止脚本自引用成无限递归。 */
    /** 子列表内 `&` 跳转的次数上限：防止 `&-1` 这种写法变成死循环。 */
    private val MAX_SUB_JUMPS = 10000

    /** 条件嵌套层数上限（畸形文件防栈溢出）。 */
    private val MAX_COND_DEPTH = 8

    /** 周围条件挂在位置对象上的键名（协议：posData.filterByAroundConditionSet）。 */
    private val AROUND_KEY = "filterByAroundConditionSet"

    /** 周围条件里允许出现的子条件类型（自动精灵 getSupportItemConditionTypes()）。 */
    private val AROUND_SUB_TYPES = setOf(
        Z.ConditionType.IMAGE, Z.ConditionType.TEXT,
        Z.ConditionType.COLOR, Z.ConditionType.NODE
    )

    private val MAX_SUB_SHELL_DEPTH = 8

    /**
     * 跑完整个脚本。
     *
     * @param maxSteps 步数上限，防止 `重复次数=0` + 无条件跳转变成死循环
     *        （那种脚本会一直转，用户只能强杀应用）
     */
    fun run(script: Z.Script, maxSteps: Int = 100_000): Result {
        val actions = script.actions
        if (actions.isEmpty()) return Result(0, emptyList(), "脚本里没有动作")

        // ★ 不要在这里重置 stopped。
        //   stop() 可能比 run() 更早到达（RUN 后立刻 STOP），
        //   重置等于把那次停止吞掉。每个 run 用新的执行器实例，默认就是 false。
        val skipped = ArrayList<Result.Skip>()
        var executed = 0
        var steps = 0
        // 「设置变量」写入的运行时变量：每次 run 从空开始（协议里变量属于一次运行）
        scriptVars.clear()
        // 弹窗里填的值就是这些变量的初值（自动精灵里"从键盘输入"就是这个意思）
        initialInputs.forEach { (k, v) -> if (v.isNotEmpty()) scriptVars[k] = v }
        runStartedAt = System.currentTimeMillis()
        runActions = actions
        com.pvz2.auto.core.ScriptRunState.requestResume()

        // jumpId → 下标，跳转时用
        val jumpTable = HashMap<String, Int>()
        /*
         * 跳转表按协议建：
         *  - 只收**启用**的动作（官方 `if(!t||t.disabled) return !1`）——
         *    跳到一条禁用动作上，官方是跳过它，我们原来会白执行一次；
         *  - 子动作（运行多个动作里）上的标识**记下来**，好给出准确的报错，
         *    而不是笼统的"目标不存在"（跳进子列表本机还不支持）。
         */
        run {
            fun collect(list: List<Z.Action>, topLevel: Boolean) {
                list.forEachIndexed { i, a ->
                    val id = a.common.jumpId
                    if (!id.isNullOrBlank()) {
                        when {
                            !topLevel -> subJumpIds.add(id)
                            a.common.disabled -> Unit
                            else -> jumpTable[id] = i
                        }
                    }
                    if (a.scriptSet.isNotEmpty()) collect(a.scriptSet, topLevel = false)
                }
            }
            collect(actions, topLevel = true)
        }
        // resolveJump 要用这张表（同一个对象，后面不会再被替换）
        jumpTableOf = jumpTable

        /*
         * 脚本级重复次数。
         *
         * ★ 0 是"无限"（协议），**负数也是无限** —— 手写脚本里用 -1 表示一直循环
         *   （常规四脚僵王无尽 心塔 1.3.zjs 的 baseInfo 就是 repeatCount: -1）。
         *   原来只判了 0，于是 -1 落进 `loop(0) >= -1` 直接 break：
         *   脚本"点运行没反应"，日志里也只有"读到 5 个动作"。
         */
        val scriptRepeat = script.baseInfo.repeatCount ?: 1
        val scriptInfinite = scriptRepeat <= 0
        // 脚本级默认等待：动作没写 delay 时按它走（见 scriptBaseDelay 的注释）
        scriptBaseDelay = script.baseInfo.delay?.takeIf { it.isNotBlank() }
        scriptBaseDelayUnit = script.baseInfo.delayUnit
        var loop = 0

        /*
         * 节点耗时用的计数器。
         *
         * recognizeMs 只累加"真正去画面里找"的时间（不含等待间隔），
         * 这样"这条为什么慢"能分清是识别慢还是单纯在等画面。
         */

        while (!stopped) {
            // 0 与负数都是无限
            if (!scriptInfinite && loop >= scriptRepeat) break
            loop++

            // 每次「整体重复」开始时重置「限制运行次数」的计数：
            // 协议 resetTime=afterRepeat 表示每轮从头算；afterFinish / onLoad
            // 在本机等价于「一次 run 内有效」，由 run() 开头的 clear 覆盖。
            limitUsed.clear()
            intervalLast.clear()

            var pc = 0
            while (pc < actions.size && !stopped) {
                if (++steps > maxSteps) {
                    return Result(executed, skipped, "执行步数超过 $maxSteps，可能有死循环")
                }

                val rawAction = actions[pc]
                val label = ZScriptIO.actionLabel(rawAction)

                /*
                 * ★ 每条动作先打一行头。
                 *
                 *   排查"哪一步出的问题"时，没有这一行就只能在几百行日志里
                 *   猜哪段属于哪条动作。带上序号/类型/节点名，配合下面
                 *   每步的实测值，就能直接定位到"哪个字段算错了"。
                 */
                resetRecognizeStats()
                val nodeStartedAt = System.currentTimeMillis()
                log("── [${pc + 1}/${actions.size}] ${rawAction.type.wire} · $label")

                /*
                 * ★ __vars：字段被变量化时，自动精灵落盘会**把原字段删掉**
                 *   （FIELDS.md:127-133）。所以我们读到的"字段不存在"有两种完全
                 *   不同的含义：用户没写 / 它是变量。
                 *
                 *   以前一律按前者处理：`delay` 是变量就按 200ms 跑、`duration`
                 *   是变量就按 100ms —— 脚本节奏全错，而脚本在界面上看起来一切正常。
                 *   这里先把能代换的字面量代换掉；代换不了的**明确跳过这一条**，
                 *   绝不静默套默认值。
                 */
                val varsApplied = applyLiteralVars(rawAction)
                if (varsApplied.blockedField != null) {
                    val field = varsApplied.blockedField
                    val why = "__vars：字段「$field」由变量定义，本机只能代换字面量，无法求值"
                    skipped.add(Result.Skip(pc, label, why))
                    log("[跳过] $why")
                    pc++
                    continue
                }
                // 运行时变量（「设置变量」写的）→ `${名字}` 代换进查找文字/输入内容
        val action = applyScriptVars(varsApplied.action)

                /*
                 * 条件。
                 *
                 * ★ 这里落实四个字段：识别次数、识别间隔、先等待再检查、取反。
                 *
                 *   以前只判一次就跳过 —— 那四个字段在模型和界面里都有，
                 *   执行器却从没读过，等于纯装饰：用户把「识别次数」设成 50，
                 *   行为和 1 完全一样。这种"设了不生效"比字段不存在更伤人。
                 *
                 * 顺序（FIELDS.md:259）：
                 *   checkBeforeDelay = true  → 先检查，通过后再等 delay
                 *   checkBeforeDelay = false → 先等 delay，再检查
                 */
                // 先上报"跑到哪一条"，再做条件判断。
                //
                // ★ 顺序很重要：条件可能无限重试（等到画面出现），
                //   如果等条件通过了才上报，那整个等待期间界面是静止的 ——
                //   用户看到的就是"卡在第一个节点"。
                com.pvz2.auto.core.ScriptRunState.enterAction(pc, label, estimateMs(action))

                /*
                 * 禁用 / 条件（含识别次数与"先等待再检查"）/ 前置等待
                 * 已经统一收进 executeWithRepeat ——
                 * 「运行多个动作」的子动作和监听槽位里的动作同样是完整动作，
                 * 它们的这几个字段必须一样生效。
                 */

                // 执行（含重复）
                abortReason = null
                val outcome = executeWithRepeat(action, pc, label, skipped)
                // 「动作运行状态」条件要查别的动作跑成功没有 —— 这里记一笔。
                // stop/jump 也算成功（它们确实按意图执行了）。
                actionOutcome[pc] = outcome.abort == null && !outcome.condSkipped
                com.pvz2.auto.core.ScriptRunState.finishAction(pc)

                /*
                 * ★ 识别不到 → 任务结束（Maa 语义）。
                 *
                 *   不是"跳过这一条继续"：那会让整条脚本在几秒内被冲完，
                 *   界面上还显示"跑完了 N 条"，实际一步都没做。
                 *   报错要带上"第几条、找什么、为什么没找到"，
                 *   用户才能判断是阈值、模板还是界面不对。
                 */
                if (outcome.abort != null) {
                    /*
                     * pauseOnFail（FIELDS.md:330，默认 true）。
                     *
                     * 协议里失败是"**暂停**，等用户决定继续/停止"。本机没有
                     * "暂停-继续"通道（停了就只能重跑），所以这里按停止处理，
                     * 但必须**写明白**：否则用户看到"任务结束"，会以为脚本自己跑完了，
                     * 而不是"它卡在这一步等你"。
                     */
                    if (script.baseInfo.pauseOnFail) {
                        val pmsg = "pauseOnFail=true：本机未实现暂停-继续，按停止处理"
                        skipped.add(Result.Skip(pc, label, pmsg))
                        log("[暂停] $pmsg")
                    } else {
                        log("[任务结束] pauseOnFail=false，失败即结束（不再暂停）")
                    }
                    log("[任务结束] ${outcome.abort}")
                    return Result(executed, skipped, outcome.abort)
                }
                if (outcome.jumpTo != null) {
                    /*
                     * ★ 跳转结果必须分**三种**，不能都用 null 表示"跳不了"。
                     *
                     *   `&1`（子动作列表内跳转）是协议里的写法、我们没实现；
                     *   `:a` 查不到 / 序号越界才是"目标不存在"。
                     *   以前两者都返回 null → 上层一律当"目标不存在"终止脚本 ——
                     *   用户按"不存在"去改脚本，改多久都不对（我们其实根本不支持）。
                     */
                    when (val resolved = resolveJump(outcome.jumpTo!!, pc, actions.size)) {
                        is JumpResult.Found -> {
                            pc = resolved.index
                            log(
                                "   ↳ 完成 [${pc + 1}] 用时 ${System.currentTimeMillis() - nodeStartedAt}ms" +
                                    "（识别 ${recognizeMs}ms × ${recognizeCount} 次）→ 跳转"
                            )
                            continue
                        }

                        is JumpResult.Unsupported -> {
                            skipped.add(Result.Skip(pc, label, resolved.message))
                            log("[跳转] ${resolved.message}，停在这一条")
                            finishRunLog(executed, skipped, resolved.message)
            return Result(executed, skipped, resolved.message)
                        }

                        is JumpResult.NotFound -> {
                            /*
                             * ★ 找不到目标时**停在原地**，不要把 pc 推到末尾。
                             *
                             *   推到末尾 = 整条脚本静默结束，界面上只有一条跳过原因 ——
                             *   用户看到的是"脚本跑完了但什么都没做"，最难查。
                             *   停在原地至少让"停止"和"往下看"都还可控，
                             *   而且这条 skip 会留在结果里。
                             */
                            skipped.add(Result.Skip(pc, label, resolved.message))
                            log("[跳转] ${resolved.message}，停在这一条")
                            finishRunLog(executed, skipped, resolved.message)
            return Result(executed, skipped, resolved.message)
                        }
                    }
                }
                if (outcome.stop) {
                    log(
                        "   ↳ 完成 [${pc + 1}] 用时 ${System.currentTimeMillis() - nodeStartedAt}ms" +
                            "（识别 ${recognizeMs}ms × ${recognizeCount} 次）"
                    )
                    finishRunLog(executed, skipped, null)
                    return Result(executed, skipped, null)
                }
                /*
                 * 这条的耗时。识别耗时单独列出来 ——
                 * 一条动作慢，可能是识别慢，也可能大部分时间在等画面（重试间隔）。
                 */
                val nodeCost = System.currentTimeMillis() - nodeStartedAt
                log(
                    "   ↳ 完成 [${pc + 1}] 用时 ${nodeCost}ms" +
                        "（识别 ${recognizeMs}ms × ${recognizeCount} 次）" +
                        ""
                )
                executed++
                pc++
            }
        }

        finishRunLog(executed, skipped, null)
        return Result(executed, skipped, null)
    }

    /**
     * 收尾：失败现场存图 + 一行汇总。
     *
     * Maa 的做法是每次 on_error 都把当时那一帧存成
     * `debug/on_error/时间_任务_节点.png`；我们至少要有一张 ——
     * 用户说"它卡住了"的时候，图比文字有用得多。
     */
    private fun finishRunLog(executed: Int, skipped: List<Result.Skip>, failReason: String?) {
        runCatching {
            val firstSkip = skipped.firstOrNull()
            if (firstSkip != null || failReason != null) {
                val tag = (failReason ?: firstSkip?.let { "#${it.index + 1} ${it.label}" } ?: "失败")
                    .take(40)
                matcher.saveFrame(
                    com.pvz2.auto.core.ZjsLog.shotDir().absolutePath,
                    tag
                )?.let { log("   现场截图：$it") }
            }
            val summary = "执行 $executed 条，跳过 ${skipped.size} 条" +
                (failReason?.let { "，停止原因：$it" } ?: "")
            log("===== 结束：$summary =====")
            com.pvz2.auto.core.ZjsLog.endRun(summary)
        }
    }

    // ------------------------------------------------------------ 单个动作

    private data class Outcome(
        /**
         * true = 这一条是**条件不满足被跳过**的（不是执行成功）。
         *
         * 「动作运行状态」条件要区分成功/失败：协议里条件不满足算失败
         * （界面文案就是「运行失败/条件失败」）。原来这种情况返回的是
         * 不带 abort 的 Outcome()，被记成"成功"，于是 fail 永远判不出来。
         */
        val condSkipped: Boolean = false,
        val jumpTo: String? = null,
        val stop: Boolean = false,
        /**
         * 只结束**当前子动作列表**，回到上层继续（协议 `ContinueParentExecute=true`）。
         *
         * 只有 `subShellDepth > 0` 时才可能置位：顶层脚本没有父可回，
         * 那种情况下按"立即结束"处理并在日志里说明。
         */
        val breakShell: Boolean = false,
        /**
         * 非 null = **整条任务就此结束**。
         *
         * Maa 的语义：一个节点识别不到、又没有失败跳转（on_error / interrupt），
         * 任务就到此为止 —— 不是"跳过这一条继续下一条"。
         *
         * 我们原来是后者。后果很直观：识别全都失败时，整条链会在几秒内被一路跳过，
         * 界面显示"跑完 42 条"，实际什么都没做。这就是"3、4 秒跑完"的来源。
         */
        val abort: String? = null
    )

    /**
     * 执行一个动作，按 `repeatCount` 重复。
     *
     * `fastRepeatMode`（FIELDS.md:118）跳过条件/坐标重算，只重放第一次的结果 ——
     * 对"连点同一个位置"能省掉每次的解析开销。
     */
    /**
     * 执行一条动作（含重复）。
     *
     * ★ 「禁用 → 条件 → 前置等待」放在这里，而不是只写在顶层循环里。
     *
     *   `运行多个动作` 的子动作、监听槽位里的动作**都是完整动作**，
     *   它们的 disabled / condition / delay 必须同样生效。
     *   原来这三段只在 `run()` 里，子动作直接进本函数 ——
     *   导入一份带条件的真实脚本，子动作会**无条件执行**：
     *   该等的不等、不该点的照点，属于跑错，不是少功能。
     *
     * @param abortOnRecognitionFail 识别类条件不成立时是否结束整条任务。
     *        顶层动作为 true（Maa 语义：节点进不去就停）；
     *        子动作/监听里为 false —— 只跳过这一条，不牵连外层。
     */
    private fun executeWithRepeat(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>,
        abortOnRecognitionFail: Boolean = true
    ): Outcome {
        // ---- 前置阶段：禁用 / 条件（含重试与"先等待再检查"）/ 前置等待 ----
        if (action.common.disabled) {
            skipped.add(Result.Skip(index, label, "已禁用"))
            return Outcome()
        }
        run {
            val cond = action.common.condition
            val mustCheck = cond != null && cond.disabled.not()

            // ---- 检查运行条件前（协议：动作**没有**条件时就不跑这个槽位）
            if (mustCheck) {
                runCallbacks(action, Z.CallbackSlot.BEFORE_CONDITION, index, label)
            }
            var delayDone = false
            if (mustCheck && !cond!!.checkBeforeDelay) {
                val pre = preDelay(action)
                waitDelay(pre.first, pre.second, delayDefaultMs(action))
                delayDone = true
            }
            if (mustCheck && !evaluateWithRetry(cond!!, index, skipped)) {
                /*
                 * ★ 跳过原因必须指向**真正卡住的那个条件**。
                 *
                 *   原来这里一律取 `matcher.lastReason()`：时间间隔/限制运行次数
                 *   这类纯逻辑条件不满足时，也会把上一次识别失败的原因贴上去，
                 *   于是日志写成「条件不满足：这一块里没识别出任何文字」——
                 *   用户会跑去调阈值、换模板，而真正的原因是间隔还没到。
                 */
                val condName = ZConditionEditor.labelOf(cond!!.type)
                val detail = if (isRecognitionCondition(cond)) matcher.lastReason() else condDetail(cond)
                val why = detail ?: "条件不成立"
                val msg = "第 ${index + 1} 条「$label」条件不满足（$condName）：$why"
                skipped.add(Result.Skip(index, label, msg))
                if (abortOnRecognitionFail && isRecognitionCondition(cond!!)) {
                    // 识别类条件不成立 = 这个节点进不去 → 整条任务结束
                    log("[任务结束] $msg")
                    return Outcome(abort = msg)
                }
                log("[跳过] $msg")
                // ---- 运行条件失败后（协议里的 afterConditionFail）
                runCallbacks(action, Z.CallbackSlot.AFTER_CONDITION_FAIL, index, label)
                return Outcome(condSkipped = true)
            }
            // ---- 条件满足后
            runCallbacks(action, Z.CallbackSlot.AFTER_CONDITION_SUC, index, label)

            if (!delayDone) {
                val pre = preDelay(action)
                waitDelay(pre.first, pre.second, delayDefaultMs(action))
            }
        }

        // ---- 动作开始运行前（条件过了、等待也做完了，正要动真格）
        runCallbacks(action, Z.CallbackSlot.BEFORE_START_EXEC, index, label)

        val repeat = action.common.repeatCount ?: 1
        /*
         * repeatCount = 0 在协议里是**无限**（FIELDS.md:115）。
         *
         * 以前我把它降级成 1 次，理由是"怕卡死"。但那违背协议：
         * 作者写 0 就是要一直重复（连点、连续采集），
         * 而且这类动作通常自带跳转或停止条件能退出。
         * 现在按协议来：只有「停止」或跳转/停止动作能结束它。
         */
        /*
         * repeatCount = 0 在协议里是无限（FIELDS.md:115）。
         *
         * 但"无限"只有在**能退出**时才成立：这条动作自己会跳转、
         * 或者它是停止/跳转类动作。否则无限重复就是死循环 ——
         * 用户写了 0 又没配跳转时，跑起来会永远卡在这一条，
         * 除了强杀进程没有别的办法。
         *
         * 所以：能退出 → 真无限（由 stopped / 跳转结束）；
         *       不能退出 → 只跑一次，并明确警告。
         */
        val canEscape = action.type.controlRunType == "jumpTo" ||
            action.type.controlRunType == "stop" ||
            action.extra["jumpToPosition"] != null
        val infinite = repeat <= 0 && canEscape
        if (repeat <= 0 && !canEscape) {
            log("[$index] $label 重复次数为 0（无限）但这条没有跳转/停止，按 1 次执行以免卡死")
        } else if (infinite) {
            log("[$index] $label 重复次数为 0 = 无限（由跳转/停止结束）")
        }
        val times = if (infinite) Int.MAX_VALUE else if (repeat <= 0) 1 else repeat

        /*
         * 监听（scriptCallbacks，7 个槽位）。
         *
         * 协议里这些槽位都会**真的跑**（FIELDS.md:292-300）。我们只实现了
         * afterExecSuc / afterExecFinish，其余槽位在这里明确说一次 ——
         * 以前完全不提，用户看到"动作成功了"，却没发现挂在上面的收尾动作
         * 一条都没执行。静默失效里最难查的一种。
         */
        warnUnsupportedCallbacks(action, index, label, skipped)

        // fastRepeatMode 的复用缓存：首轮识别出来的坐标，后面几轮直接用（见 RepeatMemo）
        val memo = RepeatMemo()

        for (n in 0 until times) {
            if (stopped) break
            if (n > 0) {
                /*
                 * 重复间隔缺省时**回落到 delay**（FIELDS.md:116）。
                 * 不回落的话 repeatCount:3 会变成三次背靠背点击，
                 * 游戏侧常把连击合并或丢弃。
                 */
                val hasOwn = !action.common.repeatDelayValue.isNullOrBlank()
                val v = if (hasOwn) action.common.repeatDelayValue else action.common.delay
                val u = if (hasOwn) action.common.repeatDelayUnit else action.common.delayUnit
                waitDelay(v, u, delayDefaultMs(action))
                /*
                 * ★ 无限重复时再兜一个最小间隔。
                 *
                 *   能走到"无限"的只有自带跳转/停止的动作（见上面的 canEscape），
                 *   但只要它同时还开了 fastRepeatMode 且 delay 写成 0，
                 *   这一轮就只剩一次注入 —— 于是变成每秒数百次的空转。
                 */
                if (times == Int.MAX_VALUE) {
                    waitDelay(null, Z.TimeUnit.MILLISECONDS, MIN_INFINITE_GAP_MS)
                }
            }

            when (val r = executeOnce(action, index, label, skipped, memo)) {
                // 执行不了：若原因是"识别不到"就结束整个任务，否则继续
                null -> {
                    /*
                     * ★ 快速重复的后续轮次**不得 abort**。
                     *
                     *   此时识别根本没跑（坐标是首轮缓存下来的），失败只可能是注入/
                     *   参数问题。把它升格成"整个任务结束"，会让一条连点动作在第二轮
                     *   就把脚本杀掉 —— 而首轮明明是成功的。
                     */
                    if (n > 0 && action.common.fastRepeatMode) {
                        abortReason = null
                        runCallbacks(action, Z.CallbackSlot.AFTER_EXEC_FAIL, index, label)
                        return Outcome()
                    }
                    // afterExecFail：协议里设置后忽略 pauseOnFail 与全局失败监听
                    runCallbacks(action, Z.CallbackSlot.AFTER_EXEC_FAIL, index, label)
                    return Outcome(abort = abortReason)
                }

                else -> {
                    // 跳转转出：协议规定 afterExecFinish **不触发**（FIELDS.md:300）
                    // breakShell 同理：它表示"结束当前子列表"，重复循环也该一起退出
                    if (r.jumpTo != null || r.stop || r.breakShell) return r
                    // afterExecSuc：**每次重复成功**都触发（FIELDS.md:298）
                    runCallbacks(action, Z.CallbackSlot.AFTER_EXEC_SUC, index, label)
                }
            }
        }
        // afterExecFinish：所有重复结束触发一次
        runCallbacks(action, Z.CallbackSlot.AFTER_EXEC_FINISH, index, label)
        return Outcome()
    }

    /**
     * 执行一次。返回 null 表示这个动作做不了（已记录原因）。
     *
     * [memo] 是 `fastRepeatMode` 的复用缓存：首轮把命中点算出来存在里面，
     * 后续轮次直接重放（FIELDS.md:118）。
     */
    private fun executeOnce(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>,
        memo: RepeatMemo
    ): Outcome? {
        /*
         * 每次进入动作先归零"上一次的失败原因"。
         *
         * abortReason 是可变字段：locate 失败会写它、runMulti 会清它，
         * 而监听里的动作失败后没人清 —— 后续一条"缺位置"之类的非识别失败
         * 就会带着陈旧原因让整条任务以错误的名义终止。
         * 改成"谁进入谁负责"：进来先清。
         */
        abortReason = null
        val c = action.common
        // duration 支持区间 "100-300"（FIELDS.md:112），取区间内随机；
        // 以前直接 toIntOrNull()，写区间就变 null → 静默掉回默认值。
        val holdRaw = randomIn(c.duration)?.toInt() ?: when (action.type) {
            Z.ActionType.SWIPE -> 1000
            else -> 100
        }
        /*
         * 「极速点击模式」（协议 10.1 `highSpeedTap`）。
         *
         * 自动精灵的原话：「开启后，会忽略默认的额外点击结束等待（5~30ms），
         * 但是会导致连续极速点击动作中少部分点击失效」。
         *
         * 我们这边对应的是注入层 `MODE_WAIT_FOR_FINISH`：等事件真的进管线才返回。
         * 所以 hold 传 **0** 当信号（见 Injector.tap 的约定），
         * 由注入层改成异步发 + 1ms 间隔。只对点击族有意义，滑动不受影响。
         */
        val clickFamily = action.type == Z.ActionType.CLICK ||
            action.type == Z.ActionType.CLICK_IMAGE ||
            action.type == Z.ActionType.CLICK_TEXT ||
            action.type == Z.ActionType.CLICK_COLOR
        val fast = clickFamily && boolExtra(action, "highSpeedTap")
        val hold = if (fast) 0 else holdRaw
        /*
         * 「识别后等待」（Maa 的 pre_delay 真正的位置）。
         *
         * Maa 的顺序是：**识别 → pre_delay → 动作**。
         * 我们原来只有一个 delay，而它在识别**之前**（前置阶段里就睡了），
         * 于是"等画面稳定再认"变成了"等一会儿再认"，语义不同。
         *
         * 这个字段专补这一段：命中之后、注入之前睡。
         */
        // extra 里可能是数字，也可能是字符串（界面上填的是文本），两种都认
        val afterLocateMs = when (val v = action.extra["delayAfterLocate"]) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: 0
            else -> 0
        }

        return when (action.type) {
            // ---- 点击系列
            Z.ActionType.CLICK, Z.ActionType.CLICK_IMAGE,
            Z.ActionType.CLICK_TEXT, Z.ActionType.CLICK_COLOR,
            Z.ActionType.CLICK_NODE -> {
                val pos = action.posData
                if (pos == null) {
                    skipped.add(Result.Skip(index, label, "缺位置"))
                    return null
                }
                /*
                 * ★ fastRepeatMode：首轮把命中点算出来，后面几轮直接重放。
                 *
                 *   协议原文是"跳过条件检查/坐标计算/变量计算/监听，只重放第一次结果"
                 *   （FIELDS.md:118）。以前这个字段只出现在注释里 —— 用户勾了
                 *   「快速重复」，行为一个像素都没变，连点二十次就白算二十次识别。
                 */
                val cached = if (c.fastRepeatMode) memo.hits else null
                val hits = cached ?: locate(
                    pos, index, label, skipped,
                    timeoutMs = action.waitTimeoutMs ?: 0,
                    intervalMs = action.waitIntervalMs ?: 200
                ) ?: return null
                if (c.fastRepeatMode) memo.hits = hits

                // indexNum 可能选出**多个**点（"1,3,4" = 这几个都点），所以是列表
                /*
                 * 识别细节进运行日志（Maa 的日志也是这个粒度）：
                 * 找的是什么、范围多大、命中几个、最佳分、首点在哪、花了多久。
                 * 没有这几项，用户只看到"没找到"，分不清是没画面、范围不对还是阈值太高。
                 */
                log(
                    "   识别：" + describePos(pos) + " → 命中 ${hits.size} 个" +
                        (matcher.lastDetail()?.let { " | $it" } ?: "") +
                        " | " + (hits.firstOrNull()?.let { "首点(${it.x},${it.y})" } ?: "无") +
                        " | 本次识别 ${lastSearchMs}ms"
                )
                tapHits(hits, hold, action, index, label, skipped, afterLocateMs)
                Outcome()
            }

            // ---- 滑动
            Z.ActionType.SWIPE -> {
                val s = action.startPos
                val e = action.endPos
                if (s == null || e == null) {
                    skipped.add(Result.Skip(index, label, "缺起点或终点"))
                    return null
                }
                /*
                 * 起点/终点也可能是"图片/文字/颜色"位置，这时 indexNum 会选出多个点。
                 * 协议里多点滑动就是"**每一对**起终点各滑一次"，所以这里按下标配对；
                 * 两边数量不等时用最后一个兜底（宁可多滑一次，也不要静默少滑一半）。
                 */
                val starts = (if (c.fastRepeatMode) memo.starts else null) ?: locate(
                    s, index, label, skipped,
                    timeoutMs = action.waitTimeoutMs ?: 0,
                    intervalMs = action.waitIntervalMs ?: 200
                ) ?: return null
                val ends = (if (c.fastRepeatMode) memo.ends else null) ?: locate(
                    e, index, label, skipped,
                    timeoutMs = action.waitTimeoutMs ?: 0,
                    intervalMs = action.waitIntervalMs ?: 200
                ) ?: return null
                if (c.fastRepeatMode) {
                    memo.starts = starts
                    memo.ends = ends
                }

                val lastStart = starts.last()
                val lastEnd = ends.last()
                val pairs = maxOf(starts.size, ends.size)
                for (i in 0 until pairs) {
                    if (stopped) break
                    // 第 2 对起先等重复间隔（与多点点击同一套理由：连发会被游戏合并）
                    if (i > 0) waitMultiGap(action)
                    val a = starts.getOrNull(i) ?: lastStart
                    val b = ends.getOrNull(i) ?: lastEnd
                    log("   滑动 (${a.x},${a.y})→(${b.x},${b.y}) 用时 ${hold}ms")
                    inject.swipe(a.x, a.y, b.x, b.y, hold)
                }
                Outcome()
            }

            // ---- 多指滑动
            Z.ActionType.MULTI_SWIPE -> {
                /*
                 * ★ 协议里多指手势的轨迹在 `gestureActions`（FIELDS.md 10.9），
                 *   而 `fingers` 是我们自己的私有扩展。从自动精灵导入的脚本
                 *   只有 gestureActions，于是以前一律判「没有手指」被跳过 ——
                 *   缩放/双指类脚本必然跑不动，界面上却看不出问题。
                 *
                 *   gestureActions 的语义是"逐点执行的普通动作"，按顺序跑即可
                 *   （本机没有真正的多指并发注入，这点如实写进日志，不假装）。
                 */
                if (action.fingers.isEmpty()) {
                    if (action.gestureActions.isEmpty()) {
                        val why = if (action.gestureRaw.isNotEmpty()) {
                            "轨迹点里有 ${action.gestureRaw.size} 项格式不认识，已原样保留"
                        } else {
                            "没有手指，也没有轨迹点"
                        }
                        skipped.add(Result.Skip(index, label, why))
                        return null
                    }
                    log("[$index] $label 用协议轨迹 gestureActions 逐点执行（本机不是真并发多指）")
                    return runActionList(action, action.gestureActions, index, label, skipped)
                }
                val paths = ArrayList<Pair<ZPosResolver.Hit, ZPosResolver.Hit>>()
                for ((fi, f) in action.fingers.withIndex()) {
                    val s = f.startPos
                    val e = f.endPos
                    if (s == null || e == null) {
                        skipped.add(Result.Skip(index, label, "手指${fi + 1} 缺起点或终点"))
                        return null
                    }
                    /*
                     * ★ 手指的起终点也可能是「图片/文字」位置。
                     *
                     *   resolveLocation 在 x/y 为空时返回**屏幕中心** —— 多指手势于是
                     *   就变成「从屏幕中心滑到屏幕中心」：不报错，也不滑对地方。
                     *   这里和 SWIPE 分支一样走 locate()。
                     */
                    val sp = locate(s, index, "$label 手指${fi + 1} 起点", skipped)
                        ?: return null
                    val ep = locate(e, index, "$label 手指${fi + 1} 终点", skipped)
                        ?: return null
                    paths.add(sp.first() to ep.first())
                }
                try {
                    inject.multiSwipe(paths, hold)
                } catch (e: Throwable) {
                    skipped.add(Result.Skip(index, label, "多指滑动失败：${e.message}"))
                    return null
                }
                Outcome()
            }

            // ---- 单指手势（10.8）：轨迹点是普通动作，逐点执行
            Z.ActionType.GESTURE -> {
                if (action.gestureActions.isEmpty()) {
                    val why = if (action.gestureRaw.isNotEmpty()) {
                        "轨迹点里有 ${action.gestureRaw.size} 项格式不认识，已原样保留"
                    } else {
                        "没有轨迹点（gestureActions 为空）"
                    }
                    skipped.add(Result.Skip(index, label, why))
                    return null
                }
                return runActionList(action, action.gestureActions, index, label, skipped)
            }

            // ---- 按键
            Z.ActionType.KEY -> {
                val kc = (action.extra["keyCode"] as? Number)?.toInt()
                if (kc == null) {
                    skipped.add(Result.Skip(index, label, "缺 keyCode"))
                    return null
                }
                // 单条失败不该中断整个脚本 —— 一条按不了键，
                // 后面几十条点击还是该照跑。
                try {
                    inject.key(kc, hold)
                } catch (e: Throwable) {
                    skipped.add(Result.Skip(index, label, "按键失败：${e.message}"))
                    return null
                }
                Outcome()
            }

            // ---- 输入内容
            Z.ActionType.INPUT_TEXT -> {
                val t = action.extra["inputText"]?.toString().orEmpty()
                if (t.isEmpty()) {
                    skipped.add(Result.Skip(index, label, "没有要输入的内容"))
                    return null
                }
                try {
                    inject.inputText(t)
                } catch (e: Throwable) {
                    skipped.add(Result.Skip(index, label, "输入失败：${e.message}"))
                    return null
                }
                Outcome()
            }

            // ---- 执行脚本（10.15 m80685）：跑另一个脚本文件
            Z.ActionType.RUN_SCRIPT -> {
                val name = action.extra["filePath"]?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    skipped.add(Result.Skip(index, label, "执行脚本：缺 filePath"))
                    return null
                }
                val loader = scriptLoader
                if (loader == null) {
                    skipped.add(Result.Skip(index, label, "执行脚本：本机未接脚本加载通道"))
                    return null
                }
                val sub = loader(name)
                if (sub == null) {
                    skipped.add(Result.Skip(index, label, "执行脚本：找不到脚本「$name」"))
                    return null
                }
                if (subShellDepth >= MAX_SUB_SHELL_DEPTH) {
                    skipped.add(Result.Skip(index, label, "执行脚本：嵌套超过 $MAX_SUB_SHELL_DEPTH 层"))
                    return null
                }
                /*
                 * 子脚本用**新的执行器**跑：变量表各自独立（协议里变量属于一次运行），
                 * 但注入器/屏幕/匹配器共用，日志仍汇到同一个地方。
                 * `continueCurrentAfterFinish` 默认 false —— 也就是子脚本跑完
                 * **不回到父脚本**，等于整条任务结束。
                 */
                log("[$index] $label 开始执行子脚本「$name」")
                subShellDepth++
                val subResult = try {
                    ZExecutor(inject, screen, matcher, log).also { e ->
                        e.scriptLoader = loader
                        e.jsStorage = jsStorage
                        e.jsClipboard = jsClipboard
                        e.jsToast = jsToast
                        e.scriptVars.putAll(scriptVars)
                    }.run(sub)
                } finally {
                    subShellDepth--
                }
                subResult.skipped.forEach {
                    log("[$index] $label 子脚本跳过：${it.label} — ${it.reason}")
                }
                log(
                    "[$index] $label 子脚本「$name」结束：执行 ${subResult.executed} 条" +
                        (subResult.error?.let { "，中止：$it" } ?: "")
                )
                val cont = boolExtra(action, "continueCurrentAfterFinish")
                if (!cont) {
                    log("[$index] $label continueCurrentAfterFinish=false，父脚本到此结束")
                    return Outcome(stop = true)
                }
                if (subResult.error != null) {
                    skipped.add(Result.Skip(index, label, "子脚本中止：${subResult.error}"))
                }
                Outcome()
            }

            // ---- 识别屏幕（10.12 m21687）
            //
            // 协议：config.recognitionMode（ocr_local / ocr_local_comp / ocr_online /
            // image_captcha / get_image_data / position_by_human）+ useResultMode
            // （clipboard / input，默认 clipboard）。
            //
            // 本机能做的是**本地 OCR**；云端识别、验证码识图、取图像数据、
            // 人工定位这四种没有对应能力，明确报出来，不做成"跑过了但什么也没发生"。
            Z.ActionType.RECOGNIZE_SCREEN -> {
                val cfg = action.extra["config"]
                val mode = runCatching {
                    val o = when (cfg) {
                        is org.json.JSONObject -> cfg
                        is String -> org.json.JSONObject(cfg)
                        else -> org.json.JSONObject()
                    }
                    o.optString("recognitionMode")
                }.getOrNull()?.ifBlank { null } ?: "ocr_local"
                if (mode != "ocr_local" && mode != "ocr_local_comp") {
                    skipped.add(
                        Result.Skip(index, label, "识别屏幕：模式「$mode」本机不支持（只支持本地 OCR）")
                    )
                    return null
                }
                /*
                 * 识别区域：协议里 `config.recognitionArea` 是**必填**且决定识别范围
                 * （官方 `if(!n) throw "识别区域缺失"`）。缺省时才退回全屏 ——
                 * 全屏识别会把无关文字一起读进来，串进结果。
                 */
                val areaRaw = runCatching {
                    val o = when (cfg) {
                        is org.json.JSONObject -> cfg
                        is String -> org.json.JSONObject(cfg)
                        else -> null
                    }
                    o?.opt("recognitionArea")?.toString()
                }.getOrNull()
                val rect = if (!areaRaw.isNullOrBlank()) {
                    runCatching {
                        ZArea.parse(areaRaw)?.toPxRect(screen.width, screen.height, screen.density)
                    }.getOrNull() ?: intArrayOf(0, 0, screen.width, screen.height)
                } else {
                    intArrayOf(0, 0, screen.width, screen.height)
                }
                val texts = try {
                    matcher.ocrTexts(rect)
                } catch (e: Throwable) {
                    skipped.add(Result.Skip(index, label, "识别屏幕失败：${e.message}"))
                    return null
                }
                if (texts.isEmpty()) {
                    skipped.add(
                        Result.Skip(index, label, "识别屏幕：没识别出文字（${matcher.lastReason() ?: "画面无字"}）")
                    )
                    return null
                }
                /*
                 * 拼接方式：协议 `ocrResultType` 为 `raw` 时返回条目数组，
                 * 否则用**空格**拼（官方行为）。我们固定吃过前一种表达方式下的
                 * "空格拼接"，raw 模式用换行保留结构（也方便日志里看行）。
                 */
                val rawMode = runCatching {
                    val o = when (cfg) {
                        is org.json.JSONObject -> cfg
                        is String -> org.json.JSONObject(cfg)
                        else -> null
                    }
                    o?.optString("ocrResultType")
                }.getOrNull() == "raw"
                val joined = texts.joinToString(if (rawMode) "\n" else " ")
                log("[$index] $label 识别屏幕：${texts.size} 行，共 ${joined.length} 字")
                val useResult = action.extra["useResultMode"]?.toString()?.trim().orEmpty()
                    .ifBlank { "clipboard" }
                if (useResult == "input") {
                    /*
                     * ★ 先把"打不进去"的情况挡在前面。
                     *
                     *   `input text` 走 KeyCharacterMap，只能打**单行 ASCII**：
                     *   中文打不出来、换行会被截断。而本项目的场景是中文游戏，
                     *   OCR 结果基本全是中文 —— 直接 injection 的后果是
                     *   "动作报成功、游戏里什么都没输入"，而且注入层还把异常
                     *   用 runCatching 吞了，连日志都没有。
                     */
                    val bad = when {
                        joined.contains('\n') -> "多行"
                        joined.any { it.code > 127 } -> "含非 ASCII 字符"
                        else -> null
                    }
                    if (bad != null) {
                        skipped.add(
                            Result.Skip(index, label, "识别屏幕：结果$bad，本机 input text 只能注入单行 ASCII")
                        )
                        log("[$index] $label 识别到的文字：${joined.take(120)}")
                        return null
                    }
                    try {
                        inject.inputText(joined)
                    } catch (e: Throwable) {
                        skipped.add(Result.Skip(index, label, "识别屏幕：输入失败（${e.message}）"))
                        return null
                    }
                } else {
                    /*
                     * clipboard 是协议的**默认**结果模式（FIELDS.md:494）。
                     * 原来这里直接跳过 —— 于是所有没写这个字段的脚本导入后，
                     * 「识别屏幕」100% 不生效，等于动作白跑。
                     */
                    val ok = runCatching { inject.setClipboard(joined) }.getOrDefault(false)
                    if (!ok) {
                        skipped.add(
                            Result.Skip(index, label, "识别屏幕：剪贴板通道未接，结果没写进去")
                        )
                    }
                    log("[$index] $label 识别到的文字：${joined.take(120)}")
                }
                Outcome()
            }

            // ---- 暂停（10.20 m82947）：停在原地等用户点「继续」
            Z.ActionType.CONTROL_PAUSE -> {
                com.pvz2.auto.core.ScriptRunState.requestPause()
                log("[$index] $label 已暂停，等待「继续」")
                while (com.pvz2.auto.core.ScriptRunState.paused && !stopped) {
                    Thread.sleep(50)
                }
                if (stopped) {
                    log("[$index] $label 暂停中被停止")
                    return Outcome(stop = true)
                }
                log("[$index] $label 继续执行")
                Outcome()
            }

            // ---- 运行JS代码（10.25 m23689）：jsCode 直接跑在沙箱里
            //
            // 自动精灵那边是 QuickJS；我们用 Rhino（纯 Java、解释模式），
            // 差别集中在 ES6 语法（箭头函数/let/模板串）—— 用到时会明确报语法错误。
            // 变量和执行器的运行时变量是同一份：全局名直接读写，
            // 也可以 get('名字') / set('名字', 值)。
            Z.ActionType.RUN_JS -> {
                val code = action.extra["jsCode"]?.toString().orEmpty()
                if (code.isBlank()) {
                    skipped.add(Result.Skip(index, label, "运行JS代码：jsCode 是空的"))
                    return null
                }
                val out = JsEngine.run(code, jsEnv())
                if (!out.ok) {
                    skipped.add(Result.Skip(index, label, "运行JS代码出错：${out.error}"))
                    log("[$index] $label JS 出错：${out.error}")
                    return null
                }
                log(
                    "[$index] $label JS 执行完成" +
                        (out.value?.let { "，结果 $it" } ?: "")
                )
                Outcome()
            }

            // ---- 设置跳转标识（10.19 m54383）：**它就是基类的那个 jumpId**
            //
            // 协议里这个动作没有独有字段 —— 它的全部作用是"当跳转目标"。
            // jumpTable 在脚本加载时已经把每个动作的 jumpId 建成下标表，
            // 所以运行时**什么都不用做**。以前把这一条当"还没实现"跳过，
            // 结果脚本照跑，看起来没问题；可一旦有 `跳转到 :这个标识`，
            // 目标是存在的（表里有），跳过去却执行了这条空动作 —— 行为是对的。
            // 标成可执行后界面也不再显示"暂不支持"。
            Z.ActionType.CONTROL_SET_JUMP_ID -> Outcome()

            // ---- 设置变量（10.24 m36077）：vars: [{name, value: VarValue}]
            Z.ActionType.SET_VARS -> {
                /*
                 * 交互弹窗（协议 10.24）。
                 *
                 * 自动精灵里「设置变量」可以是一条**问用户的对话框**：
                 * 确定 → 变量按配置赋值；取消 → 执行 `dialogCancelAction`
                 * 那条动作、并且**不赋值**。`dialogShowOnce` 表示只问一次。
                 *
                 * 原来这六个字段一个都没实现 —— 我们会静默按"直接赋值"跑完，
                 * 取消分支永不执行，用户配的交互等于不存在。
                 */
                val title = action.extra["dialogTitle"]?.toString()?.trim().orEmpty()
                val okText = action.extra["dialogOKText"]?.toString()?.trim().orEmpty()
                val cancelText = action.extra["dialogCancelText"]?.toString()?.trim().orEmpty()
                val cancelActionJson = action.extra["dialogCancelAction"]
                val autoBtn = action.extra["dialogAutoClickBtn"]?.toString()?.trim().orEmpty()
                val showOnce = boolExtra(action, "dialogShowOnce")
                val hasDialog = title.isNotEmpty() || okText.isNotEmpty() ||
                    cancelText.isNotEmpty() || cancelActionJson != null

                if (hasDialog) {
                    val onceKey = "dialogShown:$index:" + title.take(40)
                    val alreadyShown = showOnce &&
                        jsStorage?.get?.invoke(onceKey)?.isNotEmpty() == true
                    if (alreadyShown) {
                        log("[$index] $label 弹窗设了「仅展示一次」，这次跳过询问")
                    } else {
                        val choice = inject.askDialog(
                            AskDialog(
                                title = title.ifBlank { "提示" },
                                okText = okText.ifBlank { "确定" },
                                cancelText = cancelText.ifBlank { "取消" },
                                autoBtn = autoBtn,
                                autoDelayMs = (action.extra["dialogAutoClickDelay"] as? Number)
                                    ?.toInt() ?: 3000
                            )
                        )
                        if (showOnce) jsStorage?.set?.invoke(onceKey, "1")
                        when (choice) {
                            null -> log("[$index] $label 本机没有弹窗通道，按「确定」处理")
                            1 -> {
                                log("[$index] $label 点了「${cancelText.ifBlank { "取消" }}」→ 执行取消动作、不赋值")
                                val cancelAction = parseActionJson(cancelActionJson)
                                if (cancelAction == null) {
                                    skipped.add(Result.Skip(index, label, "取消动作解析失败（格式不认识）"))
                                } else {
                                    executeWithRepeat(
                                        cancelAction, index,
                                        "$label › 取消动作", skipped,
                                        abortOnRecognitionFail = false
                                    )
                                }
                                return Outcome()
                            }
                            else -> log("[$index] $label 点了「${okText.ifBlank { "确定" }}」→ 按配置赋值")
                        }
                    }
                }

                val arr = varsArrayOf(action)
                if (arr == null) {
                    skipped.add(Result.Skip(index, label, "设置变量：vars 字段缺失或格式不对"))
                    return null
                }
                var okCount = 0
                val bad = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isEmpty()) continue
                    val def = item.optJSONObject("value")

                    /*
                     * 勾了「从键盘输入」(`showInput`) 的变量：值来自运行前的弹窗。
                     *  - 弹窗给了值        → 用弹窗的值（覆盖配置值）
                     *  - 留空 && 必填      → 记一条跳过原因（界面本该拦住，兜一下）
                     *  - 留空 && 不必填    → **删掉这个变量**（自动精灵的 VAR_DELETE_MARK）
                     */
                    if (def != null && def.optBoolean("showInput", false)) {
                        val typed = initialInputs[name]
                        val must = if (def.has("mustInput")) def.optBoolean("mustInput") else true
                        when {
                            typed == null -> Unit            // 没走弹窗（例如子脚本）：用配置值
                            typed.isNotEmpty() -> { scriptVars[name] = typed; okCount++ }
                            must -> bad.add("$name（必填但为空）")
                            else -> scriptVars.remove(name)  // 与协议一致：删变量
                        }
                        if (typed != null) continue
                    }

                    val lit = def?.let { literalOf(it) }
                    if (lit == null) {
                        // 求不出来的变量（表达式/随机数/取文本…）明确说出来，
                        // 别让它静默变成空值 —— 后面用它拼出来的文字会全错。
                        bad.add(name)
                        continue
                    }
                    scriptVars[name] = lit.text
                    okCount++
                }
                log(
                    "[$index] $label 设置变量：成功 $okCount 个" +
                        (if (bad.isEmpty()) "" else "；${bad.joinToString("、")} 求不出值（本机只支持字面量）")
                )
                if (bad.isNotEmpty()) {
                    skipped.add(
                        Result.Skip(index, label, "变量 ${bad.joinToString("、")} 不是字面量，未赋值")
                    )
                }
                Outcome()
            }

            // ---- 控制执行
            Z.ActionType.CONTROL_WAIT -> {
                // 等待已经在 delay 里做过了；waitDelay 动作本身不再额外等
                Outcome()
            }

            Z.ActionType.CONTROL_JUMP -> {
                val target = action.extra["jumpToPosition"]?.toString()
                if (target.isNullOrBlank()) {
                    skipped.add(Result.Skip(index, label, "缺跳转目标"))
                    return null
                }
                Outcome(jumpTo = target)
            }

            Z.ActionType.CONTROL_STOP -> {
                /*
                 * ContinueParentExecute（FIELDS.md:557，**首字母大写**）：
                 * true = 只结束当前子脚本，回到上层继续。
                 *
                 * 默认（false）= 全停，这是协议语义，所以这里不能无条件改成
                 * "只跳出子列表"——那会让脚本该结束的时候继续往下点。
                 * 顶层没有父可回，ContinueParentExecute=true 时要说清楚：
                 * 否则作者会以为"停止"变成了"跳过"。
                 */
                val continueParent = boolExtra(action, "ContinueParentExecute")
                if (!continueParent) {
                    Outcome(stop = true)
                } else if (subShellDepth > 0) {
                    log("   停止（ContinueParentExecute=true）：只结束当前子动作列表，回到上层继续")
                    Outcome(breakShell = true)
                } else {
                    log("   停止：ContinueParentExecute=true 但当前是顶层脚本（没有父可回）→ 按立即结束处理")
                    Outcome(stop = true)
                }
            }

            /*
             * 等候选 —— Maa 的 next 语义。
             *
             * Maa 的每个节点都有一条候选链：每轮按顺序逐个识别，第一个成功的
             * 被进入；都不成功就继续等，直到超时。
             *
             * 这里就是那条循环。几个必须讲清楚的点：
             *
             * 1. **顺序即优先级**。不能排序、不能去重 —— 用户排的顺序就是语义。
             * 2. **每轮都从头试**。Maa 也是每轮重新识别，不是记住上一轮结果。
             * 3. **睡的时候分片**，否则「停止」要等整段间隔才生效，
             *    用户会以为卡死了。
             * 4. **超时不是错误**。Maa 里 timeout 到点是"这条没等到"，
             *    继续往下走；记一条跳过原因，但不按失败处理。
             */
            Z.ActionType.WAIT_CANDIDATES -> {
                if (action.candidates.isEmpty()) {
                    skipped.add(Result.Skip(index, label, "没有候选可等"))
                    return null
                }
                val timeout = action.waitTimeoutMs ?: 0
                val interval = (action.waitIntervalMs ?: 200).coerceAtLeast(20)
                val startedAt = System.currentTimeMillis()
                var round = 0
                while (!stopped) {
                    round++
                    for ((ci, cand) in action.candidates.withIndex()) {
                        val ok = cand.condition == null ||
                            evaluate(cand.condition, index, skipped)
                        if (ok) {
                            val target = cand.jumpTo
                            log(
                                "[等待候选] 第 $round 轮命中候选 ${ci + 1}" +
                                    "/${action.candidates.size}" +
                                    (target?.let { " → $it" } ?: "（留在原地往下走）")
                            )
                            return if (target.isNullOrBlank()) Outcome() else Outcome(jumpTo = target)
                        }
                    }
                    // 每 10 轮报一次"还在等什么"。
                    // 不报的话脚本会长时间毫无动静，用户无法区分
                    // "在等画面"和"卡死了" —— 这两件的处理方式完全相反。
                    if (round % 10 == 0) {
                        val waited = (System.currentTimeMillis() - startedAt) / 1000
                        // 同一个信息也要给界面看：用户不该为了知道"在等什么"去翻 logcat
                        com.pvz2.auto.core.ScriptRunState.update {
                            it.copy(
                                message = "正在等「${action.candidates.joinToString(" / ") {
                                    c -> c.jumpTo ?: "下一动作"
                                }}」第 $round 轮（已 ${waited}s）"
                            )
                        }
                                val seen2 = matcher.lastDetail() ?: matcher.lastReason()
                        log(
                            "[等待候选] ${label}：已等 ${waited}s，第 $round 轮，" +
                                "${action.candidates.size} 个候选都没出现" +
                                (seen2?.let { " | 最近一次识别：$it" } ?: "") +
                                (if (timeout > 0) "（上限 ${timeout}ms）" else "（不设上限）")
                        )
                    }
                    if (timeout > 0 && System.currentTimeMillis() - startedAt >= timeout) {
                        /*
                         * 超时去哪 —— 对应 Maa 的 `on_error`。
                         *
                         * Maa：next 扫到 timeout = 这个节点失败，走 on_error（空 = 任务失败）。
                         * 我们：`onTimeoutJump`（本机新增字段）写了 jumpId 就跳过去；
                         *      没写则维持老行为 —— 记一条跳过、继续往下。
                         *
                         * 为什么不用「监听 afterExecFail」来做：监听槽位里的动作
                         * **不传递跳转/停止**（协议如此），而 on_error 本质是控制流。
                         */
                        val to = (action.extra["onTimeoutJump"] as? String)?.trim().orEmpty()
                if (to.isEmpty() && boolExtra(action, "onTimeoutStop")) {
                    val why = "等待候选超时（${timeout}ms），按 Maa 语义结束任务"
                    skipped.add(Result.Skip(index, label, why))
                    log("[等待候选] $why")
                    return Outcome(abort = why)
                }
                        if (to.isNotEmpty()) {
                            log("[等待候选] 等了 ${timeout}ms 没命中 → 超时跳转 $to（Maa on_error）")
                            skipped.add(Result.Skip(index, label, "超时（${timeout}ms）转 on_error：$to"))
                            return Outcome(jumpTo = to)
                        }
                        log("[等待候选] 等了 ${timeout}ms，${round} 轮都没命中，继续往下")
                        skipped.add(Result.Skip(index, label, "等了 ${timeout}ms 没有候选出现"))
                        return null
                    }
                    var slept = 0
                    while (slept < interval && !stopped) {
                        Thread.sleep(20L)
                        slept += 20
                    }
                }
                skipped.add(Result.Skip(index, label, "被停止"))
                null
            }

            /*
             * 运行多个动作（协议 10.16 / FIELDS.md:527-536）。
             *
             * 真实脚本的主体经常就是它 —— SPEC.md:824 那段每个节点都是一个
             * 「运行多个动作」。以前整块跳过：脚本"跑完 N 条"，游戏一步没动。
             */
            Z.ActionType.RUN_MULTI -> runMulti(action, index, label, skipped)

            // ---- 打开应用（协议 10.13）
            Z.ActionType.OPEN_APP -> {
                val pkg = action.extra["appPackageName"]?.toString()?.trim().orEmpty()
                if (pkg.isEmpty()) {
                    skipped.add(Result.Skip(index, label, "缺 appPackageName"))
                    return null
                }
                if (boolExtra(action, "onlyClose")) {
                    // 协议里 onlyClose=true 是"只关闭、不打开"。没有关闭通道时，
                    // 退化成"打开"会把用户想关的应用再拉起来 —— 宁可明确拒绝。
                    skipped.add(Result.Skip(index, label, "onlyClose（仅关闭应用）尚未实现"))
                    return null
                }
                val launcher = inject.appLauncher() ?: run {
                    skipped.add(Result.Skip(index, label, "本机无法打开应用（没有启动通道）"))
                    return null
                }
                if (boolExtra(action, "closeBeforeOpen")) {
                    log("   closeBeforeOpen 未实现：直接启动（已有任务可能被提到前台而不是新建）")
                }
                val ok = try {
                    launcher(pkg)
                } catch (e: Throwable) {
                    log("   启动 $pkg 异常：${e.message}")
                    false
                }
                if (!ok) {
                    skipped.add(Result.Skip(index, label, "启动 $pkg 失败"))
                    return null
                }
                log("   已请求打开 $pkg")
                Outcome()
            }

            // ---- 打开链接（协议 10.14）
            Z.ActionType.OPEN_LINK -> {
                val link = action.extra["link"]?.toString()?.trim().orEmpty()
                if (link.isEmpty()) {
                    skipped.add(Result.Skip(index, label, "缺 link"))
                    return null
                }
                val opener = inject.linkOpener() ?: run {
                    skipped.add(Result.Skip(index, label, "本机无法打开链接（没有打开通道）"))
                    return null
                }
                val ok = try {
                    opener(link)
                } catch (e: Throwable) {
                    log("   打开链接异常：${e.message}")
                    false
                }
                if (!ok) {
                    skipped.add(Result.Skip(index, label, "打开链接失败：$link"))
                    return null
                }
                log("   已请求打开链接 $link")
                Outcome()
            }

            // ---- 系统提示（协议 10.23）
            Z.ActionType.PROMPT -> {
                val text = action.extra["promptText"]?.toString().orEmpty()
                val title = action.extra["promptTitle"]?.toString().orEmpty()
                val body = if (title.isBlank()) text else "$title\n$text"
                val shower = inject.toaster()
                if (body.isBlank() || shower == null) {
                    skipped.add(
                        Result.Skip(
                            index, label,
                            if (body.isBlank()) "提示内容为空" else "本机无法显示提示（没有提示通道）"
                        )
                    )
                    return null
                }
                try {
                    shower(body)
                } catch (e: Throwable) {
                    skipped.add(Result.Skip(index, label, "显示提示失败：${e.message}"))
                    return null
                }
                /*
                 * 其余字段明确记录未实现。特别注意 promptType="alert" 在协议里是
                 * **阻塞**的（等用户按按钮），我们只做到 toast，不阻塞 ——
                 * 脚本会比作者预期跑得快，所以必须留痕。
                 */
                val rest = listOf("promptType", "showDuration", "showPosition", "playAudio", "useVibrator", "actions")
                    .filter { action.extra[it] != null }
                if (rest.isNotEmpty()) {
                    log("   系统提示：${rest.joinToString("、")} 未实现（只显示文本，不阻塞）")
                }
                Outcome()
            }

            // ---- 运行目标动作（协议 10.17）
            Z.ActionType.CONTROL_RUN_AIM -> {
                val aim = action.extra["aimActionPosition"]?.toString().orEmpty()
                val why = "运行目标动作（runAimAction）尚未实现" +
                    (if (aim.isBlank()) "" else "：目标 $aim")
                skipped.add(Result.Skip(index, label, why))
                log("[跳过] $label — $why")
                null
            }

            // pause / setJumpId / 其余未实现的动作：明确拒绝，不静默
            else -> {
                val why = when {
                    !action.type.executable -> "安卓上暂不支持"
                    else -> "还没实现"
                }
                skipped.add(Result.Skip(index, label, why))
                null
            }
        }
    }

    /**
     * 按位置对象的类型找命中点，并选出**要处理的那几个**。
     *
     * 返回列表而不是单点：`indexNum = "1,3,4"` 的语义是"这三个都点"
     * （FIELDS.md:151-154），以前随机挑一个 —— 多目标脚本每轮都漏掉大部分。
     */
    /**
     * 解析点击位置。
     *
     * ## 找不到时会重试（Maa 的 timeout 语义）
     *
     * Maa 的节点识别不到会一直等到超时，我们原来是一失败就跳过 ——
     * 从 Maa 转过来的脚本因此会"一路冲过去"，不等画面。
     *
     * ★ 关键区别：重试的是**识别**，不是动作。
     *   用 repeatCount 重试整个动作的话，找到之后剩下的次数会继续点下去
     *   （点 5 下变成点 5 次），那是另一个 bug。
     *
     * @param timeoutMs 总超时，0 = 只试一次
     * @param intervalMs 每轮间隔
     * @return 选中的命中点（至少一个）；null = 找不到 / 下标全越界（已记跳过原因）
     */
    /**
     * 「位置周围条件」（`posData.filterByAroundConditionSet`）。
     *
     * ## 语义来自自动精灵自己的帮助文案
     *
     * 反编译产物里（renderer-script-run-area-bundle.js）：
     * ```
     * getTypeDesc()  → "设置后可以过滤掉周围信息不满足条件的点位，以更精确的匹配到目标点位"
     * basePosX 帮助  → "即假定找到的目标坐标，底下条件列表中的 限制区域/指定位置
     *                   会根据和此坐标的相对关系，检查匹配到的点位是否满足周围条件"
     * ```
     * 也就是说：`basePosX/basePosY` 是**假定**的目标坐标，子条件的区域按它与基准的
     * 相对关系摆放。所以对每个真实命中点 P：
     *
     *     offset = P − basePos
     *     子条件区域/指定位置 + offset → 评估这些子条件
     *
     * 满足（and / or / count 由 matchMode 决定）的点位保留，其余丢弃。
     *
     * 子条件只支持 image / textFount / colorFound / nodeFound
     * （同一份产物里 `getSupportItemConditionTypes()` 就是这么返回的）。
     */
    private fun applyAroundFilter(
        pos: Z.PosData,
        hits: List<ZPosResolver.Hit>,
        index: Int,
        skipped: MutableList<Result.Skip>
    ): List<ZPosResolver.Hit> {
        val raw = pos.extra[AROUND_KEY] ?: return hits
        val obj = runCatching {
            if (raw is org.json.JSONObject) raw else org.json.JSONObject(raw.toString())
        }.getOrNull() ?: return hits
        if (obj.optString("type") != "AroundCondition") return hits

        val arr = obj.optJSONArray("conditions") ?: return hits
        val subs = ArrayList<Z.Condition>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { o -> Z.Condition.parse(o)?.let { subs.add(it) } }
        }
        if (subs.isEmpty()) return hits

        val mode = obj.optString("matchMode").ifBlank { "and" }
        val need = obj.optInt("matchCount", subs.size).coerceAtLeast(1)
        /*
         * ★ 缺 basePos 时**不过滤**。
         *
         *   官方（40791/13901）：`if (!base?.x || !base?.y)` 直接不构建子条件项 →
         *   and 模式下"没有项"= 真空成立 → 等于不做任何过滤。
         *   我们原来把缺失当成 (0,0)，于是以屏幕左上为基准去平移子条件区域，
         *   结果是"全过滤掉"或"过滤错点"，比不过滤更糟。
         */
        val bxRaw = obj.opt("basePosX")?.toString()?.trim()
        val byRaw = obj.opt("basePosY")?.toString()?.trim()
        if (bxRaw.isNullOrEmpty() || byRaw.isNullOrEmpty()) {
            log("   周围条件：没有基准坐标 basePosX/basePosY，按不过滤处理")
            return hits
        }
        val baseX = aroundPx(bxRaw, isX = true)
        val baseY = aroundPx(byRaw, isX = false)

        val tmpSkips = ArrayList<Result.Skip>()
        val kept = hits.filter { hit ->
            val dx = hit.x - baseX
            val dy = hit.y - baseY
            var okCount = 0
            for (sub in subs) {
                // 子条件只认这四种；其它类型（含我们自己加的）不参与判定
                if (sub.type !in AROUND_SUB_TYPES) continue
                if (evaluate(shiftCondition(sub, dx, dy), index, tmpSkips)) okCount++
            }
            when (mode) {
                "or" -> okCount > 0
                "count" -> okCount >= need
                else -> okCount == subs.count { it.type in AROUND_SUB_TYPES }
            }
        }
        if (kept.size != hits.size) {
            log(
                "   周围条件：命中 ${hits.size} 处，过滤后 ${kept.size} 处" +
                    "（基准 $baseX,$baseY，模式 $mode）"
            )
        }
        if (kept.isEmpty()) {
            skipped.add(Result.Skip(index, "位置", "周围条件把 ${hits.size} 个候选点位全部过滤掉了"))
        }
        return kept
    }

    /** 把子条件里的区域/指定位置整体平移 (dx, dy)。 */
    private fun shiftCondition(cond: Z.Condition, dx: Int, dy: Int): Z.Condition {
        val p = cond.posData ?: return cond
        val area = p.limitArea
        val shiftedArea = if (area == null) {
            null
        } else {
            val r = area.toPxRect(screen.width, screen.height, screen.density)
            ZArea.fromPxRect(
                r[0] + dx, r[1] + dy,
                (r[2] - r[0]).coerceAtLeast(1), (r[3] - r[1]).coerceAtLeast(1),
                screen.width, screen.height
            )
        }
        // 指定位置（limitPosX/Y）也要跟着平移，否则"只看一个点"的子条件会原地不动
        val newExtra = p.extra.toMutableMap()
        for ((key, isX) in listOf("limitPosX" to true, "limitPosY" to false)) {
            val cur = newExtra[key]?.toString() ?: continue
            val base = if (isX) screen.width else screen.height
            val px = ZValue.parse(cur).toPx(base, screen.density) + (if (isX) dx else dy)
            newExtra[key] = "%.2f%%".format(100.0 * px / base)
        }
        return cond.copy(posData = p.copy(limitArea = shiftedArea ?: area, extra = newExtra))
    }

    private fun aroundPx(v: Any?, isX: Boolean): Int {
        val text = v?.toString()?.trim().orEmpty()
        val parsed = if (text.isEmpty()) null else ZValue.parse(text)
        val base = if (isX) screen.width else screen.height
        return parsed?.toPx(base, screen.density)?.toInt() ?: 0
    }

    private fun locate(
        pos: Z.PosData,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>,
        timeoutMs: Int = 0,
        intervalMs: Int = 200
    ): List<ZPosResolver.Hit>? {
        if (pos.type == Z.PosType.LOCATION) {
            return listOf(ZPosResolver.resolveLocation(pos, screen))
        }

        val rect = ZPosResolver.searchRect(pos, screen)
        var hits = searchPos(pos, rect)

        // 找不到就按间隔重试，直到超时（Maa 的 timeout）。
        //
        // ★ 三态语义，必须分清：
        //      timeoutMs > 0  → 等到这个时长为止
        //      timeoutMs < 0  → **一直等**（Maa 的 timeout:-1），只有「停止」能结束
        //      timeoutMs = 0  → 只试一次
        //
        //   之前只处理了 >0，于是 timeout:-1 被转成 0 之后变成"只试一次"，
        //   而"永不放弃"的节点（识别开始战斗/识别结算/boss 识别）就全都
        //   一帧没命中立刻跳过 —— 打完一关进不了继续挑战分支，就是这个。
        if (hits.isEmpty() && timeoutMs != 0 && !stopped) {
            val forever = timeoutMs < 0
            val deadline = if (forever) Long.MAX_VALUE
            else System.currentTimeMillis() + timeoutMs
            val step = intervalMs.coerceIn(20, 2000)
            var round = 0
            while (hits.isEmpty() && !stopped && System.currentTimeMillis() < deadline) {
                var slept = 0
                while (slept < step && !stopped) {
                    Thread.sleep(20L)
                    slept += 20
                }
                round++
                hits = searchPos(pos, rect)
                // 一直等的时候也要让人看得见在等什么、等了多久
                if (forever && round % 10 == 0) {
                    val secs = round * step / 1000
                    val seen = matcher.lastDetail() ?: matcher.lastReason()
                    log(
                        "[动作 $index] $label 还在等（第 $round 轮，已 ${secs}s，不设上限）" +
                            (seen?.let { " | $it" } ?: "")
                    )
                    com.pvz2.auto.core.ScriptRunState.update {
                        it.copy(message = "正在等「$label」第 $round 轮（已 ${secs}s）")
                    }
                }
            }
            if (hits.isNotEmpty()) log("[动作 $index] $label — 等了 $round 轮后找到")
        }

        if (hits.isEmpty()) {
            val why = matcher.lastReason()
            val prefix = if (timeoutMs > 0) "等了 ${timeoutMs}ms 仍" else ""
            val msg = "${prefix}没有匹配到目标" + (why?.let { "：$it" } ?: "")
            skipped.add(Result.Skip(index, label, msg))
            log("[动作 $index] $label — $msg")
            // 识别不到 → 按 Maa 的语义整个任务结束（这条没有失败跳转可走）
            abortReason = "第 ${index + 1} 条「$label」$msg"
            return null
        }

        /*
         * 选哪一个 / 哪几个。
         *
         * ★ indexNum 缺省是 **mostMatched（相似度最高）**，不是"排序第一个"
         *   （FIELDS.md:146）。以前直接取 `hits[0]` —— 而命中顺序是按位置从上到下的，
         *   于是"画面里有两个同样的图标，点分数更高的那个"变成了"点最上面那个"。
         *   分数全 0（matcher 还没传 score）时 `maxByOrNull` 取第一个，等价旧行为。
         */
    // ★ 位置周围条件：在「选第几个」**之前**过滤候选点。
    //   （作者语义：不满足的点位先被剔除，indexNum 数的才是剩下的那些）
    if (hits.isNotEmpty()) {
        hits = applyAroundFilter(pos, hits, index, skipped)
    }

        val chosen: List<ZPosResolver.Hit> = if (pos.indexNum.isNullOrBlank()) {
            listOfNotNull(hits.maxByOrNull { it.score })
        } else {
            ZPosResolver.pickIndices(pos.indexNum, hits.size).mapNotNull { hits.getOrNull(it) }
        }
        if (chosen.isEmpty()) {
            /*
             * 下标全部越界：这是**脚本配置问题**，不是"识别不到"。
             * 所以不设 abortReason —— 让上层只跳过这一条，不要因此结束整个任务。
             */
            val msg = "indexNum「${pos.indexNum}」指定的下标都超出了命中数 ${hits.size}"
            skipped.add(Result.Skip(index, label, msg))
            log("[动作 $index] $label — $msg")
            return null
        }

        // 命中后的偏移
        val ox = pos.xOffset?.toPx(screen.width, screen.density) ?: 0f
        val oy = pos.yOffset?.toPx(screen.height, screen.density) ?: 0f
        return chosen.map { hit ->
            ZPosResolver.Hit(
                x = (hit.x + ox).toInt().coerceIn(0, screen.width - 1),
                y = (hit.y + oy).toInt().coerceIn(0, screen.height - 1),
                // 分数带下去：多点动作里要按它挑，日志里也要能看到"为什么选了这个"
                score = hit.score
            )
        }
    }

    /**
     * 估一条动作要花多久（毫秒），只用来画进度条。
     *
     * 宁可估粗也不要估 0：估 0 的话进度条永远是满的或不动，
     * 用户看不出"这一条正在跑"还是"卡住了"。
     */
    /**
     * 解析 `jumpToPosition` 的写法，返回 [JumpResult]（Found / NotFound / Unsupported）。
     *
     * ## 为什么要支持这么多种
     *
     * 协议（SPEC.md:447-460）里跳转有四种写法，而真实脚本**最常用的是第一种**：
     *
     *      ":A"     → 跳到 jumpId 为 A 的动作（自动精灵原生的无限循环就这么写）
     *      "2"      → 跳到第 2 条（**1 基**）
     *      "+2"/"-1" → 相对当前前进/后退
     *      "&1"     → 子列表，未支持
     *
     * 原来只做 jumpTable 精确匹配，于是真实脚本里的 `":a"` 和 `"2"` 全都查不到，
     * 而查不到又会把 pc 推到末尾 —— 整条脚本跑一条就"完成"了。
     */
    /**
     * 跳转的三种结果。
     *
     * ★ 为什么不能用 `Int?`：`null` 同时表示"目标不存在"和"这种写法我们没实现"，
     *   上层只能一律当"不存在"终止脚本 —— 于是用户拿着 `&1` 去改 jumpId，
     *   改一辈子也改不对（我们根本不支持子列表跳转）。
     */
    private sealed interface JumpResult {
        /** 解析成功，[index] 是目标动作下标（0 基）。 */
        data class Found(val index: Int) : JumpResult

        /** 写法合法但没有目标（jumpId 不存在 / 序号越界）。 */
        data class NotFound(val message: String) : JumpResult

        /** 是协议里的写法，我们还没实现（`&1` 子动作列表）。 */
        data class Unsupported(val message: String) : JumpResult
    }

    private fun resolveJump(raw: String, current: Int, size: Int): JumpResult {
        val t = raw.trim()
        if (t.isEmpty()) return JumpResult.NotFound("跳转目标是空的")

        // ":A" —— 按 jumpId 找
        if (t.startsWith(":")) {
            val id = t.substring(1)
            val idx = jumpTableOf[id]
                ?: return JumpResult.NotFound("跳转目标「$t」不存在（没有动作的 jumpId 是「$id」）")
            return JumpResult.Found(idx)
        }

        // "&1" / "&-2" —— 子动作列表内的跳转（SPEC.md:455 / :509）
        // ★ 必须和"不存在"分开报：这是我们自己的缺口，不是脚本写错了。
        if (t.startsWith("&")) {
            return JumpResult.Unsupported(
                "「$t」是**子动作列表内部**的跳转（`&` 前缀），只在「运行多个动作」" +
                    "的子动作里有效；写在外层动作上会被忽略"
            )
        }

        // "+2" / "-1" —— 相对当前
        if (t.length > 1 && (t[0] == '+' || t[0] == '-')) {
            val delta = t.toIntOrNull()
                ?: return JumpResult.NotFound("跳转目标「$t」不是合法的相对位置")
            val target = current + delta
            if (target !in 0 until size) {
                return JumpResult.NotFound("相对跳转「$t」超出动作范围（共 $size 条）")
            }
            return JumpResult.Found(target)
        }

        // "2" —— 1 基序号
        val idx = t.toIntOrNull()
        if (idx == null) {
            // 不是数字：再按 jumpId 试一次（手写脚本里常见裸 id）
            val byId = jumpTableOf[t]
                ?: return JumpResult.NotFound("跳转目标「$t」既不是序号，也不是存在的 jumpId")
            return JumpResult.Found(byId)
        }
        val target = idx - 1
        if (target !in 0 until size) {
            // 序号越界时再按 jumpId 兜一次（历史行为：有些脚本把标识写成数字）
            jumpTableOf[t]?.let { return JumpResult.Found(it) }
            return JumpResult.NotFound("跳转序号「$t」超出范围（共 $size 条）")
        }
        return JumpResult.Found(target)
    }

    /** 由 [run] 填好，供 [resolveJump] 用。 */
    private var jumpTableOf: Map<String, Int> = emptyMap()

    /** 最近一次 locate 失败的原因；非 null 表示"识别不到"，任务应结束。 */
    private var abortReason: String? = null

    private fun estimateMs(a: Z.Action): Int {
        fun num(s: String?): Int =
            s?.substringBefore('-')?.trim()?.toIntOrNull() ?: 0

        fun toMs(value: Int, unit: Z.TimeUnit): Int = when (unit.wire) {
            0 -> value            // 毫秒
            1 -> value * 1000     // 秒
            2 -> value * 60_000   // 分
            else -> value
        }

        /*
         * 运行前等待要按**实际生效的取值**估：动作自己的 delay → 脚本级 baseInfo.delay
         * → 兜底（见 [preDelay]）。只看动作自己的 delay 时，靠 baseInfo.delay 的动作
         * 会被估成 0ms，进度条一上来就跑满。
         */
        val pre = preDelay(a)
        val preMs = toMs(num(pre.first), pre.second)
        val durMs = num(a.common.duration).takeIf { it > 0 }
            ?: if (a.type == Z.ActionType.SWIPE) 1000 else 100
        /*
         * 「等待」动作的实际时长就是 delay；没写 delay 时按 1000ms 兜底
         * （见 [delayDefaultMs]）。估 100ms 会让进度条在真正等待的 1 秒里
         * 一直显示 100% —— 用户以为卡住了。
         */
        val effectiveDur = if (a.type == Z.ActionType.CONTROL_WAIT) {
            maxOf(durMs, DEFAULT_WAIT_ACTION_MS)
        } else {
            durMs
        }
        val repeatGapMs = toMs(num(a.common.repeatDelayValue), a.common.repeatDelayUnit)
        val reps = (a.common.repeatCount ?: 1).let { if (it <= 0) 1 else it }
        // 候选等待的时长取决于画面什么时候出现，估不出来
        if (a.type == Z.ActionType.WAIT_CANDIDATES) return 0
        return (preMs + reps * (effectiveDur + repeatGapMs)).coerceIn(1, 600_000)
    }

    /**
     * 按位置类型找一遍。抽出来是因为重试要重复调用它。
     *
     * 顺带统计"真正识别耗时"（不含重试间隔）：日志里要能分清
     * "识别慢"和"在等画面"，这两件的处理方式完全不同。
     */
    private var lastSearchMs: Long = 0
    private var recognizeMs: Long = 0
    private var recognizeCount: Int = 0

    /** 进入一个新节点时清空统计。 */
    private fun resetRecognizeStats() {
        recognizeMs = 0
        recognizeCount = 0
        lastSearchMs = 0
    }

    private fun searchPos(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        val t0 = System.nanoTime()
        val r = searchPosRaw(pos, rect)
        lastSearchMs = (System.nanoTime() - t0) / 1_000_000
        recognizeMs += lastSearchMs
        recognizeCount++
        return r
    }

    private fun searchPosRaw(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> =
        when (pos.type) {
            Z.PosType.IMAGE -> matcher.findImage(pos, rect)
            Z.PosType.TEXT -> matcher.findText(pos, rect)
            Z.PosType.COLOR -> matcher.findColor(pos, rect)
            Z.PosType.NODE -> matcher.findNode(pos, rect)
            else -> emptyList()
        }

    // ------------------------------------------------------------ 条件

    /**
     * 判断条件是否成立。
     *
     * ## 判不出来时按「不成立」处理
     *
     * 这是我自定的规则，不是协议要求的，但更安全：
     * 条件出错时如果按成立处理，动作会**乱点**；按不成立处理只是不动。
     * 用户看到"没执行"会来查，看到"点错了"却查不出来。
     */
    /**
     * 带重试的条件判断 —— 识别次数 / 识别间隔在这里生效。
     *
     * @return 最终是否成立
     *
     * 规则照 FIELDS.md:256-258：
     *   repeatWhenFalse = false → 只判一次
     *   repeatWhenFalseLimitTimes = 0 → 无限重试，N → 最多判 N 次
     *   repeatWhenFalseRepeatDelay → 每次之间的间隔（毫秒）
     *
     * 睡眠切 20ms 小片，保证「停止」能及时生效。
     */
    private fun evaluateWithRetry(
        cond: Z.Condition,
        index: Int,
        skipped: MutableList<Result.Skip>
    ): Boolean {
        /*
         * ★ 「判不了」和「不成立」必须分开。
         *
         *   有 6 种情况 evaluate() 永远返回 false 且不可能变真：
         *   未实现的类型（aiCloud）、独立使用周围条件、识别类条件没配位置、
         *   JS 表达式为空/语法错、条件嵌套过深。
         *   勾了「不成立时反复检查」（协议缺省 limit=0 = 无限）时，
         *   原来会**无限自旋**：状态行一直显示"正在等条件"，
         *   跟"正常等画面"完全分不出来，而且每轮往 skipped 里塞一条，
         *   跑几小时就是几万条。用户只能手动停止。
         *
         *   所以先判一次"这个条件本机到底判不判得了"，判不了就直接返回，
         *   不进循环。
         */
        undecidableReason(cond)?.let { why ->
            val msg = "条件判不了（$why），按不成立处理，不进入反复检查"
            log("[跳过] $msg")
            skipped.add(Result.Skip(index, "条件", msg))
            return false
        }

        val first = evaluate(cond, index, skipped)
        if (first || !cond.repeatWhenFalse) return first

        /*
         * ★ limit 是"**额外**重复检查的次数"，不是总次数。
         *
         *   协议原实现（自动精灵 m4506.js）：
         *       const t = LimitTimes || 0; let o = 1;
         *       for (; (0 == t || o <= t) && (... r = await n(), !r);) o++
         *   循环体先检查再 o++，o 从 1 走到 t —— 即第 1 次之外**再做 t 次**。
         *   界面文案也写着"额外重复检查的上限次数，0 代表无限"。
         *
         *   原先写成 `attempt >= limit` 直接 break：limit=1 变成"总共只判 1 次"
         *   = 0 次重试，比协议少一次。而真实脚本普遍写
         *   `repeatWhenFalseLimitTimes: 1`（首帧没出现就再等 100ms 看一眼），
         *   少这一次会成片漏动作。
         */
        val limit = cond.repeatWhenFalseLimitTimes ?: 0
        val gap = (cond.repeatWhenFalseRepeatDelay ?: 1000).coerceIn(20, 60_000)
        var retries = 0
        val startedAt = System.currentTimeMillis()
        while (!stopped) {
            if (limit > 0 && retries >= limit) break
            var slept = 0
            while (slept < gap && !stopped) {
                Thread.sleep(20L)
                slept += 20
            }
            if (stopped) break
            retries++
            if (evaluate(cond, index, skipped)) {
                log("[条件] 额外识别第 $retries 次成立")
                return true
            }
            /*
             * ★ 每 5 轮上报一次。
             *
             *   无限重试（limit = 0）时如果什么都不打，界面上就是**彻底静止**——
             *   看起来和死循环、卡死一模一样，用户只能强杀进程。
             *   实测已发生：第 1 个节点"卡住"，其实是在一遍遍 OCR 等画面。
             *
             *   这里把"在等什么、为什么没等到"同时推给日志和运行状态，
             *   第 2 页的状态行、第 3 页的节点进度都能看见。
             */
            // 每次都推给界面（重试频率只有几十 Hz，开销可忽略）：
            // 用户需要看到"它在拼命识别"而不是"它卡住了"。
            val why = matcher.lastReason() ?: "条件不成立"
            com.pvz2.auto.core.ScriptRunState.retry(retries, why)
            if (retries % 5 == 0) {
                val secs = (System.currentTimeMillis() - startedAt) / 1000
                log("[条件] 第 $retries 次仍不成立（已 ${secs}s）：$why")
                com.pvz2.auto.core.ScriptRunState.update {
                    it.copy(message = "正在等条件：第 $retries 次（已 ${secs}s）· $why")
                }
            }
        }
        return false
    }

    /** 这个条件是不是"看画面"的识别类条件。 */
    private fun isRecognitionCondition(c: Z.Condition): Boolean = when (c.type) {
        Z.ConditionType.IMAGE, Z.ConditionType.TEXT, Z.ConditionType.COLOR -> true
        else -> false
    }

    private fun evaluate(
        cond: Z.Condition,
        index: Int,
        skipped: MutableList<Result.Skip>,
        depth: Int = 0
    ): Boolean {
        if (depth > MAX_COND_DEPTH) {
            // 畸形文件可能把条件自己套自己几百层 —— 直接算会 StackOverflowError，
            // 报出来比崩掉强（崩掉时界面只能显示"执行异常：null"）。
            log("   条件嵌套超过 $MAX_COND_DEPTH 层，视为不成立")
            skipped.add(Result.Skip(index, "条件", "条件嵌套超过 $MAX_COND_DEPTH 层"))
            return false
        }
        val raw = when (cond.type) {
            Z.ConditionType.IMAGE, Z.ConditionType.TEXT,
            Z.ConditionType.COLOR, Z.ConditionType.NODE -> {
                /*
                 * ★ 没配位置 → 判不出来，**不取反**。
                 *   和下面 else 分支同一个道理：false 再被 runWhenFalse 翻成 true，
                 *   动作就变成"无条件执行"，而界面上这个条件看着配得好好的。
                 */
                val pos = cond.posData ?: run {
                    log("   条件 ${cond.type.wire}：没有位置数据，判不了（不取反）")
                    skipped.add(Result.Skip(index, "条件", "条件 ${cond.type.wire} 没配位置，视为不成立"))
                    return false
                }
                val rect = ZPosResolver.searchRect(pos, screen)
                val hits = when (cond.type) {
                    Z.ConditionType.IMAGE -> matcher.findImage(pos, rect)
                    Z.ConditionType.TEXT -> matcher.findText(pos, rect)
                    Z.ConditionType.COLOR -> matcher.findColor(pos, rect)
                    else -> matcher.findNode(pos, rect)
                }
                val ok = hits.isNotEmpty()
                log(
                    "   条件 ${cond.type.wire}: " +
                        if (ok) "成立（命中 ${hits.size} 处）"
                        else "不成立（${matcher.lastReason() ?: "未命中"}）"
                )
                ok
            }

            Z.ConditionType.RANDOM -> {
                val p = (cond.extra["percent"] as? Number)?.toInt() ?: 50
                (0 until 100).random() < p
            }

            // ---- 多个条件（FIELDS.md:277 conditionSet / 278 AroundCondition）
            Z.ConditionType.CONDITION_SET -> combineConditions(cond, index, skipped, depth)

            /*
             * 独立的 `AroundCondition` 在协议里**不成立**：
             * 官方实现第一句就是 `if (willCheckX<0||willCheckY<0) throw ...`
             * —— 它只能在 `posData.filterByAroundConditionSet` 上用（那一路我们已实现）。
             * 这里如实报不成立，且**不参与取反**（抛异常 ≠ false）。
             */
            Z.ConditionType.AROUND -> {
                log("   条件 周围条件：独立使用时协议不支持（只能挂在位置上），视为不成立")
                skipped.add(
                    Result.Skip(index, "条件", "独立的周围条件不被协议支持，视为不成立")
                )
                return false
            }

            // ---- 限制运行次数（FIELDS.md:271）
            Z.ConditionType.LIMIT_RUN_TIMES -> {
                val limit = (cond.extra["limitTimes"] as? Number)?.toInt() ?: 0
                val key = condKey(cond)
                // resetTime 决定"脚本整体重复"时要不要清零：
                //   afterRepeat（每轮清零） / afterFinish（默认，动作跑完清零）
                //   onLoad（只在载入时清零 —— 整个运行只给 N 次）
                limitResetTime[key] = cond.extra["resetTime"]?.toString()?.trim().orEmpty()
                    .ifBlank { "afterFinish" }
                // syncNowRunTimesToVarName：协议要求把当前次数同步到脚本变量
                cond.extra["syncNowRunTimesToVarName"]?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { varName -> scriptVars[varName] = (limitUsed[key] ?: 0).toString() }
                val used = limitUsed[key] ?: 0
                val ok = limit <= 0 || used < limit
                if (ok) limitUsed[key] = used + 1
                log("   条件 限制运行次数：已用 $used / $limit → ${if (ok) "成立" else "不成立"}")
                ok
            }

            // ---- 动作运行状态（FIELDS.md:272）
            Z.ConditionType.SCRIPT_RUN_STATE -> {
                val aim = cond.extra["aimPosition"]?.toString()?.trim().orEmpty()
                val want = cond.extra["aimScriptRunState"]?.toString()?.trim() ?: "suc"
                /*
                 * ★ aimPosition 有四种写法（80865.js 的字段帮助）：
                 *     2   → 列表里第 2 个动作
                 *     +2  → 当前位置**后面**第 2 个
                 *     -1  → 当前位置**前面** 1 个
                 *     :A  → 跳转标识为 A 的动作
                 *   原来只用 `toIntOrNull()`：`+2` 被当成 2（错条）、`-1` 变成 -2（错条）、
                 *   `:A` 查表查的是裸 id（永远查不到）→ "未运行"条件恒真，守卫失效。
                 */
                val target = when {
                    aim.startsWith(":") -> jumpTableOf[aim.substring(1)]
                    aim.startsWith("+") || aim.startsWith("-") -> aim.toIntOrNull()?.let { index + it }
                    else -> aim.toIntOrNull()?.minus(1)
                }?.takeIf { it in runActions.indices }
                val ran = target?.let { actionOutcome[it] }
                val ok = when (want) {
                    "suc" -> ran == true
                    "fail" -> ran == false
                    "notRun" -> ran == null
                    else -> false
                }
                log("   条件 动作运行状态：「$aim」要 $want，实际 ${ran ?: "未运行"} → $ok")
                ok
            }

            // ---- 在时间之后（FIELDS.md:274）
            Z.ConditionType.TIME_AFTER -> {
                val v = cond.extra["timeValue"]?.toString().orEmpty()
                val ok = timeAfterPassed(v, condKey(cond))
                log("   条件 在时间之后「$v」→ $ok")
                ok
            }

            // ---- 时间间隔（FIELDS.md:275）
            Z.ConditionType.TIME_INTERVAL -> {
                val v = cond.extra["timeIntervalValue"]?.toString()
                val unit = (cond.extra["timeIntervalUnit"] as? Number)?.toInt() ?: 1
                val gapMs = (randomIn(v) ?: 0.0).toLong() * unitToMs(unit)
                val now = System.currentTimeMillis()
                val key = condKey(cond)
                val anchor = intervalLast[key]
                val ignoreInit = boolExtra(cond, "ignoreInitPass")
                /*
                 * ★ ignoreInitPass 的语义是"**首次条件检查不满足**"。
                 *
                 *   自动精灵（91969.js）:
                 *     if (null == lastConditionPassTime)
                 *         return this.lastConditionPassTime = Date.now(), !this.condition.ignoreInitPass;
                 *
                 *   也就是：第一次检查先把基准时间记下来，
                 *   勾了 ignoreInitPass 就**返回不通过**，没勾就通过。
                 *   我上一版写反了（勾了就立刻通过），等于这个开关唯一的作用消失。
                 */
                val ok = if (gapMs <= 0L) {
                    true
                } else if (anchor == null) {
                    intervalLast[key] = now
                    !ignoreInit
                } else if (now - anchor >= gapMs) {
                    intervalLast[key] = now
                    true
                } else {
                    false
                }
                log(
                    "   条件 时间间隔 ${gapMs}ms：" +
                        (if (anchor == null) "首次" else "距上次 ${now - anchor}ms") + " → $ok"
                )
                ok
            }

            // ---- JS 表达式（FIELDS.md:273）
            Z.ConditionType.JS_EXPRESSION -> {
                val expr = cond.extra["expression"]?.toString().orEmpty()
                if (expr.isBlank()) {
                    log("   条件 JS 表达式：expression 是空的，判不了")
                    skipped.add(Result.Skip(index, "条件", "JS 表达式是空的"))
                    return false
                }
                val v = JsEngine.evalBool(expr, jsEnv())
                if (v == null) {
                    // 求值失败 ≠ false，更不能被 runWhenFalse 翻成 true
                    log("   条件 JS 表达式「$expr」求值失败（语法/运行时错误）")
                    skipped.add(Result.Skip(index, "条件", "JS 表达式求值失败：$expr"))
                    return false
                }
                log("   条件 JS 表达式「$expr」→ $v")
                v
            }

            // 其余条件安卓上暂时判不了
            else -> {
                /*
                 * ★ 判不出来 ≠ 假，而且**不能被取反**。
                 *
                 *   以前这里返回 false 再走 applyInvert：一旦用户勾了
                 *   「条件不成立时执行」（runWhenFalse），false 立刻变成 true ——
                 *   动作**无条件执行**。8 种未实现的条件（jsExpression /
                 *   conditionSet / limitRunTimes / timeInterval / timeAfter /
                 *   scriptRunState / aiCloud / AroundCondition）全踩这个坑，
                 *   而界面上它们配置得好好的，完全看不出来。
                 */
                log("条件 ${cond.type.wire} 暂不支持，按不成立处理（不参与取反）")
                skipped.add(
                    Result.Skip(index, "条件", "条件类型 ${cond.type.wire} 未实现，视为不成立")
                )
                return false
            }
        }
        return applyInvert(cond, raw)
    }

    /**
     * 这个条件在本机**能不能判**。null = 能判。
     *
     * 返回非 null 的条件，evaluate() 必然恒为 false 且不会变真 ——
     * 拿去反复检查就是死循环（见 [evaluateWithRetry]）。
     */
    private fun undecidableReason(cond: Z.Condition): String? = when (cond.type) {
        Z.ConditionType.AI_CLOUD -> "AI 云识别需要云端接口"
        Z.ConditionType.AROUND -> "周围条件不能单独当条件用"
        Z.ConditionType.JS_EXPRESSION ->
            if (cond.extra["expression"]?.toString().isNullOrBlank()) "JS 表达式为空" else null
        Z.ConditionType.IMAGE, Z.ConditionType.TEXT,
        Z.ConditionType.COLOR, Z.ConditionType.NODE ->
            if (cond.posData == null) "识别类条件没有配位置" else null
        else -> null
    }

    /**
     * 纯逻辑条件"当时为什么判不过"的参数。
     *
     * 这类条件没有匹配器可问，只能摊出自己的值：间隔多长、
     * 限制了 N 次已经用完、要求某个动作处于什么状态。
     */
    private fun condDetail(cond: Z.Condition): String? = when (cond.type) {
        Z.ConditionType.TIME_INTERVAL ->
            "间隔 ${cond.extra["timeIntervalValue"] ?: "?"} 还没到"
        Z.ConditionType.LIMIT_RUN_TIMES ->
            "限制 ${cond.extra["limitTimes"] ?: "?"} 次已经用完"
        Z.ConditionType.SCRIPT_RUN_STATE ->
            "要求「${cond.extra["aimPosition"] ?: "?"}」为 " +
                "${cond.extra["aimScriptRunState"] ?: "?"}，当前不符"
        Z.ConditionType.TIME_AFTER ->
            "还没到时间「${cond.extra["timeValue"] ?: "?"}」"
        Z.ConditionType.CONDITION_SET ->
            "组合条件（${cond.extra["matchMode"] ?: "and"}）不满足"
        Z.ConditionType.RANDOM -> "随机未命中"
        else -> null
    }

    /** 反相（FIELDS.md:254）。 */
    private fun applyInvert(cond: Z.Condition, value: Boolean): Boolean =
        if (cond.runWhenFalse) !value else value

    // ------------------------------------------------------------ 工具

    /**
     * 等待。
     *
     * `delay` 支持区间写法 `"1-3"`（协议里表示范围内随机），
     * 这时取一个随机值 —— 用在"每次等待时长不同"的反检测场景。
     */
    /**
     * 等待。
     *
     * [defaultMs]：没写 delay 时用多少毫秒。
     *
     * Maa 的 pre_delay / post_delay 默认都是 200ms，所以「真的去操作画面」的动作
     * 走默认 200ms；控制流动作（跳转、等待候选）传 0 —— 否则「无限循环 + 每步 200ms」
     * 的脚本会在死循环保护生效前爬几小时，测试里那个死循环用例直接跑不完。
     */
    private fun waitDelay(value: String?, unit: Z.TimeUnit, defaultMs: Int = 0) {
        val n = randomIn(value)
        val total: Long = if (n == null) {
            defaultMs.toLong()
        } else {
            when (unit) {
                Z.TimeUnit.MILLISECONDS -> n
                Z.TimeUnit.SECONDS -> n * 1000
                Z.TimeUnit.MINUTES -> n * 60_000
                Z.TimeUnit.HOURS -> n * 3_600_000
            }.toLong()
        }
        if (total <= 0) return
        // 分片睡眠：否则 delay 填成「分钟/小时」时，点停止要等满整段才生效
        var left = total
        try {
            while (left > 0 && !stopped) {
                val step = minOf(20L, left)
                Thread.sleep(step)
                left -= step
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stopped = true
        }
    }

    /**
     * 没写 delay（动作级和脚本级都没有）时的兜底毫秒数。
     *
     * - **「等待」动作**（controlRun:`waitDelay`）：1000ms。协议里 `delay` 是这个
     *   动作唯一的时长来源（FIELDS.md:559），没有它"等待"就是空操作 ——
     *   以前传 0，用户放一条"等 2 秒"完全不生效。
     * - **有位置/起终点的交互动作**：200ms（Maa 的 pre_delay 默认值）。
     * - **其余控制流**（跳转、等待候选、停止…）：0 —— 否则「无限循环 + 每步 200ms」
     *   会让死循环保护形同虚设（测试里那个死循环用例会跑几小时）。
     */
    private fun delayDefaultMs(a: Z.Action): Int = when {
        a.type == Z.ActionType.CONTROL_WAIT -> DEFAULT_WAIT_ACTION_MS
        a.posData != null || a.startPos != null -> DEFAULT_DELAY_MS
        else -> 0
    }

    /**
     * 这条动作"运行前等待"的实际取值。
     *
     * 优先级（FIELDS.md:113）：动作自己的 `delay` → 脚本级 `baseInfo.delay`
     * → 由 [delayDefaultMs] 给的兜底。以前只用了兜底 200ms，
     * 脚本级的全局节奏从来没被读过。
     */
    private fun preDelay(a: Z.Action): Pair<String?, Z.TimeUnit> {
        val own = a.common.delay
        if (!own.isNullOrBlank()) return own to a.common.delayUnit
        val scriptDefault = scriptBaseDelay
        /*
         * ★ 脚本级 delay 是 "0" 时**不算指定**。
         *
         * 很多脚本（包括我们转换出来的）baseInfo 里写着 delay:"0"，意思只是
         * "没有全局额外节奏"；把它当成"每条都等 0ms"就会把动作自己的默认 200ms
         * 一起压掉 —— 真机表现是：编辑器里显示「等待 200（默认）」，
         * 实际日志里一条滑动只用了 4ms，连点全挤在一起。
         */
        if (!scriptDefault.isNullOrBlank() && (scriptDefault.trim().toDoubleOrNull() ?: 0.0) > 0.0) {
            return scriptDefault to scriptBaseDelayUnit
        }
        return null to a.common.delayUnit
    }

    /**
     * `fastRepeatMode` 的复用缓存（FIELDS.md:118）。
     *
     * 首轮识别出来的坐标存在这里，后面几轮直接用 —— 连点同一个位置时，
     * 每轮都重跑一次模板匹配是纯浪费（而且每次都要跨进程取帧）。
     */
    private class RepeatMemo {
        /** 点击类的首轮命中点。 */
        var hits: List<ZPosResolver.Hit>? = null

        /** 滑动类的首轮起点命中点。 */
        var starts: List<ZPosResolver.Hit>? = null

        /** 滑动类的首轮终点命中点。 */
        var ends: List<ZPosResolver.Hit>? = null
    }

    /**
     * 对多个命中点依次点击。
     *
     * `indexNum = "1,3,4"` 的语义是"这三个都点"（FIELDS.md:151-154）。
     * 以前随机挑一个 —— "关 3 个弹窗"每轮只关 1 个，剩下 2 个永远漏，
     * 而脚本看起来"跑完了"。
     */
    private fun tapHits(
        hits: List<ZPosResolver.Hit>,
        hold: Int,
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>,
        afterLocateMs: Int = 0
    ) {
        if (afterLocateMs > 0) {
            log("   识别后等待 ${afterLocateMs}ms 再点（Maa pre_delay 位置）")
            waitDelay(afterLocateMs.toString(), Z.TimeUnit.MILLISECONDS, afterLocateMs)
            if (stopped) return
        }
        hits.forEachIndexed { i, hit ->
            if (stopped) return
            if (i > 0) waitMultiGap(action)
            log(
            if (hold <= 0) "   点击 (${hit.x},${hit.y}) 极速模式（不等点击结束）"
            else "   点击 (${hit.x},${hit.y}) 按住 ${hold}ms"
        )
            try {
                inject.tap(hit.x, hit.y, hold)
            } catch (e: Throwable) {
                /*
                 * 一个点注入失败**不该**让整条脚本以"执行异常"结束 ——
                 * 与 KEY / INPUT_TEXT 同一约定：记一条跳过原因再收手。
                 * 以前这个异常会穿到 runZjs 的 runCatching，
                 * 表现是"跑着跑着整个脚本没了，日志只有一行执行异常"。
                 */
                val why = "点击 (${hit.x},${hit.y}) 失败：${e.message}"
                skipped.add(Result.Skip(index, label, why))
                log("   $why")
                return
            }
        }
    }

    /**
     * 多点动作里"第 2 个点之前"的间隔。
     *
     * 同一条动作选出的多个点，间隔按**重复间隔**走（`repeatDelayValue`，
     * 缺省回落 `delay`，FIELDS.md:116）。一口气连发在游戏侧常被合并成一次，
     * 于是"点 3 个"实际只生效 1 个 —— 又是一次漏点。
     */
    private fun waitMultiGap(action: Z.Action) {
        val c = action.common
        val hasOwn = !c.repeatDelayValue.isNullOrBlank()
        val v = if (hasOwn) c.repeatDelayValue else c.delay
        val u = if (hasOwn) c.repeatDelayUnit else c.delayUnit
        if (v.isNullOrBlank()) {
            /*
             * ★ 两个都没写时**不能**零间隔。
             *
             *   `"1,3,4"` 三个点会在几毫秒内连发，游戏侧常把它们合并成一次点击 ——
             *   于是"点 3 个"实际只生效 1 个，正是这条修复要治的病。
             *   兜底与动作缺省等待同源（交互动作 200ms / 控制类 0）。
             */
            waitDelay(null, Z.TimeUnit.MILLISECONDS, delayDefaultMs(action))
            return
        }
        waitDelay(v, u, 0)
    }

    // ------------------------------------------------ 「运行多个动作」子列表

    /**
     * 执行「运行多个动作」的子动作列表（协议 10.16 / FIELDS.md:527-536）。
     *
     * ## 为什么必须真跑
     *
     * 真实脚本的主体经常就是它 —— SPEC.md:824 那段里每个节点都是一个
     * 「运行多个动作」。以前整块跳过：脚本"跑完 N 条"，游戏一步没动。
     *
     * ## 作用域：哪些能出去、哪些不能
     *
     *  - **跳转不出去**：子列表里的 `jumpToPosition` 用的是子列表内的位置，
     *    拿它去改外层 pc 会跳到完全无关的地方。记 log 后忽略。
     *  - **停止按协议出去**：`ContinueParentExecute=false`（默认）就是全停；
     *    只有 `true` 才只结束本列表（那一条在 [executeOnce] 里已经折成 breakShell）。
     *  - **识别失败不出去**：只有顶层动作识别失败才按 Maa 语义终止任务。
     */
    /**
     * 跑一串动作（手势轨迹 `gestureActions` 用）。
     *
     * 复用 [runMulti] 的那套循环：limitRunCount / breakCondition / `&` 子列表跳转 /
     * ContinueParentExecute 语义全都一样 —— 协议的 `gestureActions` 本来就是
     * "逐个执行的普通动作数组"（FIELDS.md 10.8/10.9），不该另写一套。
     */
    private fun runActionList(
        owner: Z.Action,
        list: List<Z.Action>,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? = runMulti(
        owner.copy(scriptSet = list),
        index,
        label,
        skipped
    )

    private fun runMulti(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        val kids = action.scriptSet
        if (kids.isEmpty()) {
            /*
             * 空列表有**两种**原因，必须分开报：
             *  - 脚本里真的没写子动作；
             *  - 模型还没把 scriptSet 解析出来（那时它还在 extra 里躺着）。
             * 第二种是接线问题，不说清楚的话表现就是"运行多个动作 = 空操作"。
             */
            val hint = if (action.extra.containsKey("scriptSet")) {
                "（scriptSet 仍在 extra 里，ZModel 尚未解析）"
            } else {
                ""
            }
            val why = "没有子动作$hint"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $label — $why")
            return null
        }
        if (subShellDepth >= MAX_SUB_SHELL_DEPTH) {
            val why = "子动作列表嵌套超过 $MAX_SUB_SHELL_DEPTH 层，已停止递归"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $label — $why")
            return null
        }

        // runMode：协议有 normal / random / multiThread，我们只有顺序执行
        val mode = action.extra["runMode"]?.toString()?.trim().orEmpty()
        if (mode.isNotEmpty() && mode != "normal") {
            log("[$index] $label runMode=$mode 暂按 normal（顺序）执行")
        }
        // limitRunCount：只跑前 N 个（0 / 缺省 = 不限）
        val limit = numExtra(action, "limitRunCount")
        // limitRunCountOnlySuc：只有成功的才占名额
        val onlySuc = boolExtra(action, "limitRunCountOnlySuc")
        // breakCondition：每跑完一个子动作检查一次（SPEC.md:1080）
        val breakCond = conditionExtra(action, "breakCondition")

        var ran = 0
        var counted = 0
        var failed = 0
        val childSkips = ArrayList<Result.Skip>()
        subShellDepth++
        try {
            /*
             * ★ 用 while 而不是 for：子列表内的 `&N` 跳转要能**往回跳**。
             *
             *   协议（SPEC.md 5.2/5.3）：在子列表里拿到 `&` 跳转时，
             *   语义是「**当前这个子列表**从第 N 条重新开始」，
             *   相对写法是相对当前子动作的下标。
             *   以前这里一律打一行「跳转被忽略」就过去了 —— 于是真实脚本里
             *   用 `&-3` 做循环的子列表会**只跑一遍**，而且日志看起来像正常执行。
             */
            var ci = 0
            var jumps = 0
            while (ci < kids.size) {
                if (stopped) break
                if (limit > 0 && counted >= limit) {
                    log("[$index] $label 已跑够 limitRunCount=$limit 个，结束本列表")
                    break
                }
                val kid = kids[ci]
                val kidLabel = "$label › 子${ci + 1}「${ZScriptIO.actionLabel(kid)}」"
                ran++
                // 子动作：识别失败只跳过这一条（不牵连外层任务）
                val outcome = executeWithRepeat(
                    kid, index, kidLabel, childSkips, abortOnRecognitionFail = false
                )
            
                // 停止：协议默认语义就是全停，子列表不能把它吞掉
                if (outcome.stop) {
                    log("[$index] $kidLabel 请求停止整个脚本")
                    return Outcome(stop = true)
                }
            
                // 名额：只有开启 limitRunCountOnlySuc 时失败才不占名额
                val success = outcome.abort == null
                if (!onlySuc || success) counted++
            
                if (outcome.breakShell) {
                    log("[$index] $kidLabel 要求只结束本子列表（ContinueParentExecute）")
                    break
                }
            
                if (outcome.abort != null) {
                    /*
                     * 子动作识别失败**不终止整条任务**：列表里某一条没认出来，
                     * 通常只意味着这一条没命中，后面的还该跑。
                     */
                    failed++
                    log("[$index] $kidLabel 识别失败（子动作，不终止任务）：${outcome.abort}")
                    abortReason = null
                }
            
                // ---- 子列表内跳转（`&N` / `&+N` / `&-N`）
                val jump = outcome.jumpTo
                if (jump != null) {
                    val spec = jump.trim().removePrefix("&")
                    if (jump.trim().startsWith("&")) {
                        val target = if (spec.startsWith("+") || spec.startsWith("-")) {
                            ci + (spec.toIntOrNull() ?: 0)
                        } else {
                            (spec.toIntOrNull() ?: 0) - 1   // 1 基
                        }
                        if (target in kids.indices) {
                            jumps++
                            if (jumps > MAX_SUB_JUMPS) {
                                log("[$index] $label 子列表跳转超过 $MAX_SUB_JUMPS 次，强制结束（八成是死循环）")
                                break
                            }
                            log("[$index] $kidLabel 子列表跳转到第 ${target + 1} 条（$jump）")
                            ci = target
                            continue
                        }
                        log("[$index] $kidLabel 子列表跳转「$jump」超出本列表范围（共 ${kids.size} 条），忽略")
                    } else {
                        log("[$index] $kidLabel 的跳转「$jump」指向外层脚本，被忽略（作用域是子列表）")
                    }
                }
            
                if (breakCond != null && evaluate(breakCond, index, childSkips)) {
                    log("[$index] $label 中止条件（breakCondition）命中，结束本列表")
                    break
                }
                ci++
            }
        } finally {
            subShellDepth--
        }

        // 子动作的失败明细只进日志（数量可能很大，塞进 Result 会刷屏），
        // 但**汇总一条**进结果，免得用户以为"子列表全部成功"。
        childSkips.forEach { log("[$index] $label 子动作跳过：${it.label} — ${it.reason}") }
        if (failed > 0) {
            skipped.add(Result.Skip(index, label, "子动作里有 $failed 条识别失败（未终止任务）"))
        }
        log(
            "[$index] $label 子动作执行 $ran 个，计入 $counted 个" +
                (if (onlySuc) "（仅统计成功）" else "")
        )
        return Outcome()
    }

    // ---------------------------------------------------- extra 读值小工具

    /** 条件的 extra 里读布尔（时间间隔的 ignoreInitPass 用）。 */
    private fun boolExtra(cond: Z.Condition, key: String): Boolean = when (val v = cond.extra[key]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.trim().let { it == "true" || it == "1" || it == "是" }
        else -> false
    }

    /** 从 `extra` 读布尔（协议里可选字段可能缺失、也可能是字符串）。 */
    private fun boolExtra(action: Z.Action, key: String): Boolean = when (val v = action.extra[key]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.trim().let { it == "true" || it == "1" || it == "是" }
        else -> false
    }

    /** 从 `extra` 读整数（缺失 / 非法 → 0）。 */
    private fun numExtra(action: Z.Action, key: String): Int {
        val v = action.extra[key] ?: return 0
        return (v as? Number)?.toInt() ?: v.toString().trim().toIntOrNull() ?: 0
    }

    /**
     * 从 `extra` 读一个条件对象（`breakCondition` 这类还没建模的字段）。
     *
     * 形状可能是 JSONObject / JSON 文本 —— 两种都收，解析失败返回 null（当作没配）。
     */
    private fun conditionExtra(action: Z.Action, key: String): Z.Condition? {
        val raw = action.extra[key] ?: return null
        val obj = runCatching {
            when (raw) {
                is org.json.JSONObject -> raw
                is String -> org.json.JSONObject(raw)
                else -> org.json.JSONObject(raw.toString())
            }
        }.getOrNull() ?: return null
        return Z.Condition.parse(obj)
    }

    /**
     * 会读 `__vars` 的字段。
     *
     * 只列真正参与执行的：这几个字段被变量化后，一旦按"没写"处理就会改变脚本行为。
     * 其余字段（desc、limitArea…）变量化不代换也不影响执行，不要因此把动作跳掉。
     */
    private val WATCHED_VAR_FIELDS = listOf("delay", "duration", "repeatCount", "posData")

    /**
     * 「限制运行次数」的计数。
     *
     * ★ 键必须是**条件实例**，不能是动作下标：
     *   自动精灵把计数存在 `WeakMap`、以条件对象为键（6005.js），
     *   每个条件各算各的。用下标的话，同一个 `conditionSet` 里两个
     *   限制条件会共用一格、每轮各 +1（等于「只跑 N 次」变成「跑 N/2 次」），
     *   子动作又会复用父动作的下标。
     */
    /** 每个「限制运行次数」条件的 resetTime（决定脚本整体重复时清不清零）。 */
    private val limitResetTime = HashMap<Int, String>()

    private val limitUsed = HashMap<Int, Int>()

    /** 每个动作最近一次运行结果：true=成功 / false=失败 / 缺键=还没跑过。 */
    private val actionOutcome = HashMap<Int, Boolean>()

    /** 只出现在「运行多个动作」子动作里的跳转标识（跳进去本机还不支持）。 */
    private val subJumpIds = HashSet<String>()

    /** 本次 run 的动作列表（条件里要判断"目标动作下标是否合法"）。 */
    private var runActions: List<Z.Action> = emptyList()

    /** 本次 run 的开始时刻：`+HH:MM:SS` 这种"运行 N 小时后"要以它为基准。 */
    private var runStartedAt: Long = 0L

    /** 「时间间隔」上次通过的时刻（毫秒），键 = 条件实例。 */
    private val intervalLast = HashMap<Int, Long>()

    /** 「在时间之后」+N 写法的基准时刻，键 = 条件实例（首次检查时确定）。 */
    private val timeAfterAnchor = HashMap<Int, Long>()

    /** 条件实例的稳定键（Condition 是 data class，用身份而不是 equals）。 */
    private fun condKey(cond: Z.Condition): Int = System.identityHashCode(cond)

    private fun unitToMs(unit: Int): Long = when (unit) {
        0 -> 1L                    // ms
        1 -> 1000L                 // s
        2 -> 60_000L               // min
        else -> 3_600_000L         // h
    }

    /**
     * 组合条件（`conditionSet` / 独立 `AroundCondition`）。
     *
     * matchMode：`and` 全满足 / `or` 任一 / `count` 满足数 >= matchCount。
     * 子条件走同一个 [evaluate]，所以取反、未实现类型的行为都一致。
     */
    private fun combineConditions(
        cond: Z.Condition,
        index: Int,
        skipped: MutableList<Result.Skip>,
        depth: Int = 0
    ): Boolean {
        val subs = subConditionsOf(cond)
        if (subs.isEmpty()) {
            log("   条件 ${cond.type.wire}：没有子条件，按不成立处理")
            return false
        }
        val mode = cond.extra["matchMode"]?.toString()?.trim().orEmpty().ifBlank { "and" }
        // 官方：matchMode=count 且 matchCount 缺省时是 `>= (n||0)` = **恒真**。
        // 原来缺省取 subs.size（要求全满足），行为正好相反。
        val need = (cond.extra["matchCount"] as? Number)?.toInt()
            ?: if (mode == "count") 0 else subs.size
        var okCount = 0
        for (sub in subs) {
            if (sub.disabled) continue
            if (evaluate(sub, index, skipped, depth + 1)) okCount++
        }
        val ok = when (mode) {
            "or" -> okCount > 0
            "count" -> okCount >= need
            else -> okCount == subs.count { !it.disabled }
        }
        log("   条件 ${cond.type.wire}（$mode）：$okCount/${subs.size} → $ok")
        return ok
    }

    /** 取子条件数组（可能是 JSONArray，也可能是 JSON 文本）。 */
    private fun subConditionsOf(cond: Z.Condition): List<Z.Condition> {
        val raw = cond.extra["conditions"] ?: return emptyList()
        val arr = runCatching {
            when (raw) {
                is org.json.JSONArray -> raw
                is String -> org.json.JSONArray(raw)
                else -> org.json.JSONArray(raw.toString())
            }
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Z.Condition>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { o -> Z.Condition.parse(o)?.let { out.add(it) } }
        }
        return out
    }

    /**
     * 「在时间之后」。
     *
     * 协议给了三种写法（FIELDS.md:274）：
     *  - `2019=01=01 09:30:58` 指定日期时间
     *  - `09:30:58`            今天这个时刻
     *  - `+02:00:00`           从**脚本开始运行**算起 2 小时后
     */
    private fun timeAfterPassed(value: String, key: Int): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        val now = System.currentTimeMillis()
        if (v.startsWith("+")) {
            /*
             * ★ `+HH:MM:SS` 的基准是**这个条件第一次被检查的时刻**，不是脚本开始时刻。
             *
             *   自动精灵（10426.js）：`parsedTime = Date.now() + offset`，
             *   首次算出来后缓存（`null==parsedTime || "+"!==t[0]` 才重算）。
             *   用脚本开始时刻做基准的话，脚本跑了 10 分钟才走到这一条，
             *   `+00:00:05` 会立刻成立 —— 而作者要的是"到这里再等 5 秒"。
             */
            val anchor = timeAfterAnchor.getOrPut(key) { now }
            return now >= anchor + durationMs(v.substring(1))
        }
        if (v.contains("=")) {
            val m = Regex("""(\d{4})=(\d{1,2})=(\d{1,2})\s+(\d{1,2}):(\d{2}):(\d{2})""").find(v)
                ?: return false
            val cal = java.util.Calendar.getInstance().apply {
                set(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1,
                    m.groupValues[3].toInt(), m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(), m.groupValues[6].toInt()
                )
                set(java.util.Calendar.MILLISECOND, 0)
            }
            return now >= cal.timeInMillis
        }
        val m = Regex("""^(\d{1,2}):(\d{2}):(\d{2})$""").find(v) ?: return false
        val cal = java.util.Calendar.getInstance().apply {
            set(
                get(java.util.Calendar.YEAR), get(java.util.Calendar.MONTH),
                get(java.util.Calendar.DAY_OF_MONTH), m.groupValues[1].toInt(),
                m.groupValues[2].toInt(), m.groupValues[3].toInt()
            )
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return now >= cal.timeInMillis
    }

    /**
     * `+` 后面的时长 → 毫秒。协议三种写法（10426.js）：
     *   `+30`      → 30 秒
     *   `+02:00`   → 2 分 00 秒
     *   `+02:00:00`→ 2 小时
     */
    private fun durationMs(hms: String): Long {
        val p = hms.split(":").map { it.toLongOrNull() ?: 0L }
        return when (p.size) {
            1 -> p[0] * 1000
            2 -> (p[0] * 60 + p[1]) * 1000
            3 -> ((p[0] * 60 + p[1]) * 60 + p[2]) * 1000
            else -> 0L
        }
    }

    /**
     * 运行时变量表 —— 「设置变量」写、`${名字}` 读。
     *
     * 只做**字面量**：协议里 VarValue 有 22 种（表达式、随机、取文本、JS…），
     * 那些需要一套变量运行时。这里能保证的是"设了字面量就能用上"，
     * 求不出来的变量会在设置那一步明确报出来，不会静默变成空串。
     */
    private val scriptVars = HashMap<String, String>()

    /** 把 extra 里的"动作 JSON"解析成 Z.Action（弹窗取消动作用）。 */
    /** 位置对象的可读摘要（给日志用）。 */
    private fun describePos(pos: Z.PosData): String {
        val where = when {
            pos.limitArea != null -> "范围=(${pos.limitArea})"
            pos.x != null || pos.y != null -> "坐标=(${pos.x?.raw ?: "?"},${pos.y?.raw ?: "?"})"
            else -> "范围=全屏"
        }
        return when (pos.type) {
            Z.PosType.LOCATION -> "坐标(${pos.x?.raw ?: "?"},${pos.y?.raw ?: "?"})"
            Z.PosType.TEXT -> "文字\"${pos.text ?: ""}\" 相似度${pos.similarPercent ?: 90}% $where"
            Z.PosType.COLOR -> "颜色${pos.color ?: ""} 相似度${pos.similarPercent ?: 100}% $where"
            Z.PosType.IMAGE -> "模板 ${where}"
            Z.PosType.NODE -> "节点\"${pos.text ?: ""}\" $where"
            else -> "${pos.type.wire} $where"
        }
    }

    private fun parseActionJson(raw: Any?): Z.Action? {
        if (raw == null) return null
        return runCatching {
            val o = when (raw) {
                is org.json.JSONObject -> raw
                is String -> org.json.JSONObject(raw)
                else -> org.json.JSONObject(raw.toString())
            }
            Z.Action.parse(o)
        }.getOrNull()
    }

    /** 从动作里取 `vars` 数组（可能是 JSONArray，也可能是 JSON 文本）。 */
    private fun varsArrayOf(action: Z.Action): org.json.JSONArray? {
        val raw = action.extra["vars"] ?: return null
        return runCatching {
            when (raw) {
                is org.json.JSONArray -> raw
                is String -> org.json.JSONArray(raw)
                else -> org.json.JSONArray(raw.toString())
            }
        }.getOrNull()
    }

    /**
     * 把 `${变量名}` 代换成运行时变量。
     *
     * 用在「点击文字」的查找文字和「输入内容」的输入文本上 ——
     * 这两处是"设置变量"最常见的用途（按关卡名/数量去点、去输入）。
     * 变量不存在时**原样保留**并记一条日志：拼错的变量名会立刻看得见，
     * 而不是变成空字符串去匹配任意文字。
     */
    private fun applyScriptVars(action: Z.Action): Z.Action {
        if (scriptVars.isEmpty()) return action
        var missing = false
        fun sub(text: String?): String? {
            if (text == null || !text.contains("${'$'}{")) return text
            var out: String = text
            for ((k, v) in scriptVars) {
                out = out.replace("${'$'}{$k}", v)
            }
            if (out.contains("${'$'}{")) missing = true
            return out
        }
        val newPos = action.posData?.let { it.copy(text = sub(it.text)) }
        val newStart = action.startPos?.let { it.copy(text = sub(it.text)) }
        val newEnd = action.endPos?.let { it.copy(text = sub(it.text)) }
        val newExtra = action.extra.toMutableMap().apply {
            (this["inputText"] as? String)?.let { put("inputText", sub(it)) }
        }
        if (missing) log("   变量：有 ${'$'}{名字} 没有对应的运行时变量，原样保留")
        return action.copy(
            posData = newPos, startPos = newStart, endPos = newEnd, extra = newExtra
        )
    }

    /** [applyLiteralVars] 的结果：[blockedField] 非 null = 这个字段是变量且求不出来。 */
    private class VarsApplied(val action: Z.Action, val blockedField: String? = null)

    /** 变量代换出来的字面量：既能当文本（delay/duration），也能当数字（repeatCount）。 */
    private class VarLiteral(val text: String, val number: Double?)

    /**
     * `__vars` 的**字面量**代换。
     *
     * 自动精灵允许把任意字段改成"运行时求值"，落盘时原字段会被**删掉**
     * （FIELDS.md:127-133）。所以我们读到的"字段不存在"有两种完全不同的含义：
     * 用户没写 / 它是变量。以前一律按前者处理 —— `delay` 是变量就按 200ms 跑、
     * `duration` 是变量就按 100ms，脚本节奏全错，而界面上一切正常。
     *
     * 这里只做**保守的字面量**（`varType` 为 `number`/`string` 且 `valueExp` 能直接解析）。
     * 求不出来的（`js_function`、`expression`、`random`…）**明确报出来**，
     * 由调用方跳过这条动作 —— 绝不静默用默认值。
     */
    private fun applyLiteralVars(action: Z.Action): VarsApplied {
        val vars = action.common.vars
        if (vars.isEmpty()) return VarsApplied(action)

        /*
         * ★ 变量覆盖了**我们不认识**的字段时，必须说出来。
         *
         *   协议落盘时会把被变量化的原字段**删掉**。于是像 `indexNum`
         *   （选第几个）、`xOffset`、`similarPercent`、`text`、`color`
         *   被变量化之后，文件里根本没有这个字段 —— 我们读不到，
         *   就静默用默认值：点第一个命中、偏移 0、阈值 90%、找空文字。
         *
         *   界面上"含变量"的标记还在，运行起来却毫无提示，用户只会觉得
         *   "这脚本在别的软件里能用，在你这里乱点"。
         *   这里把超出代换能力的字段明确记下来（日志 + 结果里的跳过原因），
         *   不装作求值成功。
         */
        val unsupported = vars.keys.filter { it !in WATCHED_VAR_FIELDS }
        if (unsupported.isNotEmpty()) {
            log(
                "   变量：字段 ${unsupported.joinToString("、")} 由变量定义，" +
                    "本机只能代换 ${WATCHED_VAR_FIELDS.joinToString("/")}，" +
                    "其余字段会按默认值执行"
            )
        }

        var common = action.common
        var changed = false
        for (field in WATCHED_VAR_FIELDS) {
            if (!vars.containsKey(field)) continue

            // posData 是结构字段，做不了字面量代换：只有它真的缺失时才拦下
            // （协议落盘时确实会删掉它），否则原样放行。
            if (field == "posData") {
                if (action.posData == null) return VarsApplied(action, field)
                continue
            }

            val def = vars[field] ?: return VarsApplied(action, field)
            val literal = literalOf(def) ?: return VarsApplied(action, field)
            when (field) {
                "delay" -> {
                    common = common.copy(delay = literal.text)
                    changed = true
                }
                "duration" -> {
                    common = common.copy(duration = literal.text)
                    changed = true
                }
                "repeatCount" -> {
                    val n = literal.number ?: return VarsApplied(action, field)
                    common = common.copy(repeatCount = n.toInt())
                    changed = true
                }
            }
        }
        return if (changed) VarsApplied(action.copy(common = common)) else VarsApplied(action)
    }

    /** 从变量定义里取字面量；返回 null = 这个变量不是字面量（求不出来）。 */
    private fun literalOf(def: org.json.JSONObject): VarLiteral? {
        val type = def.optString("varType")
        val exp = def.optString("valueExp").trim()
        return when (type) {
            "number" -> exp.toDoubleOrNull()?.let { VarLiteral(it.toString(), it) }
            "string" -> {
                if (exp.isEmpty()) return null
                // 去掉最外层一对引号：`"abc"` 和 `'abc'` 都是常见写法
                val unquoted = when {
                    exp.length >= 2 && exp.first() == '"' && exp.last() == '"' ->
                        exp.substring(1, exp.length - 1)
                    exp.length >= 2 && exp.first() == '\'' && exp.last() == '\'' ->
                        exp.substring(1, exp.length - 1)
                    else -> exp
                }
                VarLiteral(unquoted, unquoted.toDoubleOrNull())
            }
            else -> null
        }
    }

    /**
     * 报了但没实现的监听槽位，每个动作只提示一次。
     *
     * 7 个槽位现在**全部**执行（见 [runCallbacks]）：检查条件前、条件满足后、
 * 条件失败后、动作开始前、每次成功、失败、全部结束。
     * 不提示的话，用户看到"动作成功了"，却没发现挂在上面的收尾动作一条都没执行。
     */
    private fun warnUnsupportedCallbacks(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ) {
        if (action.callbacks.isEmpty()) return
        /*
         * ★ 白名单必须包含**实际会执行的那三个**：SUC / FAIL / FINISH。
         *
         *   原来漏了 AFTER_EXEC_FAIL —— 它在 :663/:667 是真执行了的，
         *   却被算进"未执行"，于是用户在跳过列表里看到
         *   「监听动作未执行…：afterExecFail」，而它明明跑了。
         *   这种"报假警"比漏报更伤：用户会去删一个正常工作配置。
         */
        /** 现在 7 个槽位全部实现；留这个函数是为了"以后协议再加槽位"时能报警。 */
        val supported = Z.CallbackSlot.entries.toSet()
        val unsupported = action.callbacks.keys.filter { it !in supported }
        if (unsupported.isEmpty()) return
        // 无限循环里每轮都会走到这里，只报第一次
        if (!warnedCallbackActions.add(index)) return
        val names = unsupported.joinToString("、") { it.wire }
        val msg = "监听动作未执行（本机只支持 afterExecSuc / afterExecFail / afterExecFinish）：$names"
        skipped.add(Result.Skip(index, label, msg))
        log("[$index] $label — $msg")
    }

    /**
     * 跑一个监听槽位（`scriptCallbacks`）。
     *
     * 只实现 afterExecSuc / afterExecFinish —— 覆盖绝大多数脚本的用法
     * （成功后收个尾、动作结束后补一手）。槽内动作的**跳转/停止不生效**：
     * 它们的作用域是外层脚本，让一个监听把整条脚本跳走会变成灾难。
     */
    private fun runCallbacks(
        action: Z.Action,
        slot: Z.CallbackSlot,
        index: Int,
        label: String
    ) {
        val cb = action.callbacks[slot] ?: return
        if (stopped) return
        if (cb.common.disabled) {
            log("[$index] $label 监听 ${slot.wire}：动作已禁用，跳过")
            return
        }
        if (!cb.type.executable) {
            log("[$index] $label 监听 ${slot.wire}：${cb.type.wire} 在安卓上暂不支持")
            return
        }

        val cbLabel = "监听·${slot.wire}"
        val cbSkips = ArrayList<Result.Skip>()
        /*
         * ★ 监听里的动作也是**完整动作**。
         *
         *   走 executeWithRepeat（而不是 executeOnce）：
         *   disabled / condition / 前置等待 / repeatCount / fastRepeatMode
         *   才会和普通动作一致生效。「运行多个动作」的子动作是同一个道理。
         *
         *   条件失败（abortOnRecognitionFail = false）只跳过这一条 ——
         *   一个监听没命中不该把整条脚本干掉。
         */
        val r = executeWithRepeat(
            cb, index, cbLabel, cbSkips, abortOnRecognitionFail = false
        )
        if (r.jumpTo != null || r.stop || r.breakShell) {
            log("[$index] $cbLabel 里的跳转/停止/结束子列表被忽略（作用域是外层脚本）")
        }
        // 监听失败不能污染外层：否则下一条「缺位置」之类的失败会带着它的名义终止整条任务
        abortReason = null
        // 槽内动作的失败原因不能吞掉：这是用户唯一能看到「监听做了什么」的地方
        cbSkips.forEach { log("[$index] $cbLabel 跳过：${it.reason}") }
        log("[$index] $label 监听 ${slot.wire} 已执行")
    }

    /** 解析数字或区间 `"1-3"`，取随机。解析不了返回 null。 */
    private fun randomIn(value: String?): Double? {
        val s = value?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.contains('-') && !s.startsWith("-")) {
            val parts = s.split('-', limit = 2)
            val lo = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
            val hi = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
            if (lo != null && hi != null && lo <= hi) {
                return lo + Math.random() * (hi - lo)
            }
            return null
        }
        return s.toDoubleOrNull()
    }
}
