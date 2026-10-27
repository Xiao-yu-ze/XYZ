package com.pvz2.auto.zjs

/**
 * 「种植顺序」的三条链：单次 / 循环 / 收尾。
 *
 * ## 为什么要有链这个概念
 *
 * 无尽模式的实际打法是三段式的：
 *  1. **单次**：开局把固定阵型摆好（种 8 个槽、喂豆、铲掉初始植物）；
 *  2. **循环**：之后每一波反复做同一组动作（补植物、喂豆、丢神器）；
 *  3. **收尾**：最后一波/结算前的固定动作。
 *
 * 平铺的动作列表表达得了这三段，但用户看不出"哪几条属于循环"，
 * 想改循环就得数节点。所以给每个游戏动作打一个 `pvzChain` 标记，
 * 编辑器按标记分成三栏，改完再按链顺序写回平铺列表 —— **运行格式不变**，
 * 执行器完全不用改。
 *
 * ## 循环次数放哪
 *
 * 不放新字段：循环链会被包进一个「运行多个动作」，用它的 `repeatCount`
 * （0 = 无限）。这样老的执行器/老的脚本双向兼容。
 */
object PvzChainModel {

    const val ONCE = "once"
    const val LOOP = "loop"
    const val END = "end"

    /** 链的中文名（顺序即展示/编译顺序）。 */
    val LABELS: List<Pair<String, String>> = listOf(
        ONCE to "单次",
        LOOP to "循环",
        END to "收尾"
    )

    fun label(key: String?): String =
        LABELS.firstOrNull { it.first == (key ?: ONCE) }?.second ?: "单次"

    /** 某个动作属于哪条链（缺省单次）。 */
    fun chainOf(action: Z.Action): String {
        val v = action.extra["pvzChain"]?.toString()?.trim().orEmpty()
        return if (v in setOf(ONCE, LOOP, END)) v else ONCE
    }

    /** 是不是我们这五种游戏动作之一。 */
    fun isPvz(action: Z.Action): Boolean = action.type in PVZ_TYPES

    val PVZ_TYPES: Set<Z.ActionType> = setOf(
        Z.ActionType.PVZ_PLANT, Z.ActionType.PVZ_FEED, Z.ActionType.PVZ_SHOVEL,
        Z.ActionType.PVZ_TAP_CELL, Z.ActionType.PVZ_ARTIFACT,
        Z.ActionType.PVZ_WAVE, Z.ActionType.PVZ_SPEED, Z.ActionType.PVZ_SWITCH_DECK,
        Z.ActionType.PVZ_SUPPLY, Z.ActionType.PVZ_START_BATTLE,
        Z.ActionType.PVZ_ENTER_CHECK, Z.ActionType.PVZ_SETTLE,
        Z.ActionType.PVZ_SWITCH_FORM
    )

    /** 把脚本里的游戏动作按链分组（保持原相对顺序）。 */
    fun group(actions: List<Z.Action>): Map<String, List<Z.Action>> {
        val out = LinkedHashMap<String, MutableList<Z.Action>>()
        for (l in LABELS) out[l.first] = ArrayList()
        for (a in actions) {
            if (!isPvz(a)) continue
            out.getOrPut(chainOf(a)) { ArrayList() }.add(a)
        }
        return out
    }

    /**
     * 按链重建节点列表。
     *
     * 规则：
     *  - 原有非游戏动作**原样保留**，顺序不变；
     *  - 全部游戏动作被抽出来，按「单次 → 循环（包一个运行多个动作）→ 收尾」
     *    重新插回**第一个原游戏动作的位置**（没有就追加到末尾）；
     *  - 循环链为空时不生成那个容器节点。
     *
     * 返回值里的 `loopRepeat` 从原来的容器上沿用（缺省 0 = 无限）。
     */
    fun rebuild(
        actions: List<Z.Action>,
        chains: Map<String, List<Z.Action>>,
        loopRepeat: Int = 0
    ): List<Z.Action> {
        val firstPvzIdx = actions.indexOfFirst { isPvz(it) }
        val kept = actions.filterNot { isPvz(it) }.toMutableList()
        val insertAt = if (firstPvzIdx < 0) kept.size else {
            // 数一下第一个游戏动作前面有几个"非游戏动作"
            actions.take(firstPvzIdx).count { !isPvz(it) }
        }

        val block = ArrayList<Z.Action>()
        chains[ONCE].orEmpty().forEach { block.add(it.withChain(ONCE)) }
        val loopItems = chains[LOOP].orEmpty()
        if (loopItems.isNotEmpty()) {
            block.add(
                Z.Action(
                    type = Z.ActionType.RUN_MULTI,
                    scriptSet = loopItems.map { it.withChain(LOOP) },
                    common = Z.ActionCommon(repeatCount = loopRepeat)
                )
            )
        }
        chains[END].orEmpty().forEach { block.add(it.withChain(END)) }

        kept.addAll(insertAt.coerceIn(0, kept.size), block)
        return kept
    }

    /** 从脚本里找回循环链的重复次数（那个容器节点的 repeatCount）。 */
    fun loopRepeatOf(actions: List<Z.Action>): Int =
        actions.firstOrNull { it.type == Z.ActionType.RUN_MULTI }
            ?.common?.repeatCount ?: 0

    private fun Z.Action.withChain(chain: String): Z.Action =
        copy(extra = extra.toMutableMap().apply { put("pvzChain", chain) })
}
