package com.pvz2.auto.service

/**
 * 实时帧率计算。
 *
 * ## 为什么单独抽出来
 *
 * 帧率是判断"采集还活着吗"的唯一实时指标 —— 之前的"累计帧数"只增不减，
 * 画面卡死了数字照样在涨，等于没用。而帧率算法本身有几个容易写错的边界
 * （刚启动时窗口没攒满、低帧率时整数截断、暂停后残留旧时间戳），
 * 抽成纯函数才能用单测把边界钉死，不必上真机去数。
 *
 * ## 算法
 *
 * 维护一个滑动窗口：只保留最近 [WINDOW_MS] 内的帧时间戳，于是
 *
 *     fps = (窗口内帧数 - 1) * 1000 / (最后一帧时刻 - 第一帧时刻)
 *
 * 用「减 1 除以跨度」而不是「帧数除以固定 1 秒」：前者统计的是**真实间隔**，
 * 设备帧率抖动时读数不会跳变；后者在窗口刚好差一帧时会在两个整数之间来回跳。
 */
object Fps {

    /** 统计窗口：过去 1 秒。 */
    const val WINDOW_MS = 1000L

    /** 帧率上限。同时是采集节流目标。 */
    const val MAX_FPS = 60

    /**
     * 窗口跨度小于这个值时不做折算。
     *
     * 刚启动时窗口里可能只有两三帧，跨度 50ms —— 按公式折算会得到
     * "40fps" 这种不可靠的读数（样本太少，一帧抖动就翻倍）。
     * 这种情况下直接报窗口里的帧数，等攒够样本再切到折算值。
     */
    const val MIN_SPAN_MS = 300L

    /**
     * 由帧时间戳序列算出实时帧率。
     *
     * @param times 帧时间戳（单调递增，单位毫秒）。调用方负责把超窗的移除。
     * @param now 当前时刻，用于判断窗口跨度
     * @return 帧率，钳制在 `0..MAX_FPS`
     */
    fun compute(times: Collection<Long>, now: Long): Int {
        if (times.isEmpty()) return 0

        val first = times.first()
        val last = times.last()
        val span = last - first

        // 样本太少：直接报帧数，不做折算（见 MIN_SPAN_MS 的说明）。
        if (span < MIN_SPAN_MS) {
            return times.size.coerceIn(0, MAX_FPS)
        }

        // 两帧之间的间隔数 = 帧数 - 1，除以跨度得到每秒帧数。
        val fps = (times.size - 1) * 1000L / span
        return fps.toInt().coerceIn(0, MAX_FPS)
    }
}
