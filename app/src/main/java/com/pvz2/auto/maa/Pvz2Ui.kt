package com.pvz2.auto.maa

import android.util.Log
import com.pvz2.auto.core.ScreenCapturer
import com.pvz2.auto.core.TapJitter
import com.pvz2.auto.core.TouchInjector
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.core.Mat
import kotlin.math.roundToInt

/**
 * ============================================================================
 * 自动化操作封装：写逻辑时只需要关心「找什么、点哪里」
 * ============================================================================
 *
 * 把「截图 → 识别 → 坐标换算 → 点击 → 释放」这一整套收进一个类，
 * 写玩法逻辑时不再需要重复处理坐标换算、资源释放、抖动、重试。
 *
 * 典型用法：
 *
 *     val ui = Pvz2Ui(capturer, injector, recognizer)
 *
 *     // 1) 找到就点，找不到就算了
 *     ui.tapIfFound("maapvz/Endless/无尽挑战.png")
 *
 *     // 2) 找到点 A，找不到点 B
 *     ui.tapEither(condition = "maapvz/Endless/yes.png",
 *                  hitX = 640, hitY = 360,
 *                  missX = 100, missY = 100)
 *
 *     // 3) 在候选里挑第一个出现的并点击
 *     ui.tapFirstFound(
 *         Candidate("maapvz/Endless/out.png",  640, 360),
 *         Candidate("maapvz/Start_game/叉叉.png", 640, 300)
 *     )
 *
 *     // 4) 等到出现为止（带超时）
 *     if (ui.waitFor("maapvz/General/暂停.png", timeoutMs = 10_000)) { ... }
 *
 *     // 5) 点固定坐标 / 滑动
 *     ui.tap(640, 360)
 *     ui.swipe(640, 600, 640, 200, 300)
 *
 * ── 设计要点 ──────────────────────────────────────────────────────────────
 *
 * * **所有对外坐标都是 720p 逻辑坐标**，物理像素换算在内部完成。
 *   换设备、换分辨率时调用方一行都不用改。
 *
 * * **每帧只抓一次**。同一轮判断里的多次识别共用一帧，既快又能保证
 *   "这一瞬间我看到的就是这样"。跨轮次会重新抓帧。
 *
 * * **协程安全**：内部用一把互斥锁串行化操作。多个协程同时调用不会
 *   互相踩到 OpenCV 的非线程安全状态。
 *
 * * **Mat 生命周期由本类负责**，调用方拿到的 [Hit] 是纯数据，用完不用管。
 */
class Pvz2Ui(
    private val capturer: ScreenCapturer,
    private val injector: TouchInjector,
    private val recognizer: Recognizer
) {

    /** 默认匹配阈值。MaaPVZ 无尽挑战用 0.8，MaaFramework 默认 0.7。 */
    var defaultThreshold: Double = 0.80

    /** 点击时叠加的随机抖动半径（像素）。设为 0 可关闭。 */
    var jitterRadius: Int = 5

    /** 串行化并发调用；OpenCV 的 Mat 状态不是线程安全的。 */
    private val lock = Mutex()

    // ======================================================================
    // 核心：一次「抓帧 + 识别」
    // ======================================================================

    /**
     * 抓一帧并识别指定模板。
     *
     * 这是所有上层方法的基础。如果你要写本类没覆盖的组合逻辑，
     * 用这个方法拿到 [Hit] 自己判断即可。
     *
     * @return 命中返回 [Hit]（坐标已是 720p 逻辑坐标），未命中返回 null
     */
    suspend fun find(
        template: String,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold,
        greenMask: Boolean = false
    ): Hit? = withFrame { frame ->
        recognizer.recognize(
            frame,
            Recognition.Template(
                templates = listOf(template),
                roi = roi,
                threshold = threshold,
                greenMask = greenMask
            )
        )
    }

    /**
     * 在同一帧里依次找多个模板，返回第一个命中的。
     *
     * 比连续调用多次 [find] 更可靠：多次 find 会各抓一帧，
     * 中间画面可能已经变了，导致"以为 A 不在、其实 A 刚才在"。
     */
    suspend fun findFirstOf(
        templates: List<String>,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold
    ): Pair<String, Hit>? = withFrame { frame ->
        for (path in templates) {
            val hit = recognizer.recognize(
                frame,
                Recognition.Template(
                    templates = listOf(path),
                    roi = roi,
                    threshold = threshold
                )
            )
            if (hit != null) return@withFrame path to hit
        }
        null
    }

    /** 用任意 [Recognition] 识别一次，给 OCR / 找色等高级用法留口子。 */
    suspend fun findWith(recognition: Recognition): Hit? =
        withFrame { frame -> recognizer.recognize(frame, recognition) }

    // ======================================================================
    // 点击
    // ======================================================================

    /** 点固定坐标（720p 逻辑坐标）。 */
    fun tap(logicalX: Int, logicalY: Int) {
        val adapter = recognizer.adapter
        if (adapter == null) {
            Log.w(TAG, "尚未抓到过帧，无法换算坐标；本次点击跳过")
            return
        }
        val point = adapter.toScreen(logicalX.toFloat(), logicalY.toFloat())
        val px = point.x.roundToInt() + if (jitterRadius > 0) TapJitter.dx() else 0
        val py = point.y.roundToInt() + if (jitterRadius > 0) TapJitter.dy() else 0
        Log.i(TAG, "点击 逻辑($logicalX,$logicalY) -> 物理($px,$py)")
        injector.tap(px.toFloat(), py.toFloat())
    }

    /** 点命中框的中心。 */
    fun tapHit(hit: Hit) = tap(hit.centerX.roundToInt(), hit.centerY.roundToInt())

    /** 识别到了就点击命中位置；返回是否命中。 */
    suspend fun tapIfFound(
        template: String,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold
    ): Boolean {
        val hit = find(template, roi, threshold) ?: return false
        tapHit(hit)
        return true
    }

    /**
     * **识别到点 A，没识别到点 B。**
     *
     * @return true 表示走了命中分支
     */
    suspend fun tapEither(
        condition: String,
        hitX: Int,
        hitY: Int,
        missX: Int,
        missY: Int,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold
    ): Boolean {
        val hit = find(condition, roi, threshold)
        if (hit != null) {
            Log.i(TAG, "命中 $condition（%.4f），点 ($hitX,$hitY)".format(hit.score))
            tap(hitX, hitY)
            return true
        }
        Log.i(TAG, "未命中 $condition，点 ($missX,$missY)")
        tap(missX, missY)
        return false
    }

    /**
     * 在候选列表里挑第一个出现的并点击它对应的坐标。
     *
     * 优先级 = 列表顺序。适合"先处理弹窗，没有再处理主界面按钮"这种场景。
     *
     * **所有候选共用同一帧**：逐个调用 [find] 会各抓一帧，中间画面可能已经变了，
     * 导致"以为 A 不在、其实 A 刚才在"。这里和 [findFirstOf] 用同一套同帧语义。
     *
     * 点击在 [withFrame] **之外**执行 —— [lock] 用的是非可重入的 `Mutex`，
     * 在持锁的 block 里再调 [tap] 会死锁。
     */
    suspend fun tapFirstFound(
        vararg candidates: Candidate,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold
    ): Candidate? {
        // 只抓一帧，在里面挑出第一个命中的候选。
        // 注意：withFrame 的返回类型是 T?，`?: return` 之后 Kotlin 无法把
        // 智能转换带进解构声明，所以这里用显式取字段而不是 `val (a, b) = ...`。
        val found: Pair<Candidate, Hit>? = withFrame { frame ->
            for (c in candidates) {
                val hit = recognizer.recognize(
                    frame,
                    Recognition.Template(
                        templates = listOf(c.template),
                        roi = c.roi ?: roi,
                        threshold = threshold
                    )
                ) ?: continue
                return@withFrame c to hit
            }
            null
        }
        val candidate = found?.first ?: return null
        val hit = found.second
        Log.i(TAG, "命中 ${candidate.template}（%.4f）".format(hit.score))
        // 给了坐标就点坐标（相对分辨率的逻辑位置更稳），
        // 没给就点识别到的实际位置。
        if (candidate.x != null && candidate.y != null) {
            tap(candidate.x, candidate.y)
        } else {
            tapHit(hit)
        }
        return candidate
    }

    /** 候选模板：[template] 必须，[x]/[y] 可选（不给则点命中位置）。 */
    data class Candidate(
        val template: String,
        val x: Int? = null,
        val y: Int? = null,
        val roi: IntArray? = null
    )

    // ======================================================================
    // 滑动
    // ======================================================================

    /**
     * 从一点滑到另一点（720p 逻辑坐标，时长毫秒）。
     *
     * 端点同样叠加 [jitterRadius] 抖动，和 [tap] 保持一致 —— 像素级完全相同的
     * 重复滑动同样容易被反自动化检测标记。
     */
    fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Long = 300) {
        val adapter = recognizer.adapter
        if (adapter == null) {
            Log.w(TAG, "尚未抓到过帧，无法换算坐标；本次滑动跳过")
            return
        }
        val a = adapter.toScreen(fromX.toFloat(), fromY.toFloat())
        val b = adapter.toScreen(toX.toFloat(), toY.toFloat())
        val jx = if (jitterRadius > 0) TapJitter.dx() else 0
        val jy = if (jitterRadius > 0) TapJitter.dy() else 0
        // roundToInt 而不是 toInt：TouchInjector 收 Float 后自己 toInt() 是**截断**，
        // 不四舍五入的话每个端点会稳定地少 1px。
        val x1 = a.x.roundToInt() + jx
        val y1 = a.y.roundToInt() + jy
        val x2 = b.x.roundToInt() + jx
        val y2 = b.y.roundToInt() + jy
        Log.i(TAG, "滑动 ($fromX,$fromY)->($toX,$toY) ${durationMs}ms 物理($x1,$y1)->($x2,$y2)")
        injector.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), durationMs)
    }

    /**
     * 长按（720p 逻辑坐标）。
     *
     * 实现就是"起点终点相同的 swipe" —— `input swipe x y x y 800` 在 Android 上
     * 就是长按。`durationMs` 一般 600~1000ms（低于 500ms 多数 ROM 会当普通点击）。
     *
     * 注意：抖动在这里会**同时**加到起点和终点，所以滑动距离保持为 0，
     * 长按不会退化成微小滑动。
     */
    fun longPress(logicalX: Int, logicalY: Int, durationMs: Long = 800) =
        swipe(logicalX, logicalY, logicalX, logicalY, durationMs)

    /** 上滑（用于列表翻页，[amount] 是逻辑像素距离）。 */
    fun swipeUp(centerX: Int, fromY: Int, amount: Int, durationMs: Long = 300) =
        swipe(centerX, fromY, centerX, fromY - amount, durationMs)

    // ======================================================================
    // 等待
    // ======================================================================

    /**
     * 等到某个模板出现，或超时。
     *
     * @return 出现返回 true，超时返回 false
     */
    suspend fun waitFor(
        template: String,
        timeoutMs: Long = 10_000,
        intervalMs: Long = 300,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold
    ): Boolean = waitForHit(template, timeoutMs, intervalMs, roi, threshold) != null

    /**
     * 同 [waitFor]，但返回命中结果。
     *
     * **节拍语义**：[intervalMs] 是"每轮识别的最低耗时"，不是叠加在识别之后。
     * 单次 [find] 要走截图（Shizuku binder 往返 + PNG 解码）+ 模板匹配，
     * 实测几十到几百 ms；如果无脑 `delay(intervalMs)`，真实周期会变成
     * `识别耗时 + intervalMs`，轮询次数远低于预期。
     *
     * **超时语义**：整体包在 [withTimeoutOrNull] 里，超时是墙钟硬上限。
     * 循环里每次都用**重新计算**的剩余时间，所以即使某次 [find] 耗时异常，
     * 也不会把总时间拖长。
     *
     * 已知边界：`capture()` 是阻塞调用（AIDL 往返），协程取消对它无效。
     * 若 Shizuku 服务端假死，[withTimeoutOrNull] 只能保证**调用方**按时返回，
     * 那个卡住的 IO 线程要等底层 socket 自己超时。要根治得在
     * `ShizukuScreenCapturer` 里给 binder 调用套一层可超时的隔离。
     */
    suspend fun waitForHit(
        template: String,
        timeoutMs: Long = 10_000,
        intervalMs: Long = 300,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold
    ): Hit? = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val startedAt = System.currentTimeMillis()
            find(template, roi, threshold)?.let { return@withTimeoutOrNull it }

            // 减去本轮已耗时，保证 intervalMs 是节拍而不是叠加。
            val need = intervalMs - (System.currentTimeMillis() - startedAt)
            if (need > 0) delay(need)
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /** 等到模板**消失**为止；常用于等过场动画结束。 */
    suspend fun waitUntilGone(
        template: String,
        timeoutMs: Long = 10_000,
        intervalMs: Long = 300,
        roi: IntArray? = null,
        threshold: Double = defaultThreshold
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val startedAt = System.currentTimeMillis()
            if (find(template, roi, threshold) == null) return@withTimeoutOrNull true

            val need = intervalMs - (System.currentTimeMillis() - startedAt)
            if (need > 0) delay(need)
        }
        @Suppress("UNREACHABLE_CODE")
        false
    } ?: false

    // ======================================================================
    // 内部：抓帧 + 自动释放
    // ======================================================================

    /**
     * 抓一帧，执行 [block]，无论成功失败都释放。
     *
     * 用 [lock] 串行化：`Recognizer` 内部有模板缓存和当前帧引用，
     * 并发调用会互相踩。
     */
    private suspend fun <T> withFrame(block: (Mat) -> T): T? = lock.withLock {
        val frame = capturer.capture() ?: run {
            Log.w(TAG, "截图失败")
            return@withLock null
        }
        try {
            // 用真实帧尺寸校准；尺寸变了会自动重建模板缓存。
            recognizer.setScreen(frame.width(), frame.height())
            block(frame)
        } finally {
            frame.release()
        }
    }

    /**
     * 抓一帧只为让 [recognizer] 建立屏幕适配器，让 [tap] / [swipe] /
     * [longPress] 在没有先做识别的情况下也能换算坐标。
     *
     * @return 拿到帧返回 true；截图失败返回 false（此时 [tap] 仍是 no-op）
     */
    suspend fun calibrate(): Boolean = withFrame { true } ?: false

    private companion object {
        const val TAG = "Pvz2Ui"
    }
}
