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
    private var stopped = false

    fun stop() {
        stopped = true
    }

    /**
     * 跑完整个脚本。
     *
     * @param maxSteps 步数上限，防止 `重复次数=0` + 无条件跳转变成死循环
     *        （那种脚本会一直转，用户只能强杀应用）
     */
    fun run(script: Z.Script, maxSteps: Int = 100_000): Result {
        val actions = script.actions
        if (actions.isEmpty()) return Result(0, emptyList(), "脚本里没有动作")

        stopped = false
        val skipped = ArrayList<Result.Skip>()
        var executed = 0
        var steps = 0

        // jumpId → 下标，跳转时用
        val jumpTable = HashMap<String, Int>()
        actions.forEachIndexed { i, a ->
            a.common.jumpId?.takeIf { it.isNotBlank() }?.let { jumpTable[it] = i }
        }

        val scriptRepeat = script.baseInfo.repeatCount ?: 1
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

                val action = actions[pc]
                val label = ZScriptIO.actionLabel(action)

                // 禁用：跳过，不算失败
                if (action.common.disabled) {
                    skipped.add(Result.Skip(pc, label, "已禁用"))
                    pc++
                    continue
                }

                // 条件：不满足就跳过
                val cond = action.common.condition
                if (cond != null && cond.disabled.not() && !evaluate(cond, pc, skipped)) {
                    skipped.add(Result.Skip(pc, label, "条件不满足"))
                    pc++
                    continue
                }

                // 上报"现在跑到哪一条"，界面据此在这一条上画进度条。
                // 放在等待之前：用户要看的是"这一条正在跑"，
                // 而不是等完之后才出现。
                com.pvz2.auto.core.ScriptRunState.enterAction(pc, label, estimateMs(action))

                // 前置等待
                waitDelay(action.common.delay, action.common.delayUnit)

                // 执行（含重复）
                val outcome = executeWithRepeat(action, pc, label, skipped)
                com.pvz2.auto.core.ScriptRunState.finishAction(pc)
                if (outcome.jumpTo != null) {
                    // 跳转：跳到目标；找不到就当脚本结束
                    val target = jumpTable[outcome.jumpTo]
                    if (target == null) {
                        skipped.add(Result.Skip(pc, label, "跳转目标「${outcome.jumpTo}」不存在"))
                        pc = actions.size
                    } else {
                        pc = target
                    }
                    continue
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

    private data class Outcome(val jumpTo: String? = null, val stop: Boolean = false)

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
        if (repeat == 0) {
            // 无限重复：只有在能跳转时才合理，否则会卡死在这里
            log("[$index] $label 重复次数为 0（无限），按 1 次执行以免卡死")
        }
        val times = if (repeat <= 0) 1 else repeat

        for (n in 0 until times) {
            if (stopped) break
            if (n > 0) waitDelay(action.common.repeatDelayValue, action.common.repeatDelayUnit)

            when (val r = executeOnce(action, index, label, skipped)) {
                null -> return Outcome()       // 执行不了，已经记过原因
                else -> if (r.jumpTo != null || r.stop) return r
            }
        }
        return Outcome()
    }

    /** 执行一次。返回 null 表示这个动作做不了（已记录原因）。 */
    private fun executeOnce(
        action: Z.Action,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>
    ): Outcome? {
        val c = action.common
        val hold = c.duration?.toIntOrNull() ?: when (action.type) {
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
                // 等待参数来自动作本身（转换出来的脚本会带上 Maa 的 timeout）
                val hit = locate(
                    pos, index, label, skipped,
                    timeoutMs = action.waitTimeoutMs ?: 0,
                    intervalMs = action.waitIntervalMs ?: 200
                ) ?: return null
                inject.tap(hit.x, hit.y, hold)
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
                val a = ZPosResolver.resolveLocation(s, screen)
                val b = ZPosResolver.resolveLocation(e, screen)
                inject.swipe(a.x, a.y, b.x, b.y, hold)
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
                    paths.add(
                        ZPosResolver.resolveLocation(s, screen) to
                            ZPosResolver.resolveLocation(e, screen)
                    )
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
     * 按位置对象的类型找命中点。
     *
     * 找到多个时按 `indexNum` 选一个 —— 这是协议里"选第几个"的语义。
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
     */
    private fun locate(
        pos: Z.PosData,
        index: Int,
        label: String,
        skipped: MutableList<Result.Skip>,
        timeoutMs: Int = 0,
        intervalMs: Int = 200
    ): ZPosResolver.Hit? {
        if (pos.type == Z.PosType.LOCATION) {
            return ZPosResolver.resolveLocation(pos, screen)
        }

        val rect = ZPosResolver.searchRect(pos, screen)
        var hits = searchPos(pos, rect)

        // 找不到就按间隔重试，直到超时（Maa 的 timeout）
        if (hits.isEmpty() && timeoutMs > 0 && !stopped) {
            val deadline = System.currentTimeMillis() + timeoutMs
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
            }
            if (hits.isNotEmpty()) log("[动作 $index] $label — 等了 $round 轮后找到")
        }

        if (hits.isEmpty()) {
            val why = matcher.lastReason()
            val prefix = if (timeoutMs > 0) "等了 ${timeoutMs}ms 仍" else ""
            val msg = "${prefix}没有匹配到目标" + (why?.let { "：$it" } ?: "")
            skipped.add(Result.Skip(index, label, msg))
            log("[动作 $index] $label — $msg")
            return null
        }

        val which = ZPosResolver.pickIndex(pos.indexNum, hits.size)
        val hit = hits.getOrNull(which) ?: hits.first()

        // 命中后的偏移
        val ox = pos.xOffset?.toPx(screen.width, screen.density) ?: 0f
        val oy = pos.yOffset?.toPx(screen.height, screen.density) ?: 0f
        return ZPosResolver.Hit(
            x = (hit.x + ox).toInt().coerceIn(0, screen.width - 1),
            y = (hit.y + oy).toInt().coerceIn(0, screen.height - 1)
        )
    }

    /**
     * 估一条动作要花多久（毫秒），只用来画进度条。
     *
     * 宁可估粗也不要估 0：估 0 的话进度条永远是满的或不动，
     * 用户看不出"这一条正在跑"还是"卡住了"。
     */
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
                hits.isNotEmpty()
            }

            Z.ConditionType.RANDOM -> {
                val p = (cond.extra["percent"] as? Number)?.toInt() ?: 50
                (0 until 100).random() < p
            }

            // 其余条件安卓上暂时判不了 → 按不成立
            else -> {
                log("条件 ${cond.type.wire} 暂不支持，按不成立处理")
                false
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
    private fun waitDelay(value: String?, unit: Z.TimeUnit) {
        val n = randomIn(value) ?: return
        val total = when (unit) {
            Z.TimeUnit.MILLISECONDS -> n
            Z.TimeUnit.SECONDS -> n * 1000
            Z.TimeUnit.MINUTES -> n * 60_000
            Z.TimeUnit.HOURS -> n * 3_600_000
        }
        if (total > 0) {
            try {
                Thread.sleep(total.toLong())
            } catch (e: InterruptedException) {
                stopped = true
            }
        }
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
