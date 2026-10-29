package com.pvz2.auto.zjs

/**
 * 执行脚本格式脚本。
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
        toast = jsToast,
        shouldStop = { stopped }
    )

    /**
     * 读另一个脚本（「执行脚本」动作用）。null = 通道未接（单测里就是 null）。
     *
     * 走回调而不是直接依赖 ZjsRunner：执行器不认识 Android 的 Context，
     * 也就能在没有文件系统的单测里跑。和 appLauncher/toaster 是同一个套路。
     */
    /**
     * 这是**子脚本**执行器（由「执行脚本」创建）。
     *
     * 子脚本自己收尾时不能去关父脚本的日志文件 —— 真机踩到：
     * 子脚本跑完调用 `ZjsLog.endRun()` 把 writer 置空，
     * 父脚本后面所有日志**静默丢失**，看起来像"父脚本没继续"。
     */
    var isSubScript: Boolean = false

    var scriptLoader: ((String) -> Z.Script?)? = null

    /**
     * 点运行时用户在弹窗里填的值（对应变量的 `value.showInput`）。
     *
     * 语义：勾了"从键盘输入"的变量，值来自这个弹窗；
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

        /** 「系统提示」的轻提示通道（协议 10.23 的 toast 部分）。 */
        fun toaster(): ((String) -> Unit)? = null
    }

    /** 识别接口。图片/文字/颜色匹配都走这里。 */
    interface Matcher {
        /**
     * 「一轮判断」的开始/结束：这一轮里的多次识别**共用同一帧**。
     *
     * 等待候选一轮要判 N 个候选，如果每个候选各抓一帧，既有 N 倍开销，
     * 也因为画面在变而"同时识别"名不副实。实现方可以钉住一帧给整轮用。
     */
    fun beginFrameGroup() {}

    fun endFrameGroup() {}

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
         * 把当前画面存成 PNG（失败现场，对应 参考管线 的 debug/on_error）。
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
    /** 参考管线 的 pre_delay / post_delay 默认值：没写 delay 时按这个走。 */
    /**
     * 动作没写 `delay` 时的缺省等待。
     *
     * 取 **100ms**：这是 脚本格式 的默认节奏（手写脚本里几乎每条都是
     * `"delay":"100","delayUnit":0`）。原来是 200，于是"没写等待"的动作
     * 比作者预期慢一倍 —— 用户实测反馈"等待缺省 100ms 没生效"。
     *
     * 注意：参考管线 转换过来的节点自带 pre_delay/post_delay（参考管线 自己的缺省是 200），
     * 那些是**显式**写进文件的，不受这里影响。
     */
    private val DEFAULT_DELAY_MS = 100

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

    /** 「调用子链」的返回栈：元素 = (回到第几条继续, 结束节点标识)。 */
    private val callStack = ArrayList<Pair<Int, String>>()

    /**
     * 被「等待候选超时结束」这类分支强制停下时记下的原因。
     *
     * 那些分支写在 lambda 里，`return Outcome(abort=…)` 只从 lambda 返回，
     * abort 传不到最终摘要，于是日志只有"执行 N 条"，看不出是超时停的。
     * 这里单独记一份，摘要里一并打印。
     */
    private var forcedStopReason: String? = null

    /**
     * 当前「监听套监听」的层数。
     *
     * 「执行脚本」有 8 层限制、「条件嵌套」有 8 层限制，**唯独监听没有**。
     * 于是 `执行成功后 → 点A`，而 A 自己又挂 `执行成功后 → 点A`，
     * 就会无限递归：轻则 StackOverflow 崩掉本次运行，重则 ANR 卡死。
     * 这里补上同样的上限。
     */
    private var callbackDepth = 0

    /** 子列表嵌套上限。协议没有这一条，纯粹防止脚本自引用成无限递归。 */
    /** 子列表内 `&` 跳转的次数上限：防止 `&-1` 这种写法变成死循环。 */
    private val MAX_SUB_JUMPS = 10000

    /** 条件嵌套层数上限（畸形文件防栈溢出）。 */
    private val MAX_COND_DEPTH = 8

    /** 周围条件挂在位置对象上的键名（协议：posData.filterByAroundConditionSet）。 */
    /**
     * 这些 `varType` 的 `valueExp` 是一段 JS 表达式，能直接求值。
     *
     * `js_function` / 取文本 / 取属性 之类不是表达式，故意不在名单里 ——
     * 名单外的类型仍然走"明确跳过"的路径，不猜。
     */
    private val JS_VAR_TYPES = setOf("expression", "random")

    private val AROUND_KEY = "filterByAroundConditionSet"

    /** 周围条件里允许出现的子条件类型（脚本格式 getSupportItemConditionTypes()）。 */
    private val AROUND_SUB_TYPES = setOf(
        Z.ConditionType.IMAGE, Z.ConditionType.TEXT,
        Z.ConditionType.COLOR
    )

    private val MAX_SUB_SHELL_DEPTH = 8

    /** 监听最多套几层（和执行脚本一致）。 */
    private val MAX_CALLBACK_DEPTH = 8

    /**
     * 跑完整个脚本。
     *
     * @param maxSteps 步数上限，防止 `重复次数=0` + 无条件跳转变成死循环
     *        （那种脚本会一直转，用户只能强杀应用）
     */
    fun run(script: Z.Script, maxSteps: Int = 100_000): Result {
        /* 解析阶段被跳过的行必须报出来：以前只进 Logcat，脚本少几步而日志显示完成。 */
        if (script.badLines.isNotEmpty()) {
            log("[脚本] 有 ${script.badLines.size} 条动作解析失败，本次不会执行：")
            script.badLines.forEach { log("   $it") }
        }
        val actions = script.actions
        if (actions.isEmpty()) return Result(0, emptyList(), "脚本里没有动作")

        startMemoryWatch()

        // ★ 不要在这里重置 stopped。
        //   stop() 可能比 run() 更早到达（RUN 后立刻 STOP），
        //   重置等于把那次停止吞掉。每个 run 用新的执行器实例，默认就是 false。
        val skipped = ArrayList<Result.Skip>()
        var executed = 0
        var steps = 0
        // 「设置变量」写入的运行时变量：每次 run 从空开始（协议里变量属于一次运行）
        scriptVars.clear()
        // 弹窗里填的值就是这些变量的初值（脚本格式里"从键盘输入"就是这个意思）
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
            /*
             * 「调用子链」返回栈：元素 = (回到第几条继续, 结束节点标识)
             * 结束标识为空串 = 跑到列表末尾自然返回。
             */
            callStack.clear()
            while (!stopped) {
                if (pc >= actions.size) {
                    if (callStack.isEmpty()) break
                    val back = callStack.removeAt(callStack.size - 1)
                    log("[调用子链] 子链跑到末尾，回到第 ${back.first + 1} 条继续")
                    pc = back.first
                    continue
                }
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
                     * 实时执行位置上报。
                     *
                     * 之前界面只能按"延时/时长/重复次数"估算进度，组合动作里估不准，
                     * 于是进度条卡在某个子动作不动（用户报的就是这个）。
                     * 这里在**每个动作真正开始前**把 (第几个/共几个/描述/类型)
                     * 推到进程级状态，界面按它显示真实进度。
                     */
                    com.pvz2.auto.core.ScriptRunState.update { st ->
                        st.copy(
                            stepIndex = pc + 1,
                            stepTotal = actions.size,
                            stepLabel = label,
                            stepType = rawAction.type.wire
                        )
                    }

                /*
                 * ★ __vars：字段被变量化时，脚本格式落盘会**把原字段删掉**
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
                    val why = "__vars：字段「$field」由变量定义，但取值求不出来" +
                    "（支持 number / string 字面量和 expression / random 表达式）"
                    skipped.add(Result.Skip(pc, label, why))
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
                val skippedBefore = skipped.size
                val outcome = executeWithRepeat(action, pc, label, skipped)
                /*
                 * ★ 跳过原因必须进日志。
                 *
                 *   动作里那些"配置有问题"的分支（缺 keyCode、缺 filePath、找不到子脚本…）
                 *   只往 skipped 里塞一条就 return，界面上只剩"完成 0ms"，
                 *   用户完全不知道发生了什么 —— 实测「执行脚本」就是这样静默跳过的。
                 */
                if (skipped.size > skippedBefore) {
                    skipped.drop(skippedBefore).forEach { sk -> log("[跳过] ${sk.reason}") }
                }
                // 「动作运行状态」条件要查别的动作跑成功没有 —— 这里记一笔。
                // stop/jump 也算成功（它们确实按意图执行了）。
                actionOutcome[pc] = outcome.abort == null && !outcome.condSkipped
                /* 「调用子链」回弹：刚执行完的这条就是子链结束节点 → 忽略它自己的 next，回到调用处继续。 */
                if (callStack.isNotEmpty()) {
                    val (backPc, stopId) = callStack.last()
                    if (stopId.isNotEmpty() && rawAction.common.jumpId == stopId) {
                        callStack.removeAt(callStack.size - 1)
                        log("[调用子链] 到达结束节点「$stopId」，回到第 ${backPc + 1} 条继续")
                        pc = backPc
                        continue
                    }
                }
                com.pvz2.auto.core.ScriptRunState.finishAction(pc)

                /*
                 * ★ 识别不到 → 任务结束（参考管线 语义）。
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
                    // 失败收尾也要走：截图 + 汇总都在里面（真失败最需要现场）
                    finishRunLog(executed, skipped, outcome.abort)
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
                                    val fromPc = pc
                            pc = resolved.index
                            log(
                            // 序号用跳转前的位置：pc 已被改成目标，用它会和下面那个真节点撞号
                                "   ↳ 完成 [${fromPc + 1}] 用时 ${System.currentTimeMillis() - nodeStartedAt}ms" +
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
     * 参考管线 的做法是每次 on_error 都把当时那一帧存成
     * `debug/on_error/时间_任务_节点.png`；我们至少要有一张 ——
     * 用户说"它卡住了"的时候，图比文字有用得多。
     */
    private fun finishRunLog(executed: Int, skipped: List<Result.Skip>, failReason: String?) {
        /*
         * 截图单独包 runCatching：它失败**不能**把下面的汇总行一起吞掉。
         * 之前整段包在一起，于是取帧抛异常时日志里既没有截图行也没有"结束"行 ——
         * 真失败的那次反而什么都没有（真机实测踩到）。
         */
        runCatching {
            val realFail = skipped.firstOrNull { sk ->
                val m = sk.reason
                !m.contains("已禁用") && !m.contains("条件不满足") && !m.contains("条件的")
            }
            if (realFail != null || failReason != null) {
                // 文件名要短：#4 点击 —— 原来把整段原因塞进去，几十个字符还带引号，
            // 既难读又容易被文件系统截断
            val tag = realFail?.let { "#${it.index + 1} ${it.label.take(12)}" }
                ?: (failReason?.let { "abort" } ?: "失败")
                matcher.saveFrame(com.pvz2.auto.core.ZjsLog.shotDir().absolutePath, tag)
                    ?.let { log("   现场截图：$it") }
            }
        }
    
        val summary = "执行 $executed 条，跳过 ${skipped.size} 条" +
            (failReason?.let { "，停止原因：$it" } ?: forcedStopReason?.let { "，停止原因：$it" } ?: "")
        log("===== 结束：$summary =====")
        // 子脚本只写自己的汇总行，日志文件归父脚本关
        if (!isSubScript) runCatching { com.pvz2.auto.core.ZjsLog.endRun(summary) }
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
         * 参考管线 的语义：一个节点识别不到、又没有失败跳转（on_error / interrupt），
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
     *        顶层动作为 true（参考管线 语义：节点进不去就停）；
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
            // 跳过也要写日志：否则节点明细里只有"完成 0ms"，看不出是被禁用了
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
                /*
                 * ★ 先跑「运行条件失败后」监听，再看它的结果。
                 *
                 *   监听里如果是「跳转到」，就应该像 next 一样把流程带到别处 ——
                 *   用户原话："条件不满足可以设置监听条件失败跳转，这个和 next 的功能很像"。
                 *   所以监听产出的 jumpTo/stop 必须传出去，不能像以前那样只打一行
                 *   "里的跳转被忽略"。
                 */
                val cbOut = runCallbacks(action, Z.CallbackSlot.AFTER_CONDITION_FAIL, index, label)
                if (cbOut != null && (cbOut.jumpTo != null || cbOut.stop)) return cbOut
                
                if (abortOnRecognitionFail && isRecognitionCondition(cond!!)) {
                    // 识别类条件不成立、监听又没安排去处 = 这个节点进不去 → 整条任务结束
                    log("[任务结束] $msg")
                    return Outcome(abort = msg)
                }
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
        /*
         * ★ 「带条件」也算一种退出方式。
         *
         * 用户脚本里的经典写法：`repeatCount: 0` + 条件取反
         * （`runWhenFalse: true`，即"没看到 4 就滑"）——
         * 语义是**反复滑直到看到 4**。原来 canEscape 只认跳转/停止，
         * 于是这种写法被判成"没有退出方式"，降级成只跑 1 次，
         * 用户看到的就是"一下就划完了"。
         *
         * 现在把"带条件"也当退出方式，并在循环体开头用**取反前**的条件判一次：
         * 成立 = 目的达到 → break。这样才安全，不会死循环。
         */
        val hasCondition = action.common.condition != null
        val canEscape = action.type.controlRunType == "jumpTo" ||
            action.type.controlRunType == "stop" ||
            action.extra["jumpToPosition"] != null || hasCondition
        val infinite = repeat <= 0 && canEscape
        if (repeat <= 0 && !canEscape) {
            log("第 ${index + 1} 条「$label」 重复次数为 0（无限）但这条没有跳转/停止，按 1 次执行以免卡死")
        } else if (infinite) {
            log("第 ${index + 1} 条「$label」 重复次数为 0 = 无限（由跳转/停止结束）")
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

        val repeatWhileCond = repeat <= 0 && hasCondition
        for (n in 0 until times) {
            if (stopped) break
            // 「无限重复 + 带条件」：每轮先看条件（取反前）是否已经成立 → 成立就退出
            /*
             * 「无限重复 + 取反条件」= 重复**直到**条件不再成立。
             *
             * 用户原话："我查找编队节点条件写的是取反，识别 4，识别到 4 以后
             * 应该才会提示条件失败，然后执行监听里的动作"。
             *
             * 所以这里不能当成"重复完成、算成功"，而要按**条件失败**收场：
             *   记一条跳过 → 跑「条件失败后」（afterConditionFail）监听 →
             *   监听里的跳转/停止照旧向上传。
             * 这样"滑到 4 出现 → 点它 → 跳去开始战斗"就是一条自然链。
             */
            if (repeatWhileCond && n > 0) {
                val cond = action.common.condition!!
                val pass = evaluate(cond, index, skipped)
                if (!pass) {
                    val condName = ZConditionEditor.labelOf(cond.type)
                    val detail = if (isRecognitionCondition(cond)) matcher.lastReason()
                    else condDetail(cond)
                    val msg = "第 ${index + 1} 条「$label」条件不满足（$condName）：" +
                        (detail ?: "条件不成立") + "（已经重复 $n 次）"
                    log(msg)
                    skipped.add(Result.Skip(index, label, msg))
                    val cbOut = runCallbacks(
                        action, Z.CallbackSlot.AFTER_CONDITION_FAIL, index, label
                    )
                    if (cbOut != null && (cbOut.jumpTo != null || cbOut.stop)) return cbOut
                    return Outcome(condSkipped = true)
                }
            }
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
         * 原话：「开启后，会忽略默认的额外点击结束等待（5~30ms），
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
         * 「识别后等待」（参考管线 的 pre_delay 真正的位置）。
         *
         * 参考管线 的顺序是：**识别 → pre_delay → 动作**。
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
            Z.ActionType.CLICK_TEXT, Z.ActionType.CLICK_COLOR -> {
                val pos = action.posData
                if (pos == null) {
                    /*
                     * 这里要按动作类型说清楚缺的是什么 —— 用户真机踩过：
                     * 监听里配了「点击文字」想点刚认出来的那个「4」，但没填要识别的文字，
                     * 日志只写"缺位置"，看不出是"没填文字"还是"没填坐标"。
                     */
                    val why = when (action.type) {
                        Z.ActionType.CLICK_TEXT ->
                            "点击文字缺「要识别的文字」—— 想点固定坐标请改用「点击」"
                        Z.ActionType.CLICK_IMAGE -> "点击图片缺模板图/位置"
                        Z.ActionType.CLICK_COLOR -> "点击颜色缺颜色/位置"
                        else -> "缺位置"
                    }
                    skipped.add(Result.Skip(index, label, why))
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
                 * 识别细节进运行日志（参考管线 的日志也是这个粒度）：
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
                // post_delay：动作执行完再等这么久（参考管线 的 post_delay 语义）
                intExtra(action.extra, "pvzPostMs")?.let { if (it > 0) waitBusy(it.toLong()) }
                    /*
                     * 滑动的 duration 也要真的占时间。
                     *
                     * 注入层同样只把 duration 写进事件的 eventTime 差值、不睡 ——
                     * 自检脚本里 duration=400 的滑动实测只有 5ms，连点/连滑全会挤在一起。
                     */
                    // ★ 不再 waitBusy(hold)：注入层已按真实节奏滑满 hold 毫秒。
                    //   之前是「瞬间滑完 + 干等 hold」，用户看到的是「一下划完然后等 3 秒」。
                }
                Outcome()
            }

            /* ---------------- 游戏动作（PVZ2 专用） ----------------
             *
             * 参数只有「卡槽 / 行 / 列」，点位查 [PvzCoords]：
             *   种植物   = 卡槽点 → 格子点（滑 80ms）
             *   喂豆     = 能量豆 → 格子点
             *   铲除     = 铲子   → 格子点
             *   使用神器 = 神器   → 格子点
             *   点击格子 = 只点格子（原实现：没有起点就是 click）
             *
             * 语义抄自 参考管线 的 compile.py:174-213 —— 那边五种动作的唯一
             * 区别就是起点，终点都是格子点。
             */
            Z.ActionType.PVZ_PLANT, Z.ActionType.PVZ_FEED, Z.ActionType.PVZ_SHOVEL,
            Z.ActionType.PVZ_TAP_CELL, Z.ActionType.PVZ_ARTIFACT,
            Z.ActionType.PVZ_WAVE, Z.ActionType.PVZ_SPEED,
            Z.ActionType.PVZ_SWITCH_DECK ->
                runPvzAction(action, index, label, skipped)

            Z.ActionType.PVZ_SUPPLY ->
                runPvzSupply(action, index, label, skipped)

            Z.ActionType.PVZ_START_BATTLE,
            Z.ActionType.PVZ_ENTER_CHECK,
            Z.ActionType.PVZ_SETTLE ->
                runPvzGate(action, index, label, skipped)

            Z.ActionType.PVZ_SWITCH_FORM ->
                runPvzSwitchForm(action, index, label, skipped)

            // ---- 多指滑动
            Z.ActionType.MULTI_SWIPE -> {
                /*
                 * ★ 协议里多指手势的轨迹在 `gestureActions`（FIELDS.md 10.9），
                 *   而 `fingers` 是我们自己的私有扩展。从脚本格式导入的脚本
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
                    log("第 ${index + 1} 条「$label」 用协议轨迹 gestureActions 逐点执行（本机不是真并发多指）")
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
                val kc = intExtra(action.extra, "keyCode")
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
                log("第 ${index + 1} 条「$label」 开始执行子脚本「$name」")
                subShellDepth++
                val subResult = try {
                    ZExecutor(inject, screen, matcher, log).also { e ->
                        e.isSubScript = true
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
                    log("第 ${index + 1} 条「$label」 子脚本跳过：${it.label} — ${it.reason}")
                }
                log(
                    "第 ${index + 1} 条「$label」 子脚本「$name」结束：执行 ${subResult.executed} 条" +
                        (subResult.error?.let { "，中止：$it" } ?: "")
                )
                val cont = boolExtra(action, "continueCurrentAfterFinish")
                if (!cont) {
                    log("第 ${index + 1} 条「$label」 continueCurrentAfterFinish=false，父脚本到此结束")
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
                log("第 ${index + 1} 条「$label」 识别屏幕：${texts.size} 行，共 ${joined.length} 字")
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
                        log("第 ${index + 1} 条「$label」 识别到的文字：${joined.take(120)}")
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
                    log("第 ${index + 1} 条「$label」 识别到的文字：${joined.take(120)}")
                }
                Outcome()
            }

            // ---- 暂停（10.20 m82947）：停在原地等用户点「继续」
            Z.ActionType.CONTROL_PAUSE -> {
                com.pvz2.auto.core.ScriptRunState.requestPause()
                log("第 ${index + 1} 条「$label」 已暂停，等待「继续」")
                while (com.pvz2.auto.core.ScriptRunState.paused && !stopped) {
                    Thread.sleep(50)
                }
                if (stopped) {
                    log("第 ${index + 1} 条「$label」 暂停中被停止")
                    return Outcome(stop = true)
                }
                log("第 ${index + 1} 条「$label」 继续执行")
                Outcome()
            }

            // ---- 运行JS代码（10.25 m23689）：jsCode 直接跑在沙箱里
            //
            // 脚本格式那边是 QuickJS；我们用 Rhino（纯 Java、解释模式），
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
                    log("第 ${index + 1} 条「$label」 JS 出错：${out.error}")
                    return null
                }
                log(
                    "第 ${index + 1} 条「$label」 JS 执行完成" +
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
                 * 脚本格式里「设置变量」可以是一条**问用户的对话框**：
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
                        log("第 ${index + 1} 条「$label」 弹窗设了「仅展示一次」，这次跳过询问")
                    } else {
                        val choice = inject.askDialog(
                            AskDialog(
                                title = title.ifBlank { "提示" },
                                okText = okText.ifBlank { "确定" },
                                cancelText = cancelText.ifBlank { "取消" },
                                autoBtn = autoBtn,
                                autoDelayMs = intExtra(action.extra, "dialogAutoClickDelay")
                                    ?.toInt() ?: 3000
                            )
                        )
                        if (showOnce) jsStorage?.set?.invoke(onceKey, "1")
                        when (choice) {
                            null -> log("第 ${index + 1} 条「$label」 本机没有弹窗通道，按「确定」处理")
                            1 -> {
                                log("第 ${index + 1} 条「$label」 点了「${cancelText.ifBlank { "取消" }}」→ 执行取消动作、不赋值")
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
                            else -> log("第 ${index + 1} 条「$label」 点了「${okText.ifBlank { "确定" }}」→ 按配置赋值")
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
                     *  - 留空 && 不必填    → **删掉这个变量**（VAR_DELETE_MARK）
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
                    "第 ${index + 1} 条「$label」 设置变量：成功 $okCount 个" +
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
             * 等候选 —— 参考管线 的 next 语义。
             *
             * 参考管线 的每个节点都有一条候选链：每轮按顺序逐个识别，第一个成功的
             * 被进入；都不成功就继续等，直到超时。
             *
             * 这里就是那条循环。几个必须讲清楚的点：
             *
             * 1. **顺序即优先级**。不能排序、不能去重 —— 用户排的顺序就是语义。
             * 2. **每轮都从头试**。参考管线 也是每轮重新识别，不是记住上一轮结果。
             * 3. **睡的时候分片**，否则「停止」要等整段间隔才生效，
             *    用户会以为卡死了。
             * 4. **超时不是错误**。参考管线 里 timeout 到点是"这条没等到"，
             *    继续往下走；记一条跳过原因，但不按失败处理。
             */
            Z.ActionType.WAIT_CANDIDATES -> {
                if (action.candidates.isEmpty()) {
                    skipped.add(Result.Skip(index, label, "没有候选可等"))
                    return null
                }
                val timeout = action.waitTimeoutMs ?: 0
                val interval = (action.waitIntervalMs ?: 200).coerceAtLeast(20)
                // 把等待上限报给界面：进度条要显示「已等 / 上限」，不然只有秒数在跳。
                if (timeout > 0) com.pvz2.auto.core.ScriptRunState.nodeTimeout(timeout)
                val startedAt = System.currentTimeMillis()
                var round = 0
                while (!stopped) {
                    round++
                    /*
                     * ★ 一轮候选 = 同一帧。
                     *
                     * 用户要求："候选等待节点，同时识别，如果命中多个目标，
                     * 就优先执行最上那个"。
                     * 同一帧是"同时"的前提：否则候选 1 看第 1 帧、候选 5 看第 6 帧，
                     * 画面在动时结论会自相矛盾。顺序优先由"逐个判、第一个命中就 return"保证。
                     */
                    matcher.beginFrameGroup()
                    try {
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
                    } finally {
                        matcher.endFrameGroup()
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
                         * 超时去哪 —— 对应 参考管线 的 `on_error`。
                         *
                         * 参考管线：next 扫到 timeout = 这个节点失败，走 on_error（空 = 任务失败）。
                         * 我们：`onTimeoutJump`（本机新增字段）写了 jumpId 就跳过去；
                         *      没写则维持老行为 —— 记一条跳过、继续往下。
                         *
                         * 为什么不用「监听 afterExecFail」来做：监听槽位里的动作
                         * **不传递跳转/停止**（协议如此），而 on_error 本质是控制流。
                         */
                        val to = (action.extra["onTimeoutJump"] as? String)?.trim().orEmpty()
                if (to.isEmpty() && boolExtra(action, "onTimeoutStop")) {
                    val why = "等待候选超时（${timeout}ms），按 参考管线 语义结束任务"
                    skipped.add(Result.Skip(index, label, why))
                    log("[等待候选] $why")
                    /* 必须同时置 stopped：这段在候选循环的 lambda 里，单靠 return Outcome 只从 lambda 返回，
                     * 外层会继续执行后面的节点（真机日志就是这样：说完"结束任务"又跑了第 2/3/4 条）。 */
                    stopped = true
                    forcedStopReason = why
                    return Outcome(abort = why)
                }
                        if (to.isNotEmpty()) {
                            log("[等待候选] 等了 ${timeout}ms 没命中 → 超时跳转 $to（参考管线 on_error）")
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
            Z.ActionType.CALL_SUB -> {
                val runId = action.extra["callRun"]?.toString()?.trim().orEmpty()
                val backId = action.extra["callBack"]?.toString()?.trim().orEmpty()
                if (runId.isEmpty()) {
                    val why = "调用子链缺「起始节点」"
                    skipped.add(Result.Skip(index, label, why))
                    log("[跳过] $why")
                    return null
                }
                if (callStack.size >= MAX_SUB_SHELL_DEPTH) {
                    val why = "调用子链嵌套超过 $MAX_SUB_SHELL_DEPTH 层"
                    skipped.add(Result.Skip(index, label, why))
                    return null
                }
                callStack.add((index + 1) to backId)
                log("   调用子链：从「$runId」开始" + (if (backId.isEmpty()) "（跑到末尾返回）" else "，到「$backId」返回"))
                return Outcome(jumpTo = runId)
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
     * ## 找不到时会重试（参考管线 的 timeout 语义）
     *
     * 参考管线 的节点识别不到会一直等到超时，我们原来是一失败就跳过 ——
     * 从 参考管线 转过来的脚本因此会"一路冲过去"，不等画面。
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
     * ## 语义来自脚本格式自己的帮助文案
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

        // 找不到就按间隔重试，直到超时（参考管线 的 timeout）。
        //
        // ★ 三态语义，必须分清：
        //      timeoutMs > 0  → 等到这个时长为止
        //      timeoutMs < 0  → **一直等**（参考管线 的 timeout:-1），只有「停止」能结束
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
            // 识别不到 → 按 参考管线 的语义整个任务结束（这条没有失败跳转可走）
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
             *
             * ★ 但要看清楚是哪一种"空"：周围条件把候选点全过滤光时，
             *   indexNum 是空的，原来却报 `indexNum「null」指定的下标都超出了命中数 0`
             *   —— 用户会去改「选第几个」，实际该改的是周围条件。
             */
            val msg = when {
                hits.isEmpty() && pos.extra.containsKey("filterByAroundConditionSet") ->
                    "周围条件把候选点位全部过滤掉了"
                hits.isEmpty() -> "没有命中任何点位"
                else -> "indexNum「${pos.indexNum}」指定的下标都超出了命中数 ${hits.size}"
            }
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
     *      ":A"     → 跳到 jumpId 为 A 的动作（脚本格式原生的无限循环就这么写）
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

    /*
     * 范围写法不认识时说一声。
     *
     * 合法写法只有 `"左 上 右 下"`（空格分隔）和 left/top/right/bottom 四个键。
     * 写成 {"x":0,"y":0,"w":1280,"h":300} 或逗号分隔时解析失败 → 静默回落全屏，
     * 用户以为加了范围其实整屏都在搜（真机自检踩到过）。
     */
    private fun warnBadArea(pos: Z.PosData) {
        if (pos.limitArea == null && pos.extra.containsKey("limitArea")) {
            log("[警告] 范围写法无法解析（应为 \"左 上 右 下\" 或 left/top/right/bottom），已按全屏处理")
        }
    }

    private fun searchPos(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> {
        val t0 = System.nanoTime()
        warnBadArea(pos)
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
        condIn: Z.Condition,
        index: Int,
        skipped: MutableList<Result.Skip>
    ): Boolean {

        /*
         * timeoutMs（本机新增）：用户填毫秒，这里折成「次数 × 间隔」。
         * 向上取整 —— 宁可多等一轮也不能早退（早退=条件没等到就跳过该节点）。
         * 间隔缺省 1000ms。
         */
        /*
         * timeoutMs（本机新增）：**按墙钟时间截止**。
         * 之前折成「次数 = 超时 ÷ 间隔」，但每轮识别本身要 3 秒上下，
         * 于是"等 6 秒"实际等 19 秒。改成记截止时刻，识别耗时自然计入。
         */
        val cond = if (condIn.timeoutMs != null) {
            condIn.copy(repeatWhenFalse = true, repeatWhenFalseLimitTimes = 0, timeoutMs = null)
        } else condIn
        val deadline = condIn.timeoutMs?.let { System.currentTimeMillis() + it } ?: 0L
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
        /*
         * 诊断：把"取反/反复检查"这几个开关的实际值打出来。
         *
         * 用户真机脚本「切换编队.zjs」里，滑动节点写了 runWhenFalse:true
         * （没看到"4"就滑动），日志却是"条件不满足 → 任务结束"。
         * 单测里同样的条件是通过的，所以必须知道运行时这些字段到底读成了什么。
         */
        log(
            "   [条件诊断] 结果=$first 取反=${cond.runWhenFalse} " +
                "反复检查=${cond.repeatWhenFalse} 额外次数=${cond.repeatWhenFalseLimitTimes} " +
                "延迟前判=${cond.checkBeforeDelay} 类型=${cond.type.wire}"
        )
        if (first || !cond.repeatWhenFalse) return first

        /*
         * ★ limit 是"**额外**重复检查的次数"，不是总次数。
         *
         *   协议原实现（脚本格式 m4506.js）：
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
            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                log("   [条件] 已到识别超时（${condIn.timeoutMs}ms，判了 ${retries + 1} 次），按不成立处理")
                break
            }
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
        
            /*
             * 变量比较（本机新增）：condition.extra 里放
             *   varName  —— 变量名
             *   expected —— 期望值（字符串比较，忽略首尾空白）
             * 用于「按变量决定要不要执行某个组合动作」，比如起始关卡/后期种蓓蕾。
             */
            if (cond.type == Z.ConditionType.VAR_EQUALS) {
                val name = cond.extra["varName"]?.toString()?.trim().orEmpty()
                val want = cond.extra["expected"]?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    log("[条件] 变量比较缺 varName")
                    return false
                }
                val got = scriptVars[name]?.trim().orEmpty()
                val ok = got == want
                /*
                 * ★ 必须走 applyInvert。
                 *
                 *   这个分支是提前 return 的，最初漏掉了取反：脚本里写
                 *   「心塔_起始关卡 ≠ 3-149关（取反）」时，比较结果为 false，
                 *   取反没生效就当成"不成立"跳过 —— 用户选「2关」，第2关被直接跳过。
                 *   日志会打「结果=false 取反=true」，看着像取反了，实际没有。
                 */
                val eff = applyInvert(cond, ok)
                log(
                    "[条件] 变量比较：$name=\"$got\" 期望 \"$want\" → 原始 ${if (ok) "成立" else "不成立"}" +
                        (if (cond.runWhenFalse) "，取反后 ${if (eff) "成立" else "不成立"}" else "")
                )
                return eff
            }
if (depth > MAX_COND_DEPTH) {
            // 畸形文件可能把条件自己套自己几百层 —— 直接算会 StackOverflowError，
            // 报出来比崩掉强（崩掉时界面只能显示"执行异常：null"）。
            log("   条件嵌套超过 $MAX_COND_DEPTH 层，视为不成立")
            skipped.add(Result.Skip(index, "条件", "条件嵌套超过 $MAX_COND_DEPTH 层"))
            return false
        }
        val raw = when (cond.type) {
            Z.ConditionType.IMAGE, Z.ConditionType.TEXT,
            Z.ConditionType.COLOR -> {
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
                val raw = cond.extra["percent"]
                val p = intExtra(cond.extra, "percent") ?: 50.also {
                    if (raw != null) log("[条件] 随机运行的概率「$raw」读不出来，按 50% 处理")
                }
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
                val limitRaw = cond.extra["limitTimes"]
            val limit = intExtra(cond.extra, "limitTimes") ?: 0.also {
                if (limitRaw != null) log("[条件] 限制次数「$limitRaw」读不出来，按不限处理")
            }
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
                val unit = intExtra(cond.extra, "timeIntervalUnit") ?: 1
                val gapMs = (randomIn(v) ?: 0.0).toLong() * unitToMs(unit)
                val now = System.currentTimeMillis()
                val key = condKey(cond)
                val anchor = intervalLast[key]
                val ignoreInit = boolExtra(cond, "ignoreInitPass")
                /*
                 * ★ ignoreInitPass 的语义是"**首次条件检查不满足**"。
                 *
                 *   脚本格式（91969.js）:
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
        Z.ConditionType.COLOR ->
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
    /**
     * 宽容取整：`Number` 和"看起来是数字的字符串"都收。
     *
     * 为什么需要：`percent`、`limitTimes` 这类字段，本机界面写的是 Int，
     * 但手动改过的 .zjs / 别处导出的脚本里常是 `"100"` 这样的字符串。
     * 原来一律 `as? Number` —— 字符串直接不匹配，然后**静默**回落默认值：
     * 随机概率变 50%、限制次数变 0（0 等于不限），用户完全看不出来。
     */
    private fun intExtra(extra: Map<String, Any?>, key: String): Int? = when (val v = extra[key]) {
        is Number -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

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
     * 参考管线 的 pre_delay / post_delay 默认都是 200ms，所以「真的去操作画面」的动作
     * 走默认 100ms；控制流动作（跳转、等待候选）传 0 —— 否则「无限循环 + 每步等待」
     * 的脚本会在死循环保护生效前爬几小时，测试里那个死循环用例直接跑不完。
     */
    /** 可中断的纯等待（按住时长、微间隔用）。 */
    private fun waitBusy(ms: Long) {
        var left = ms
        while (left > 0 && !stopped) {
            val step = minOf(left, 20L)
            Thread.sleep(step)
            left -= step
        }
    }

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
        // 等待也进日志：节点用时对不上时，能一眼看出是"没等"还是"等多了"
        if (total > 0) log("   等待 ${total}ms")
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
     * - **有位置/起终点的交互动作**：200ms（参考管线 的 pre_delay 默认值）。
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
         * "没有全局额外节奏"；把它当成"每条都等 0ms"就会把动作自己的默认等待
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
            log("   识别后等待 ${afterLocateMs}ms 再点（参考管线 pre_delay 位置）")
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
                /*
                 * 「按住 N ms」要真的占时间。
                 *
                 * 注入层把 hold 只写进两个事件的 eventTime 差值、不睡 —— 于是连点 35 次
                 * 该有的时长，实际 178ms 就发完了（每下 3.5ms），用户看到的就是
                 * "这条一下就结束了"，参数形同虚设。hold <= 0（极速模式）不等，与协议一致。
                 */
                // ★ 不再 waitBusy(hold)：注入层已按真实节奏滑满 hold 毫秒。
                //   之前是「瞬间滑完 + 干等 hold」，用户看到的是「一下划完然后等 3 秒」。
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
     *  - **识别失败不出去**：只有顶层动作识别失败才按 参考管线 语义终止任务。
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
            return null
        }
        if (subShellDepth >= MAX_SUB_SHELL_DEPTH) {
            val why = "子动作列表嵌套超过 $MAX_SUB_SHELL_DEPTH 层，已停止递归"
            skipped.add(Result.Skip(index, label, why))
            return null
        }

        // runMode：协议有 normal / random / multiThread，我们只有顺序执行
        val mode = action.extra["runMode"]?.toString()?.trim().orEmpty()
        if (mode.isNotEmpty() && mode != "normal") {
            log("第 ${index + 1} 条「$label」 runMode=$mode 暂按 normal（顺序）执行")
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
                    log("第 ${index + 1} 条「$label」 已跑够 limitRunCount=$limit 个，结束本列表")
                    break
                }
                val kid = kids[ci]
                val kidLabel = "$label › 子${ci + 1}「${ZScriptIO.actionLabel(kid)}」"
                /*
                 * ★ 子动作也要做变量代换。
                 *
                 *   以前只在外层循环调用 applyLiteralVars，组合动作里的子动作
                 *   完全不做 —— 真机表现：主脚本第 1 关的点波写着
                 *   `__vars.pvzWaveTimes = zdjl.getVar('心塔_点波次数')`，
                 *   跑起来只点 1 次，日志连「变量→extra」那行都没有。
                 */
                val kidVars = applyLiteralVars(kid)
                if (kidVars.blockedField != null) {
                    val why = "__vars：字段「${kidVars.blockedField}」由变量定义，但取值求不出来"
                    log("第 ${index + 1} 条「$kidLabel」 $why")
                    childSkips.add(Result.Skip(index, kidLabel, why))
                    ci++
                    continue
                }
                val kidReal = kidVars.action
                ran++
                // 子动作：识别失败只跳过这一条（不牵连外层任务）
                com.pvz2.auto.core.ScriptRunState.enterChild(
                    label, ci + 1, kids.size, ZScriptIO.actionLabel(kidReal)
                )
                val outcome = executeWithRepeat(
                    kidReal, index, kidLabel, childSkips, abortOnRecognitionFail = false
                )
            
                // 停止：协议默认语义就是全停，子列表不能把它吞掉
                if (outcome.stop) {
                    log("第 ${index + 1} 条「$kidLabel」 请求停止整个脚本")
                    return Outcome(stop = true)
                }
            
                // 名额：只有开启 limitRunCountOnlySuc 时失败才不占名额
                val success = outcome.abort == null
                if (!onlySuc || success) counted++
            
                if (outcome.breakShell) {
                    log("第 ${index + 1} 条「$kidLabel」 要求只结束本子列表（ContinueParentExecute）")
                    break
                }
            
                if (outcome.abort != null) {
                    /*
                     * 子动作识别失败**不终止整条任务**：列表里某一条没认出来，
                     * 通常只意味着这一条没命中，后面的还该跑。
                     */
                    failed++
                    log("第 ${index + 1} 条「$kidLabel」 识别失败（子动作，不终止任务）：${outcome.abort}")
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
                                log("第 ${index + 1} 条「$label」 子列表跳转超过 $MAX_SUB_JUMPS 次，强制结束（八成是死循环）")
                                break
                            }
                            log("第 ${index + 1} 条「$kidLabel」 子列表跳转到第 ${target + 1} 条（$jump）")
                            ci = target
                            continue
                        }
                        log("第 ${index + 1} 条「$kidLabel」 子列表跳转「$jump」超出本列表范围（共 ${kids.size} 条），忽略")
                    } else {
                        log("第 ${index + 1} 条「$kidLabel」 的跳转「$jump」指向外层脚本，被忽略（作用域是子列表）")
                    }
                }
            
                if (breakCond != null && evaluate(breakCond, index, childSkips)) {
                    log("第 ${index + 1} 条「$label」 中止条件（breakCondition）命中，结束本列表")
                    break
                }
                ci++
            }
        } finally {
            subShellDepth--
        }

        // 子动作的失败明细只进日志（数量可能很大，塞进 Result 会刷屏），
        // 但**汇总一条**进结果，免得用户以为"子列表全部成功"。
        childSkips.forEach { log("第 ${index + 1} 条「$label」 子动作跳过：${it.label} — ${it.reason}") }
        if (failed > 0) {
            skipped.add(Result.Skip(index, label, "子动作里有 $failed 条识别失败（未终止任务）"))
        }
        log(
            "第 ${index + 1} 条「$label」 子动作执行 $ran 个，计入 $counted 个" +
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
     *   脚本格式把计数存在 `WeakMap`、以条件对象为键（6005.js），
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
             *   脚本格式（10426.js）：`parsedTime = Date.now() + offset`，
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
     * 脚本格式允许把任意字段改成"运行时求值"，落盘时原字段会被**删掉**
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
        val unsupported = vars.keys.filter { it !in WATCHED_VAR_FIELDS && !it.startsWith("pvz") }
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
            val literal = literalOf(def)
                ?: evalVarExpression(def.optString("valueExp").trim(), def.optString("varType"))
                ?: return VarsApplied(action, field)
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

        /*
         * 游戏节点私有参数（extra）也支持变量：`pvzWaveTimes` / `pvzPostMs` / `pvzSwipeMs` 等。
         *
         * 这些键不在 WATCHED_VAR_FIELDS 里（那是协议标准字段），但协议落盘时**同样**会把
         * 被变量化的原字段删掉。结果就是：界面下拉选了"7 秒"，脚本里根本没有
         * `pvzWaveTimes`，执行时按默认值跑 —— 变量看着生效、实际无效。
         */
        var extra = action.extra
        var extraChanged = false
        for ((field, def) in vars) {
            if (!field.startsWith("pvz")) continue
            val exp = def.optString("valueExp").trim()
            val type = def.optString("varType")
            /*
             * ★ 先看这段 valueExp 是不是"代码"。
             *
             *   真机日志暴露过：`varType=string` + `valueExp=zdjl.getVar('心塔_点波次数')`
             *   时，literalOf 把整段表达式当成字面量字符串返回，于是
             *   pvzWaveTimes 被写成 "zdjl.getVar('心塔_点波次数')" 这串文本，
             *   点波只点 1 次，日志还写着「变量→extra：pvzWaveTimes = zdjl.getVar(...)」。
             *
             *   判据：含 `(` 或 `zdjl.` 就不是普通字符串，按表达式求值。
             */
            val looksLikeCode = type in JS_VAR_TYPES || exp.contains("(") || exp.contains("zdjl.")
            val literal = if (looksLikeCode) {
                /*
                 * 注意：这里**强制按 expression 求值**。
                 * evalVarExpression 只认 varType=expression/random，而脚本里常写成
                 * varType=string + valueExp=zdjl.getVar(...)；直接传 "string" 会求值失败，
                 * 又退回字面量（就是真机那个"点波只点一次"的 bug）。
                 */
                evalVarExpression(exp, "expression") ?: literalOf(def)
            } else {
                literalOf(def) ?: evalVarExpression(exp, type)
            } ?: return VarsApplied(action, field)
            if (!extraChanged) {
                extra = LinkedHashMap(extra)
                extraChanged = true
            }
            extra[field] = literal.text
            log("   变量→extra：$field = ${literal.text}")
        }
        return if (changed || extraChanged) {
            VarsApplied(action.copy(common = common, extra = extra))
        } else {
            VarsApplied(action)
        }
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
     * 跑一个「游戏动作」（种植物 / 喂豆 / 铲除 / 点击格子 / 使用神器）。
     *
     * ## 为什么这些不是坐标节点
     *
     * 玩家想的是"3 号卡槽的植物种到 2 行 5 列"，不是两个像素点。
     * 点位表在 [PvzCoords]，改表不用改脚本。
     *
     * ## 参数校验一律明说
     *
     * 行列越界、卡槽缺失、屏幕不是 1280×720，全部写进日志/跳过原因 ——
     * 这些是"点了但没反应"的最常见原因，闷头点下去最难查。
     */
    /**
     * 切换编队：**先识别、再滑动**，直到认出目标编队为止。
     *
     * ## 为什么要"先认后滑"
     *
     * 用户原话："在划之前要先识别一次，防止用户选的编队1，划的时候直接把
     * 编队一划走了就永远识别不到了"。所以每一轮都是：先扫一眼编队号那一竖条，
     * 认到目标就点它收工；没认到才滑一段。
     *
     * ## 滑到不能滑怎么办
     *
     * 用户："到底就停住，可以加一个划个 5、6 次就往反方向划，一直循环"。
     * 所以连续误滑 [PvzCoords.DECK_REVERSE_AFTER] 次就换方向，来回找。
     */
    private fun switchDeck(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        val deck = intExtra(action.extra, "pvzDeck") ?: 0
        if (deck !in 1..PvzCoords.DECK_COUNT) {
            val why = "切换编队缺目标（pvzDeck 应为 1..${PvzCoords.DECK_COUNT}，实际 $deck）"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $why")
            return null
        }
        val pos = Z.PosData(
            type = Z.PosType.TEXT,
            text = deck.toString(),
            similarPercent = 90,
            limitArea = ZArea.fromPxRect(
                PvzCoords.DECK_AREA[0], PvzCoords.DECK_AREA[1],
                PvzCoords.DECK_AREA[2] - PvzCoords.DECK_AREA[0],
                PvzCoords.DECK_AREA[3] - PvzCoords.DECK_AREA[1],
                PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
            )
        )
        /*
         * 前置：先确认在**选卡界面**（有「开始战斗」），再点「点击编队」进去。
         *
         * 用户实测反馈："你都没有点到编队界面里面，你在植物界面划" ——
         * 直接在关卡里滑当然永远认不到编队号。顺序照他写的脚本：
         *   等「开始战斗」出现 → 点「点击编队」(763,394) → 才去认/滑。
         */
        val startPos = Z.PosData(
            type = Z.PosType.TEXT, text = "开始战斗", similarPercent = 90,
            limitArea = ZArea.fromPxRect(
                PvzCoords.START_BATTLE_AREA[0], PvzCoords.START_BATTLE_AREA[1],
                PvzCoords.START_BATTLE_AREA[2] - PvzCoords.START_BATTLE_AREA[0],
                PvzCoords.START_BATTLE_AREA[3] - PvzCoords.START_BATTLE_AREA[1],
                PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
            )
        )
        var ready = false
        val waitUntil = System.currentTimeMillis() + 20_000
        while (!stopped && System.currentTimeMillis() < waitUntil) {
            if (matcher.findText(startPos, PvzCoords.START_BATTLE_AREA).isNotEmpty()) {
                ready = true
                break
            }
            waitBusy(300)
        }
        if (!ready) {
            val why = "切换编队：20 秒内没看到「开始战斗」，可能不在选卡界面"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $why")
            return null
        }
        log("   切换编队：已到选卡界面，点「点击编队」(${PvzCoords.DECK_ENTRY[0]},${PvzCoords.DECK_ENTRY[1]})")
        inject.tap(PvzCoords.DECK_ENTRY[0], PvzCoords.DECK_ENTRY[1], 0)
        waitBusy(1200)

        /*
         * ★ 编队 1 特殊处理：不识别，直接点死点。
         *
         *   用户实测："编队1 识别的不准，改成如果选的编队1就不识别了，
         *   点到编队界面等 2 秒就点 (157,231)，这个坐标是没有滑动时的编队1的坐标"。
         *   原因是数字「1」容易和别的字符混淆（日志里认出过 2/3/4/5 和「A」，就是没有 1）。
         */
        if (deck == 1) {
            log("   切换编队：目标编队 1 → 不识别，等 ${PvzCoords.DECK_1_WAIT_MS}ms 直接点 (${PvzCoords.DECK_1_POINT[0]},${PvzCoords.DECK_1_POINT[1]})")
            waitBusy(PvzCoords.DECK_1_WAIT_MS)
            inject.tap(PvzCoords.DECK_1_POINT[0], PvzCoords.DECK_1_POINT[1], 0)
            intExtra(action.extra, "pvzPostMs")?.let { if (it > 0) waitBusy(it.toLong()) }
            return Outcome()
        }

        val rect = PvzCoords.DECK_AREA
        var forward = true          // true = 往上翻（露出后面的编队）
        var sinceReverse = 0
        var rounds = 0
        log("   切换编队 → 编队 $deck（先认后滑）")
        while (!stopped) {
            rounds++
            // ① 先认一次
            val hits = matcher.findText(pos, rect)
            if (hits.isNotEmpty()) {
                val hit = hits.first()
                log("   认出编队 $deck 在第 $rounds 轮 (${hit.x},${hit.y})，点它")
                inject.tap(hit.x, hit.y, 0)
                return Outcome()
            }
            // ② 没认到 → 滑一段（方向按连续失败次数翻转）
            if (sinceReverse >= PvzCoords.DECK_REVERSE_AFTER) {
                if (rounds > PvzCoords.DECK_REVERSE_AFTER * 2) {
                    // 正反各扫一轮仍没有 → 明确放弃，不无限滑
                    val why = "切换编队：来回扫过一轮没找到编队 $deck"
                    skipped.add(Result.Skip(index, label, why))
                    log("[跳过] $why")
                    return null
                }
                forward = !forward
                sinceReverse = 0
                log("   连续 ${PvzCoords.DECK_REVERSE_AFTER} 次没认出，反向滑")
            }
            val from = PvzCoords.DECK_SWIPE_UP
            val x1 = from[0]; val y1 = if (forward) from[1] else from[3]
            val x2 = from[2]; val y2 = if (forward) from[3] else from[1]
            log("   没认出编队 $deck（第 $rounds 轮），滑一段 ${if (forward) "上翻" else "下翻"}")
            inject.swipe(x1, y1, x2, y2, PvzCoords.DECK_SWIPE_MS)
            sinceReverse++
        }
        return Outcome()
    }

    /**
     * 「选补给」组合动作：一个节点跑完整条补给链。
     *
     * 用户问："为什么不把选补给做成组合动作" —— 对，摊成 12 个节点既难改也难读。
     * 这里把 参考管线 `Supply.json` 的整条链收进一个动作里，编译时只出一个节点。
     *
     * 顺序与延迟 1:1 照抄（见 PvzSupplyChain 的注释）：
     *   查看(2000) → 判断(1000) → 分类候选 → 兜底(颜色条件) → 确定1(0)
     *   → [提示 | 确定3] → 确定3(1000) → 确定4(1000)
     */
    private fun runPvzSupply(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        val order = (action.extra["pvzSupplyOrder"]?.toString() ?: "")
            .split("|").map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { PvzSupplyChain.defaultOrder }
        /*
         * ★ CATEGORY_AREA 是 MAA 的 roi 写法：[x, y, 宽, 高]。
         *
         *   这里曾经按"左 上 右 下"去算 w/h：
         *     w = 909 - 194 = 715
         *     h = 80  - 237 = -157   ← 负数
         *   limitArea 于是变成上边 237、下边 80 的"倒矩形"，
         *   OCR 在这一块当然一个字都认不出 —— 真机表现就是"直接点了第一张卡片"。
         *   统一按 x,y,w,h 解释，matcher 用的 rect 再换算成 l,t,r,b。
         */
        val area = PvzSupplyChain.CATEGORY_AREA
        val catRect = intArrayOf(area[0], area[1], area[0] + area[2], area[1] + area[3])
        fun tapPt(pt: IntArray) { inject.tap(pt[0], pt[1], 0) }
        fun sleep(ms: String) { ms.toIntOrNull()?.let { if (it > 0) waitBusy(it.toLong()) } }
        /*
         * 分类标签：区域用 MAA 的 [194,237,909,80]（= x,y,宽,高），相似度用 80。
         *
         * 两处都是真机实测出来的：
         *  · 区域必须按 x,y,宽,高 解释。按"左上右下"算会得到 h = 80-237 = -157 的
         *    倒矩形，OCR 一个字都认不出（这正是以前"直接点第一张卡片"的原因）。
         *  · 相似度 80 才认得出。同一块区域、同样文字，90% 一条都不中，
         *    80% 立刻 100% 命中（自检_21 点「蓝色植物」就是 80% 认出来的）。
         */
        fun textPos(name: String) = Z.PosData(
            type = Z.PosType.TEXT, text = name, similarPercent = 80,
            limitArea = ZArea.fromPxRect(
                area[0], area[1], area[2], area[3],
                PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
            )
        )

        /*
         * ① 先确认**在补给界面**，再往下走。
         *
         *   用户："开始应该是判断当前是否在补给界面，确定在补给界面了再接着往下走"。
         *   判据用 参考管线 Supply.json 那个 `补给` 节点本身（色块 roi[1085,84,26,33]）。
         *   等不到就整条跳过 —— 绝不在别的界面上瞎点「查看」。
         */
        val uiRoi = PvzSupplyChain.SUPPLY_UI_ROI
        val uiPos = Z.PosData(
            type = Z.PosType.COLOR, color = PvzSupplyChain.SUPPLY_UI_COLOR,
            similarPercent = 85,
            limitArea = ZArea.fromPxRect(
                uiRoi[0], uiRoi[1], uiRoi[2] - uiRoi[0], uiRoi[3] - uiRoi[1],
                PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
            )
        )
        log("   选补给：先确认在补给界面（最多等 ${PvzSupplyChain.SUPPLY_UI_WAIT_MS}ms）")
        val uiDeadline = System.currentTimeMillis() + PvzSupplyChain.SUPPLY_UI_WAIT_MS
        var onSupplyUi = false
        while (!stopped && System.currentTimeMillis() < uiDeadline) {
            if (matcher.findColor(uiPos, uiRoi).isNotEmpty()) {
                onSupplyUi = true
                break
            }
            waitBusy(200)
        }
        if (!onSupplyUi) {
            val why = "选补给：没认出补给界面（色块未出现），不点「查看」"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $why")
            return null
        }
        log("   选补给：确认在补给界面")

        // 查看
        log("   选补给：点「查看」(${PvzSupplyChain.VIEW[0]},${PvzSupplyChain.VIEW[1]})")
        tapPt(PvzSupplyChain.VIEW)
        sleep(PvzSupplyChain.DELAY_VIEW)

        // 判断：先认分类
        sleep(PvzSupplyChain.DELAY_CHECK)
        var picked: String? = null
        for (name in order) {
            if (stopped) return null
            val hits = matcher.findText(textPos(name), catRect)
            if (hits.isNotEmpty()) {
                val h = hits.first()
                log("   选补给：认出「$name」(${h.x},${h.y})，点它")
                inject.tap(h.x, h.y, 0)
                picked = name
                break
            }
        }
        if (picked == null) {
            /*
             * 第二遍：放宽到 60% 只找「全部」。
             *
             * 90% 那一遍在真机上一条都没认出（玩家反馈：直接点了第一张卡片）。
             * MAA 用的 ROI/文字原值不变，变的只是我们 OCR 的容差 —— 分类栏是
             * 半透明底上的小字，OCR 认出来常常只有六七成相似度。
             */
            for (name in listOf("全部植物", "全部")) {
                if (stopped) return null
                val loose = Z.PosData(
                    type = Z.PosType.TEXT, text = name, similarPercent = 60,
                    limitArea = ZArea.fromPxRect(
                        area[0], area[1], area[2], area[3],
                        PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                    )
                )
                val hits = matcher.findText(loose, catRect)
                if (hits.isNotEmpty()) {
                    val h = hits.first()
                    log("   选补给：宽容匹配认出「$name」(${h.x},${h.y})，点它")
                    inject.tap(h.x, h.y, 0)
                    picked = name
                    break
                }
            }
        }
        if (picked == null) {
            /*
             * 认不出时把那一块 OCR 的原始结果打出来。
             *
             * 之前只能看到"一个分类都没认出"，分不清是"区域错了"、"字太小"
             * 还是"确实没有这几个标签"。matcher.lastReason() 里就是识别器
             * 对这一块的原话（几行、内容、最高相似度），直接抄进日志。
             */
            log("   选补给：分类区 OCR 原始结果 → " + matcher.lastReason())
            // 兜底：参考管线 `buji_补给` 点第一个卡片
            log("   选补给：一个分类都没认出 → 兜底点第一个卡片")
            tapPt(PvzSupplyChain.FALLBACK_FIRST_CARD)
        }
        sleep(PvzSupplyChain.DELAY_CATEGORY)

        /*
         * 确定 ×3 —— 照 MAA `通用_神器补给_确定1/2/3`：
         *
         *   确定1 Click[950,921] pre1000
         *   确定2 Click[950,921] pre2000
         *   确定3 Click[950,921] pre2000 → [Anchor]
         *
         * 以前我们只点两次、中间还插了一段「提示」弹窗识别。真机结果是
         * 点完仍在补给界面没退出（探针：绿点/查看都还在），于是后面的
         * BOSS 链等「开始战斗」白等 60 秒。MAA 这条链里没有提示弹窗那一步，
         * 就老老实实点三次、每次都等足。
         */
        log("   选补给：确定1")
        tapPt(PvzSupplyChain.CONFIRM)
        waitBusy(1000)

        // 提示弹窗（选到有上限的补给会弹）
        val tipHits = matcher.findText(
            Z.PosData(
                type = Z.PosType.TEXT, text = "提示", similarPercent = 90,
                limitArea = ZArea.fromPxRect(
                    PvzSupplyChain.TIP_AREA[0], PvzSupplyChain.TIP_AREA[1],
                    PvzSupplyChain.TIP_AREA[2] - PvzSupplyChain.TIP_AREA[0],
                    PvzSupplyChain.TIP_AREA[3] - PvzSupplyChain.TIP_AREA[1],
                    PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                )
            ),
            PvzSupplyChain.TIP_AREA
        )
        if (tipHits.isNotEmpty()) {
            log("   选补给：出现「提示」（补给有上限），点掉 (${PvzSupplyChain.TIP_TAP[0]},${PvzSupplyChain.TIP_TAP[1]})")
            tapPt(PvzSupplyChain.TIP_TAP)
            waitBusy(500)
        }

        log("   选补给：确定2")
        tapPt(PvzSupplyChain.CONFIRM)
        waitBusy(2000)
        log("   选补给：确定3")
        tapPt(PvzSupplyChain.CONFIRM)
        waitBusy(2000)

        /*
         * 收尾自检：确认真的离开补给界面。
         *
         * 没离开就是没领成功（以前会静默往下走，然后在 BOSS 链白等 60 秒）。
         */
        if (!stopped) {
            val still = matcher.findColor(
                Z.PosData(
                    type = Z.PosType.COLOR, color = PvzSupplyChain.SUPPLY_UI_COLOR, similarPercent = 85,
                    limitArea = ZArea.fromPxRect(
                        uiRoi[0], uiRoi[1], uiRoi[2] - uiRoi[0], uiRoi[3] - uiRoi[1],
                        PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                    )
                ),
                uiRoi
            )
            if (still.isNotEmpty()) {
                log("   选补给：三次确定后仍在补给界面 —— 这次没领到，继续往下（不影响主循环）")
            } else {
                log("   选补给：已离开补给界面")
            }
        }
        return Outcome()
    }

    /**
     * 三个"闸门"型游戏节点：开始战斗 / 进关判定 / 识别结算。
     *
     * 共同点：都是"等一个画面 → 点它"，只是判据不同：
     *  - 开始战斗：OCR「开始战斗」（yuan_big ROI [915,530,357,181]）
     *  - 进关判定：ColorMatch 绿点（[467,669,27,27]），命中后顺手点加速 [1171,37]
     *  - 识别结算：OCR「继续挑战」（[716,621,179,50]）
     *
     * 都带 `pvzWaitMs`（默认 20000）的等待上限，等不到就明确跳过并说明，
     * 不会像以前那样无限等。
     */
    /**
     * 运行期内存自检：每 30 秒往日志里写一条 Native Heap / MemFree / PSS。
     *
     * 为什么要它：崩溃前几分钟到底是不是内存在涨，过去只能靠我在电脑上反复
     * 抓 meminfo —— 用户那边一旦崩了就什么都看不到。现在自己记，崩了直接看日志。
     *
     * 只读 `/proc/self/statm` 与 `Debug`，不额外启动进程、不开线程池。
     */
    private var memWatch: Thread? = null

    private fun startMemoryWatch() {
        if (memWatch != null) return
        val t = Thread {
            var lastNative = -1L
            var lastFrames = -1L
            var lastDrops = -1L
            var lastFramesAt = 0L
            while (!stopped) {
                try {
                    Thread.sleep(30_000)
                    val nowMs = System.currentTimeMillis()
                    if (stopped) break
                    val nativeMb = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                    // 虚拟屏实际出帧率（≈ 游戏帧率），自己数出来，不靠 dumpsys
                    val frames = com.pvz2.auto.service.VirtualDisplaySession.frameCountProvider()
                    /*
                     * ★ 帧率守卫：虚拟屏重建会让帧计数归零，差值变负；
                     *   同一时刻若有两路写着也可能翻倍（曾报出 120.0fps 的假数）。
                     *   所以只接受 0..200 的合理值，其余记 0。
                     */
                    val fps = if (lastFrames in 0..frames && nowMs > lastFramesAt) {
                        ((frames - lastFrames) * 1000.0 / (nowMs - lastFramesAt))
                            .takeIf { it in 0.0..200.0 } ?: 0.0
                    } else 0.0
                    lastFrames = frames
                    lastFramesAt = nowMs
                    // 采集端因共享环三块都在用而主动丢弃的帧数（增量）
                    val dropsNow = runCatching {
                        com.pvz2.auto.shizuku.NativeCapture.droppedFrames()
                    }.getOrDefault(0L)
                    val dropDelta = if (lastDrops < 0) 0L else dropsNow - lastDrops
                    lastDrops = dropsNow
                    val freeMb = readMemFreeMb()
                    val delta = if (lastNative < 0) 0 else nativeMb - lastNative
                    lastNative = nativeMb
                    log(
                        "[内存] Native=${nativeMb}MB (${if (delta >= 0) "+" else ""}$delta) " +
                            "MemFree=${freeMb}MB 帧率=${String.format(java.util.Locale.US, "%.1f", fps)}fps" +
                            " 丢帧=${dropDelta}/30s"
                    )
                } catch (t: Throwable) {
                    break
                }
            }
        }
        t.isDaemon = true
        t.name = "zjs-mem-watch"
        memWatch = t
        t.start()
    }

    /** 从 /proc/meminfo 读 MemFree（MB）。读不到返回 -1。 */
    private fun readMemFreeMb(): Long = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemFree:") }
                ?.split(Regex("\\s+"))
                ?.getOrNull(1)
                ?.toLongOrNull()
                ?.div(1024)
                ?: -1L
        }
    }.getOrDefault(-1L)

    /**
     * 「切换形态」：在 **(固定 X = 163, 卡槽对应的 Y)** 那个点连点 N 下。
     *
     * 用户说明：
     *  - "你记住这个坐标的 X 坐标，他是不变的" —— 所以 X 固定 163
     *  - "Y 随卡槽号走，卡槽坐标就用我们的就行" —— Y 取 [PvzCoords.slot]
     *  - "切换次数是 spinner，只能 1/2/3，选几次就点几次"
     *  - "每次点击的间隔和现在这个间隔一样" / "maa 缺省默认是 200ms"
     *
     * 球果就是靠这个切形态：原本脚本里那个坐标点两下 = 切换次数 2。
     */
    /**
     * 「使用神器」：先点神器图标，等 [PvzCoords.ARTIFACT_TAP_GAP_MS]（500ms），再按种类点第二下。
     *
     * 用户给出的规则：
     *  - 葫芦 → 「体型」大/中/小 → 点对应点位
     *  - 魔豆 → 「释放位置」选一格 → 点那一格
     *  - 相机 → 「复制 / 粘贴」→ 点对应点位
     *  - 保龄球 / 棱镜塔 → 点一下神器位置就完（没有第二下）
     *
     * 也兼容旧值：gourd_s/gourd_m/gourd_l、camera_copy/camera_paste、bean。
     */
    private fun runPvzArtifact(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        var kind = action.extra["pvzArtifact"]?.toString()?.trim().orEmpty()
        var size = action.extra["pvzGourdSize"]?.toString()?.trim().orEmpty()
        var mode = action.extra["pvzCameraMode"]?.toString()?.trim().orEmpty()
        // 旧值兼容
        when (kind) {
            "gourd_s" -> { kind = "gourd"; if (size.isEmpty()) size = "小" }
            "gourd_m" -> { kind = "gourd"; if (size.isEmpty()) size = "中" }
            "gourd_l" -> { kind = "gourd"; if (size.isEmpty()) size = "大" }
            "camera_copy" -> { kind = "camera"; if (mode.isEmpty()) mode = "复制" }
            "camera_paste" -> { kind = "camera"; if (mode.isEmpty()) mode = "粘贴" }
        }
        if (PvzCoords.ARTIFACT_KINDS.none { it.first == kind }) {
            val why = "使用神器缺「神器」（可选：" +
                PvzCoords.ARTIFACT_KINDS.joinToString("、") { it.second } + "）"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $why")
            return null
        }
        val icon = PvzCoords.ARTIFACT
        log("   神器：点神器图标 (${icon[0]},${icon[1]})")
        inject.tap(icon[0], icon[1], 0)
        waitBusy(PvzCoords.ARTIFACT_TAP_GAP_MS)

        when (kind) {
            "gourd" -> {
                val pick = PvzCoords.GOURD_SIZES.firstOrNull { it.first == size }
                    ?: PvzCoords.GOURD_SIZES.first { it.first == "中" }
                log("   神器：葫芦·${pick.first} → (${pick.third[0]},${pick.third[1]})")
                inject.tap(pick.third[0], pick.third[1], 0)
            }
            "camera" -> {
                val pick = PvzCoords.CAMERA_MODES.firstOrNull { it.first == mode }
                    ?: PvzCoords.CAMERA_MODES.first()
                log("   神器：相机·${pick.first} → (${pick.second[0]},${pick.second[1]})")
                inject.tap(pick.second[0], pick.second[1], 0)
            }
            "bean" -> {
                val row = intExtra(action.extra, "pvzRow") ?: 0
                val col = intExtra(action.extra, "pvzCol") ?: 0
                val cell = if (row in 1..PvzCoords.ROWS && col in 1..PvzCoords.COLS) {
                    PvzCoords.cell(row, col)
                } else null
                if (cell == null) {
                    val why = "使用神器（魔豆）缺「释放位置」（行 $row / 列 $col）"
                    skipped.add(Result.Skip(index, label, why))
                    log("[跳过] $why")
                    return null
                }
                log("   神器：魔豆 → 释放到 ${PvzCoords.cellLabel(row, col)} (${cell[0]},${cell[1]})")
                inject.tap(cell[0], cell[1], 0)
            }
            else -> log("   神器：${PvzCoords.ARTIFACT_KINDS.first { it.first == kind }.second} 只需点一下")
        }
        intExtra(action.extra, "pvzPostMs")?.let { if (it > 0) waitBusy(it.toLong()) }
        return Outcome()
    }

    private fun runPvzSwitchForm(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        val slotNo = intExtra(action.extra, "pvzSlot") ?: 0
        if (slotNo !in 1..8) {
            val why = "切换形态缺卡槽（应为 1..8，实际 $slotNo）"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $why")
            return null
        }
        val times = (intExtra(action.extra, "pvzSwitchTimes") ?: 1)
            .coerceIn(1, PvzCoords.SWITCH_FORM_MAX_TIMES)
        val slot = PvzCoords.slot(slotNo)
        if (slot == null) {
            val why = "切换形态取不到卡槽$slotNo 的坐标"
            skipped.add(Result.Skip(index, label, why))
            log("[跳过] $why")
            return null
        }
        val x = PvzCoords.SWITCH_FORM_X
        val y = slot[1]
        log("   切换形态：卡槽$slotNo → ($x,$y) 点击 $times 次（间隔 ${PvzCoords.SWITCH_FORM_INTERVAL_MS}ms）")
        for (i in 1..times) {
            if (stopped) return null
            inject.tap(x, y, 0)
            if (i < times) waitBusy(PvzCoords.SWITCH_FORM_INTERVAL_MS)
        }
        intExtra(action.extra, "pvzPostMs")?.let { if (it > 0) waitBusy(it.toLong()) }
        return Outcome()
    }

    private fun runPvzGate(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        val waitMs = (intExtra(action.extra, "pvzWaitMs") ?: 20_000).coerceIn(500, 600_000)
        com.pvz2.auto.core.ScriptRunState.nodeTimeout(waitMs)
        val deadline = System.currentTimeMillis() + waitMs
        val interval = 200L

        // 判据
        val (roi, pos) = when (action.type) {
            /*
             * 进关绿点：#9EDF12 是 MAA `判断是否进入关内` 的色域中点
             * （lower [148,213,8] ~ upper [168,233,28] → (158,223,18)）。
             *
             * 原来写的是 #9AD216 = (154,210,22)，G=210 **低于 MAA 下限 213**，
             * 属于"看起来一样、其实落在容差外"的颜色 —— 审计时必须按数值比，
             * 不能按"绿色"这个印象比。
             */
            Z.ActionType.PVZ_ENTER_CHECK -> PvzCoords.ENTER_ROI to Z.PosData(
                type = Z.PosType.COLOR, color = "#9AD216", similarPercent = 85,
                limitArea = ZArea.fromPxRect(
                    PvzCoords.ENTER_ROI[0], PvzCoords.ENTER_ROI[1],
                    PvzCoords.ENTER_ROI[2] - PvzCoords.ENTER_ROI[0],
                    PvzCoords.ENTER_ROI[3] - PvzCoords.ENTER_ROI[1],
                    PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
                )
            )
            Z.ActionType.PVZ_SETTLE -> PvzCoords.SETTLE_ROI to textPosOf("继续挑战", PvzCoords.SETTLE_ROI)
            else -> PvzCoords.START_BATTLE_ROI to textPosOf("开始战斗", PvzCoords.START_BATTLE_ROI)
        }

        val what = when (action.type) {
            Z.ActionType.PVZ_ENTER_CHECK -> "进关绿点"
            Z.ActionType.PVZ_SETTLE -> "「继续挑战」"
            else -> "「开始战斗」"
        }
        log("   ${label}：等 $what（最多 ${waitMs}ms）")
        while (!stopped && System.currentTimeMillis() < deadline) {
            val hits = when (action.type) {
                Z.ActionType.PVZ_ENTER_CHECK -> matcher.findColor(pos, roi)
                else -> matcher.findText(pos, roi)
            }
            if (hits.isNotEmpty()) {
                val h = hits.first()
                if (action.type == Z.ActionType.PVZ_SETTLE ||
                    action.type == Z.ActionType.PVZ_START_BATTLE
                ) {
                    log("   认出 $what (${h.x},${h.y})，点它")
                    inject.tap(h.x, h.y, 0)
                } else {
                    /*
                     * 进关判定：默认**不自动点加速**（用户要求过：有些关卡开局不能马上加速，
                     * 由他在链里自己放「点加速」控制时机）。
                     *
                     * 但 MAA 的 `2_xinta3_判断是否进入关内` 是 ColorMatch + Click[1171,37]，
                     * 也就是"认出绿点就顺手点加速"。所以给节点加一个开关
                     * `pvzClickSpeed`（是/否，默认否），心塔 3-149 关的链里设成"是"，
                     * 就能与 MAA 一致，同时不影响其它脚本。
                     */
                    if (boolExtra(action, "pvzClickSpeed")) {
                        log("   已进关 → 点加速 (${PvzCoords.SPEED_ON_ENTER[0]},${PvzCoords.SPEED_ON_ENTER[1]})")
                        inject.tap(PvzCoords.SPEED_ON_ENTER[0], PvzCoords.SPEED_ON_ENTER[1], 0)
                    } else {
                        log("   已进关（不自动点加速）")
                    }
                }
                intExtra(action.extra, "pvzPostMs")?.let { if (it > 0) waitBusy(it.toLong()) }
                return Outcome()
            }
            waitBusy(interval)
        }
        val why = "${label}：${waitMs}ms 内没等到 $what" +
            (matcher.lastReason()?.let { "（$it）" } ?: "")
        skipped.add(Result.Skip(index, label, why))
        log("[跳过] $why")
        return null
    }

    /** 造一个"认这几个字 + 限定区域"的文字位置。 */
    private fun textPosOf(text: String, roi: IntArray): Z.PosData = Z.PosData(
        type = Z.PosType.TEXT, text = text, similarPercent = 80,
        limitArea = ZArea.fromPxRect(
            roi[0], roi[1], roi[2] - roi[0], roi[3] - roi[1],
            PvzCoords.BASE_WIDTH, PvzCoords.BASE_HEIGHT
        )
    )

    private fun runPvzAction(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        if (!PvzCoords.fitsBase(screen.width, screen.height)) {
            log(
                "   [警告] 点位表按 ${PvzCoords.BASE_WIDTH}×${PvzCoords.BASE_HEIGHT} 标定，" +
                    "当前屏幕 ${screen.width}×${screen.height}，点下去可能偏"
            )
        }
        /*
         * 使用神器：**必须在最前面分派**。
         *
         *   原来插在函数下部（旧 when 判据之后），于是先被旧分支拦下 ——
         *   日志里那句"使用神器缺「神器」参数（可选：bean、gourd_s…）"就是旧判据，
         *   新写的 gourd/bowling/bean/prism/camera 全都不认，除了偶然对上的 bean，
         *   其它神器一个位置都不点、200ms 就结束。
         */
        if (action.type == Z.ActionType.PVZ_ARTIFACT) {
            return runPvzArtifact(action, index, label, skipped)
        }

        // 切换编队：先认后滑、6 次反向、循环，直到认出目标编队并点它
        if (action.type == Z.ActionType.PVZ_SWITCH_DECK) {
            return switchDeck(action, index, label, skipped)
        }

        // 点波 / 加速：固定点位，不需要行列
        val fixed = when (action.type) {
            Z.ActionType.PVZ_WAVE -> PvzCoords.NEXT_WAVE to "下一波"
            Z.ActionType.PVZ_SPEED -> PvzCoords.SPEED to "加速"
            else -> null
        }
        if (fixed != null) {
            /*
             * 点波可以连点：「次数」+「间隔」两个参数。
             *
             * 默认 1 次 / 300ms —— 点波是"催一波"，玩家经常连点两三下；
             * 次数写成 1 时行为和以前完全一致。
             */
            /*
             * 上限 50 是拍脑袋定的。MAA 的心塔链是 repeat=70（点波 70 次 / 20ms），
             * 被夹到 50 时日志只显示「第 1/50 次」，看不出被截断 —— 点波数量不足
             * 会让波次推进不到位。放到 999，够用且仍能挡住手滑写成几千。
             */
            val times = (intExtra(action.extra, "pvzWaveTimes") ?: 1).coerceIn(1, 999)
            val gap = (intExtra(action.extra, "pvzWaveInterval") ?: 300).coerceIn(0, 10_000)
            for (i in 1..times) {
                if (stopped) break
                com.pvz2.auto.core.ScriptRunState.repeatProgress((i - 1).toLong(), times)
                log(
                    "   点「${fixed.second}」(${fixed.first[0]},${fixed.first[1]})" +
                        if (times > 1) " 第 $i/$times 次" else ""
                )
                inject.tap(fixed.first[0], fixed.first[1], 0)
                if (i < times && gap > 0) waitBusy(gap.toLong())
            }
            return Outcome()
        }

        // 神器的点按类（相机复制/粘贴）不需要行列，必须放在校验之前
        if (action.type == Z.ActionType.PVZ_ARTIFACT) {
            val preset = PvzCoords.artifact(action.extra["pvzArtifact"]?.toString())
            if (preset == null) {
                val why = "使用神器缺「神器」参数（可选：" +
                    PvzCoords.ARTIFACTS.joinToString("、") { it.key } + "）"
                skipped.add(Result.Skip(index, label, why))
                log("[跳过] $why")
                return null
            }
            if (preset.use == PvzCoords.Use.TAP) {
                /*
                 * 葫芦 / 相机这类「选中即生效」的神器：
                 *   先点神器图标 → 弹出选择 → 再点具体那一项。
                 *
                 * 用户原话："葫芦使用后会吸走全场对应体型的僵尸，直接使用进行；
                 * 相机和葫芦使用前都要点一下神器才能选"。
                 * 所以那两个点位是**选择器里的按钮**，不是拖拽的起点。
                 */
                val open = PvzCoords.ARTIFACT
                log("   神器「${preset.label}」：点神器图标 (${open[0]},${open[1]})")
                inject.tap(open[0], open[1], 0)
                waitBusy(PvzCoords.ARTIFACT_PICK_GAP_MS)
                log("   神器「${preset.label}」：选「${preset.label}」(${preset.point[0]},${preset.point[1]})")
                inject.tap(preset.point[0], preset.point[1], 0)
                return Outcome()
            }
        }

        val row = intExtra(action.extra, "pvzRow")
        val col = intExtra(action.extra, "pvzCol")
        if (row == null || col == null) {
            val why = "游戏动作缺行列（pvzRow / pvzCol）"
            skipped.add(Result.Skip(index, label, why))
            return null
        }
        if (row !in 1..PvzCoords.ROWS || col !in 1..PvzCoords.COLS) {
            val why = "行列越界：第 ${row} 行第 ${col} 列（棋盘是 " +
                "${PvzCoords.ROWS} 行 × ${PvzCoords.COLS} 列）"
            skipped.add(Result.Skip(index, label, why))
            return null
        }
        val dst = PvzCoords.cell(row, col)
        if (dst == null) {
            val why = "点位表里没有 ${PvzCoords.cellLabel(row, col)}"
            skipped.add(Result.Skip(index, label, why))
            return null
        }

        // 起点：点击格子没有起点，其余四种各有一个固定起点
        var start: IntArray? = null
        var startName = ""
        when (action.type) {
            Z.ActionType.PVZ_PLANT -> {
                val slotNo = intExtra(action.extra, "pvzSlot")
                if (slotNo == null || slotNo !in 1..8) {
                    val why = "种植物缺卡槽号（pvzSlot 应为 1..8，实际 ${action.extra["pvzSlot"]}）"
                    skipped.add(Result.Skip(index, label, why))
                    return null
                }
                start = PvzCoords.slot(slotNo)
                startName = "卡槽$slotNo"
            }
            Z.ActionType.PVZ_FEED -> {
                start = PvzCoords.ENERGY_BEAN
                startName = "能量豆"
            }
            Z.ActionType.PVZ_SHOVEL -> {
                start = PvzCoords.SHOVEL
                startName = "铲子"
            }
            else -> Unit // 点击格子
        }

        /*
         * 拖动时长可以按节点指定（`pvzSwipeMs`）。
         *
         * YS 里"大哥滑动"是 500ms、"原豌滑动"是 100ms，抛花走默认 80ms ——
         * 三种速度是设计的一部分，不能统一成一个值。
         * 另外注入层现在按真实节奏滑满 duration，这里**不再额外 waitBusy**，
         * 否则 500ms 的动作会变成 1 秒。
         */
        /*
         * 「有条件才执行」：`pvzNeedColor` = "x1,y1,x2,y2,#RRGGBB,容差%"。
         *
         * yuan_big 里大哥滑动4/5 是**带条件的分叉**：
         *   UI 那块色带 ROI[115,250,7,45] 色域 250,140,0~255,167,18 命中才滑，
         *   没命中就直接跳到点波2。以前我把它们做成无条件滑动 —— 那是错的。
         */
        /* pvzNeedColor 已删除：新脚本一律用条件 colorFound（见 docs/NODES_AND_FIELDS.md）。 */

        val swipeMsRaw = intExtra(action.extra, "pvzSwipeMs")
        val swipeMs = (swipeMsRaw ?: 0).takeIf { it > 0 } ?: PvzCoords.SWIPE_MS
        if (swipeMsRaw == null && action.type in listOf(
                Z.ActionType.PVZ_PLANT, Z.ActionType.PVZ_FEED,
                Z.ActionType.PVZ_SHOVEL, Z.ActionType.PVZ_TAP_CELL
            )
            ) {
            log("   [诊断] pvzSwipeMs 没读到；extra 里有：${action.extra.keys.filter { it.contains("pvz") }}")
        }
        val at = PvzCoords.cellLabel(row, col)
        if (start == null) {
            log("   点击 $at (${dst[0]},${dst[1]})")
            inject.tap(dst[0], dst[1], 0)
            intExtra(action.extra, "pvzPostMs")?.let { if (it > 0) waitBusy(it.toLong()) }
        } else {
            log(
                "   $startName (${start[0]},${start[1]}) → $at (${dst[0]},${dst[1]}) " +
                    "用时 ${swipeMs}ms"
            )
            inject.swipe(start[0], start[1], dst[0], dst[1], swipeMs)
            intExtra(action.extra, "pvzPostMs")?.let { if (it > 0) waitBusy(it.toLong()) }
        }
        return Outcome()
    }

    /**
     * 用 JS 引擎求一个变量表达式（`varType` = `expression` / `random`）。
     *
     * ## 为什么只给这两个类型开口子
     *
     * `VarValue` 里 `expression` 和 `random` 的 `valueExp` 就是一段
     * **JS 表达式**（`100+50`、`Math.floor(Math.random()*200+100)`），
     * 我们手上已经有 Rhino，直接求值最省事也不会猜错语义。
     *
     * `js_function` / `text_extract` 之类不是表达式（是函数体或取文本规则），
     * 硬套 `String((...))` 只会得到一段函数源码 —— 那时仍然返回 null，
     * 由调用方明确跳过并报出来，绝不把源码当值用。
     */
    private fun evalVarExpression(exp: String, varType: String): VarLiteral? {
        if (varType !in JS_VAR_TYPES) return null
        if (exp.isBlank()) return null
        /*
         * 变量表用副本：求值不该改动脚本的运行时变量，
         * 但表达式里读 `${名字}` 之外的东西（比如另一个变量）仍然要能读到。
         */
        val tmp = HashMap(scriptVars)
        val out = JsEngine.run(
            "String(($exp))",
            JsEngine.Env(
                vars = tmp,
                log = { line -> log(line) },
                shouldStop = { stopped }
            )
        )
        if (!out.ok) {
            log("   变量表达式「$exp」求值失败：${out.error}")
            return null
        }
        val text = out.value?.trim().orEmpty()
        if (text.isEmpty() || text == "undefined" || text == "null" || text.startsWith("function")) {
            return null
        }
        return VarLiteral(text, text.toDoubleOrNull())
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
        log("第 ${index + 1} 条「$label」 — $msg")
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
    ): Outcome? {
        val cb = action.callbacks[slot] ?: return null
        if (stopped) return null
        if (cb.common.disabled) {
            log("第 ${index + 1} 条「$label」 监听 ${slot.wire}：动作已禁用，跳过")
            return null
        }
        if (!cb.type.executable) {
            log("第 ${index + 1} 条「$label」 监听 ${slot.wire}：${cb.type.wire} 在安卓上暂不支持")
            return null
        }

        val cbLabel = "监听·${slot.wire}"
        if (callbackDepth >= MAX_CALLBACK_DEPTH) {
            val msg = "监听嵌套超过 $MAX_CALLBACK_DEPTH 层，跳过（疑似自触发死循环）"
            log("第 ${index + 1} 条「$label」 $msg")
            // runCallbacks 没有 skipped 参数：这里只记日志（够用，原因已经写在上面那行）
            return null
        }
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
        callbackDepth++
        val r = try {
            executeWithRepeat(
                cb, index, cbLabel, cbSkips, abortOnRecognitionFail = false
            )
        } finally {
            callbackDepth--
        }
        /*
         * 监听的跳转/停止**要生效**。
         *
         * 以前这里只打一行"被忽略"，于是用户配了「条件失败后 → 跳转到某条」，
         * 脚本照样停在原地。现在把结果交给调用方：条件失败分支会直接返回它，
         * 等于 next 的效果。
         */
        val carried = if (r.jumpTo != null || r.stop) r else null
        if (r.breakShell) {
            log("第 ${index + 1} 条「$cbLabel」 里的「结束子列表」被忽略（作用域是外层脚本）")
        }
        // 监听失败不能污染外层：否则下一条「缺位置」之类的失败会带着它的名义终止整条任务
        abortReason = null
        // 槽内动作的失败原因不能吞掉：这是用户唯一能看到「监听做了什么」的地方
        cbSkips.forEach { log("第 ${index + 1} 条「$cbLabel」 跳过：${it.reason}") }
        log("第 ${index + 1} 条「$label」 监听 ${slot.wire} 已执行")
        if (carried?.jumpTo != null) log("   → 监听里要求跳转到「${carried.jumpTo}」")
        if (carried?.stop == true) log("   → 监听里要求停止任务")
        return carried
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
