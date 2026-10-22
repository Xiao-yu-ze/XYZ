package com.pvz2.auto.core

import android.util.Log
import com.pvz2.auto.zjs.Z
import com.pvz2.auto.zjs.ZExecutor
import com.pvz2.auto.zjs.ZPosResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「预览查找结果」—— 在实时画面上持续跑一次识别，把命中框画出来。
 *
 * ## 为什么是"持续跑"而不是"跑一次"
 *
 * 用户调的是**阈值和范围**，而这两样每一改都要重新看一眼结果。
 * 只跑一次的话，他改完还得再点一次按钮；自动精灵 的做法是 300ms
 * 轮询一次（`docs/zdjl-capture-and-roi.md` 里那段 `setInterval(w, 300)`）。
 *
 * 我们这里跟着做，但**只在结果变化时才更新界面** ——
 * 每 300ms 重绘一次整个覆盖层，在低端机上能看出闪。
 *
 * ## 为什么匹配在后台线程
 *
 * 一次模板匹配要几十毫秒（OpenCV 在当前 1280×720 帧上跑）。放主线程
 * 会卡住预览，而用户正盯着预览判断匹配对不对。
 */
object PreviewResults {

    private const val TAG = "PreviewResults"

    /** 轮询间隔。比 30fps 慢，因为调参不需要每帧都看。 */
    private const val INTERVAL_MS = 350L

    /** 一次查找的结果。 */
    data class Outcome(
        /** 搜索范围（虚拟屏坐标），null = 整屏。 */
        val searchRect: IntArray?,
        val boxes: List<com.pvz2.auto.ui.ResultsOverlay.Box>,
        /** 给界面显示的一句话（比如"没找到"的原因）。 */
        val message: String
    ) {
        // IntArray 在 data class 里不按内容比较，得手写
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Outcome) return false
            if (message != other.message) return false
            if (!searchRect.contentEquals(other.searchRect)) return false
            return boxes == other.boxes
        }

        override fun hashCode(): Int {
            var r = message.hashCode()
            r = 31 * r + (searchRect?.contentHashCode() ?: 0)
            r = 31 * r + boxes.hashCode()
            return r
        }
    }

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _outcome = MutableStateFlow<Outcome?>(null)
    val outcome: StateFlow<Outcome?> = _outcome.asStateFlow()

    private var job: Job? = null

    /**
     * 开始预览某个位置的识别结果。
     *
     * @param scope 宿主作用域（页面销毁时会一起取消）
     * @param pos   要预览的位置（含图片/文字/颜色/范围/索引）
     * @param screen 当前屏幕参数
     * @param matcher 匹配器（真机用 [com.pvz2.auto.zjs.ZRealMatcher]）
     * @param frame 取当前帧
     */
    fun start(
        scope: CoroutineScope,
        pos: Z.PosData,
        screen: ZPosResolver.Screen,
        matcher: ZExecutor.Matcher,
        frame: () -> Any?
    ) {
        stop()
        _active.value = true
        _outcome.value = null

        job = scope.launch {
            var last: Outcome? = null
            while (isActive) {
                val o = runCatching { probe(pos, screen, matcher, frame) }
                    .onFailure { Log.w(TAG, "预览匹配失败", it) }
                    .getOrNull()

                // 只在变化时推给界面，避免每 350ms 重绘造成的闪
                if (o != null && o != last) {
                    // 也写一行日志：界面上那句话会被下一次刷新覆盖掉，
                    // 而"到底匹配上没有、最高多少分"是排查时最想回看的。
                    Log.i(TAG, "预览：${o.message}（命中 ${o.boxes.size} 处）")
                    last = o
                    _outcome.value = o
                }
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _active.value = false
        _outcome.value = null
    }

    /** 跑一次匹配，转成界面能画的东西。 */
    private fun probe(
        pos: Z.PosData,
        screen: ZPosResolver.Screen,
        matcher: ZExecutor.Matcher,
        frame: () -> Any?
    ): Outcome {
        val rect = ZPosResolver.searchRect(pos, screen)

        // 拿不到帧时说清楚 —— "没找到"和"没画面"是两回事，
        // 混成一句会让用户去调阈值，而问题在虚拟屏没起来。
        if (frame() == null) {
            return Outcome(rect, emptyList(), "虚拟屏没在运行：先回第 1 页点「在虚拟屏启动游戏」")
        }

        val hits = when (pos.type) {
            Z.PosType.IMAGE -> matcher.findImage(pos, rect)
            Z.PosType.TEXT -> matcher.findText(pos, rect)
            Z.PosType.COLOR -> matcher.findColor(pos, rect)
            Z.PosType.NODE -> matcher.findNode(pos, rect)
            else -> emptyList()
        }

        if (pos.type !in setOf(
                Z.PosType.IMAGE, Z.PosType.TEXT,
                Z.PosType.COLOR, Z.PosType.NODE
            )
        ) {
            return Outcome(rect, emptyList(), "这个位置类型不需要查找（用的是固定坐标）")
        }

        if (hits.isEmpty()) {
            // 把"最高到多少"报出来。只说"没找到"，用户就只能盲降阈值；
            // 而 87% 和 12% 是两个完全不同的问题（阈值太高 vs 模板压根不对）。
            val real = matcher as? com.pvz2.auto.zjs.ZRealMatcher
            val best = real?.lastScore ?: 0.0
            val pct = (best * 100).toInt()
            val why = real?.lastUnsupported
            return Outcome(
                rect, emptyList(),
                when {
                    why != null -> why
                    best > 0.0 -> "没找到（最高相似度 $pct%，降到 $pct 以下再试）"
                    else -> "没找到（可以试试扩大范围或换模板）"
                }
            )
        }

        // 模板大小用来画框；没有就跟一个固定小方框，至少能看出命中了
        val w = pos.imageData?.width ?: 40
        val h = pos.imageData?.height ?: 40
        val boxes = hits.take(20).mapIndexed { i, hit ->
            com.pvz2.auto.ui.ResultsOverlay.Box(
                left = hit.x - w / 2,
                top = hit.y - h / 2,
                right = hit.x + w / 2,
                bottom = hit.y + h / 2,
                label = if (hits.size == 1) "命中" else "#${i + 1}"
            )
        }
        val msg = if (hits.size == 1) "找到 1 处" else "找到 ${hits.size} 处"
        return Outcome(rect, boxes, msg)
    }
}
