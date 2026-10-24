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

    /** 注入接口。真机实现走 Shizuku，测试实现记录调用。 */
    interface Injector {
        fun tap(x: Int, y: Int, holdMs: Int)
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int)
        fun key(keyCode: Int, holdMs: Int)
        fun inputText(text: String)

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

        // jumpId → 下标，跳转时用
        val jumpTable = HashMap<String, Int>()
        actions.forEachIndexed { i, a ->
            a.common.jumpId?.takeIf { it.isNotBlank() }?.let { jumpTable[it] = i }
        }
        // resolveJump 要用这张表（同一个对象，后面不会再被替换）
        jumpTableOf = jumpTable

        val scriptRepeat = script.baseInfo.repeatCount ?: 1
        // 脚本级默认等待：动作没写 delay 时按它走（见 scriptBaseDelay 的注释）
        scriptBaseDelay = script.baseInfo.delay?.takeIf { it.isNotBlank() }
        scriptBaseDelayUnit = script.baseInfo.delayUnit
        var loop = 0

        while (!stopped) {
            // 0 = 无限
            if (scriptRepeat != 0 && loop >= scriptRepeat) break
            loop++

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
                val action = varsApplied.action

                // 禁用：跳过，不算失败
                if (action.common.disabled) {
                    skipped.add(Result.Skip(pc, label, "已禁用"))
                    pc++
                    continue
                }

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

                val cond = action.common.condition
                val mustCheck = cond != null && cond.disabled.not()
                var delayDone = false
                if (mustCheck && !cond!!.checkBeforeDelay) {
                    val pre = preDelay(action)
                    waitDelay(pre.first, pre.second, delayDefaultMs(action))
                    delayDone = true
                }
                if (mustCheck && !evaluateWithRetry(cond!!, pc, skipped)) {
                    /*
                     * ★ 条件（本质就是节点的识别）不成立 → 任务结束，不是跳过。
                     *
                     *   颜色/图片/文字条件跑完了重试次数还是没出现，说明这个节点
                     *   进不去。Maa 里这时任务就停在那里，不会"跳过这一条继续下一条"。
                     *   我们原来是跳过 —— 于是整条链在一瞬间被冲完，
                     *   界面显示"跑完 N 条"，实际一步没做（实测 3~4 秒）。
                     *
                     *   报错要能定位：第几条、找的是什么、为什么没找到。
                     */
                    val why = matcher.lastReason() ?: "条件不成立"
                    val msg = "第 ${pc + 1} 条「$label」条件不满足：$why"
                    skipped.add(Result.Skip(pc, label, msg))
                    /*
                     * 分成两种：
                     *
                     *   识别类条件（颜色/图片/文字）不成立 → 这个节点进不去，
                     *   任务结束。这是 Maa 的行为，也是用户明确要求的。
                     *
                     *   我们还没实现的条件类型（jsExpression / conditionSet …）
                     *   不成立是**我们的能力缺口**，不是脚本的问题 ——
                     *   那种情况只跳过这一条并明确警告，不该把整个任务杀掉。
                     */
                    if (isRecognitionCondition(cond!!)) {
                        log("[任务结束] $msg")
                        return Result(executed, skipped, msg)
                    }
                    log("[跳过] $msg（这个条件类型还没实现，不据此终止任务）")
                    pc++
                    continue
                }
                if (!delayDone) {
                    val pre = preDelay(action)
                    waitDelay(pre.first, pre.second, delayDefaultMs(action))
                }

                // 执行（含重复）
                abortReason = null
                val outcome = executeWithRepeat(action, pc, label, skipped)
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
                            continue
                        }

                        is JumpResult.Unsupported -> {
                            skipped.add(Result.Skip(pc, label, resolved.message))
                            log("[跳转] ${resolved.message}，停在这一条")
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
                            return Result(executed, skipped, resolved.message)
                        }
                    }
                }
                if (outcome.stop) {
                    return Result(executed, skipped, null)
                }
                executed++
                pc++
            }
        }

        return Result(executed, skipped, null)
    }

    // ------------------------------------------------------------ 单个动作

    private data class Outcome(
        val jumpTo: String? = null,
        val stop: Boolean = false,
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
    private fun executeWithRepeat(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome {
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
                    if (r.jumpTo != null || r.stop) return r
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
        val c = action.common
        // duration 支持区间 "100-300"（FIELDS.md:112），取区间内随机；
        // 以前直接 toIntOrNull()，写区间就变 null → 静默掉回默认值。
        val hold = randomIn(c.duration)?.toInt() ?: when (action.type) {
            Z.ActionType.SWIPE -> 1000
            else -> 100
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
                tapHits(hits, hold, c)
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
                    if (i > 0) waitMultiGap(c)
                    val a = starts.getOrNull(i) ?: lastStart
                    val b = ends.getOrNull(i) ?: lastEnd
                    log("   滑动 (${a.x},${a.y})→(${b.x},${b.y}) 用时 ${hold}ms")
                    inject.swipe(a.x, a.y, b.x, b.y, hold)
                }
                Outcome()
            }

            // ---- 多指滑动
            Z.ActionType.MULTI_SWIPE -> {
                if (action.fingers.isEmpty()) {
                    skipped.add(Result.Skip(index, label, "没有手指"))
                    return null
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

            Z.ActionType.CONTROL_STOP -> Outcome(stop = true)

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
                        log(
                            "[等待候选] ${label}：已等 ${waited}s，第 $round 轮，" +
                                "${action.candidates.size} 个候选都没出现" +
                                (if (timeout > 0) "（上限 ${timeout}ms）" else "（不设上限）")
                        )
                    }
                    if (timeout > 0 && System.currentTimeMillis() - startedAt >= timeout) {
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

            // pause / setJumpId / runAimAction 安卓上先不做
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
                    log("[动作 $index] $label 还在等（第 $round 轮，已 ${secs}s，不设上限）")
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
                "子动作列表跳转「$t」尚未实现（需要先支持「运行多个动作」）"
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

        val preMs = toMs(num(a.common.delay), a.common.delayUnit)
        val durMs = num(a.common.duration).takeIf { it > 0 }
            ?: if (a.type == Z.ActionType.SWIPE) 1000 else 100
        val repeatGapMs = toMs(num(a.common.repeatDelayValue), a.common.repeatDelayUnit)
        val reps = (a.common.repeatCount ?: 1).let { if (it <= 0) 1 else it }
        // 候选等待的时长取决于画面什么时候出现，估不出来
        if (a.type == Z.ActionType.WAIT_CANDIDATES) return 0
        return (preMs + reps * (durMs + repeatGapMs)).coerceIn(1, 600_000)
    }

    /** 按位置类型找一遍。抽出来是因为重试要重复调用它。 */
    private fun searchPos(pos: Z.PosData, rect: IntArray): List<ZPosResolver.Hit> =
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
        skipped: MutableList<Result.Skip>
    ): Boolean {
        val raw = when (cond.type) {
            Z.ConditionType.IMAGE, Z.ConditionType.TEXT,
            Z.ConditionType.COLOR, Z.ConditionType.NODE -> {
                val pos = cond.posData ?: return applyInvert(cond, false)
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
        if (!scriptDefault.isNullOrBlank()) return scriptDefault to scriptBaseDelayUnit
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
    private fun tapHits(hits: List<ZPosResolver.Hit>, hold: Int, c: Z.ActionCommon) {
        hits.forEachIndexed { i, hit ->
            if (stopped) return
            if (i > 0) waitMultiGap(c)
            log("   点击 (${hit.x},${hit.y}) 按住 ${hold}ms")
            inject.tap(hit.x, hit.y, hold)
        }
    }

    /**
     * 多点动作里"第 2 个点之前"的间隔。
     *
     * 同一条动作选出的多个点，间隔按**重复间隔**走（`repeatDelayValue`，
     * 缺省回落 `delay`，FIELDS.md:116）。一口气连发在游戏侧常被合并成一次，
     * 于是"点 3 个"实际只生效 1 个 —— 又是一次漏点。
     */
    private fun waitMultiGap(c: Z.ActionCommon) {
        val hasOwn = !c.repeatDelayValue.isNullOrBlank()
        val v = if (hasOwn) c.repeatDelayValue else c.delay
        val u = if (hasOwn) c.repeatDelayUnit else c.delayUnit
        if (v.isNullOrBlank()) return
        waitDelay(v, u, 0)
    }

    /**
     * 会读 `__vars` 的字段。
     *
     * 只列真正参与执行的：这几个字段被变量化后，一旦按"没写"处理就会改变脚本行为。
     * 其余字段（desc、limitArea…）变量化不代换也不影响执行，不要因此把动作跳掉。
     */
    private val WATCHED_VAR_FIELDS = listOf("delay", "duration", "repeatCount", "posData")

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
     * 7 个槽位里我们只跑 afterExecSuc / afterExecFinish（见 [runCallbacks]）。
     * 不提示的话，用户看到"动作成功了"，却没发现挂在上面的收尾动作一条都没执行。
     */
    private fun warnUnsupportedCallbacks(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ) {
        if (action.callbacks.isEmpty()) return
        val unsupported = action.callbacks.keys.filter {
            it != Z.CallbackSlot.AFTER_EXEC_SUC && it != Z.CallbackSlot.AFTER_EXEC_FINISH
        }
        if (unsupported.isEmpty()) return
        // 无限循环里每轮都会走到这里，只报第一次
        if (!warnedCallbackActions.add(index)) return
        val names = unsupported.joinToString("、") { it.wire }
        val msg = "监听动作未执行（本机只支持 afterExecSuc / afterExecFinish）：$names"
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
        val times = (cb.common.repeatCount ?: 1).let { if (it <= 0) 1 else it }
        val cbSkips = ArrayList<Result.Skip>()
        // 缓存放在循环外：槽内动作自己也可能是"连点同一个位置"的快速重复
        val cbMemo = RepeatMemo()
        for (n in 0 until times) {
            if (stopped) break
            if (n > 0) waitDelay(cb.common.repeatDelayValue, cb.common.repeatDelayUnit)
            val r = executeOnce(cb, index, cbLabel, cbSkips, cbMemo)
            if (r == null) break
            if (r.jumpTo != null || r.stop) {
                log("[$index] $cbLabel 里的跳转/停止被忽略（作用域是外层脚本）")
                break
            }
        }
        // 槽内动作的失败原因不能吞掉：这是用户唯一能看到"监听做了什么"的地方
        cbSkips.forEach { log("[$index] $cbLabel 跳过：${it.reason}") }
        log("[$index] $label 监听 ${slot.wire} 已执行（$times 次）")
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
