package com.pvz2.auto.service

import android.util.Log
import com.pvz2.auto.core.ScreenCapturer
import org.opencv.core.Mat

/**
 * 识别用的取帧器：**优先走虚拟屏帧，拿不到再回退 screencap**。
 *
 * ## 为什么要换
 *
 * 原来识别走的是 `screencap -p` → PNG → 管道 → 解码 → `bitmapToMat`：
 * 每帧要 fork 两个进程、做一次 PNG 编码、一次 PNG 解码，实测 **40~90 ms**。
 * 而虚拟屏的 `AImageReader` 每帧已经在出图了（60fps），那时延只有几毫秒。
 *
 * 更要紧的是：**Boss 放技能的窗口只有一两百毫秒**，40~90ms 的抓帧延迟
 * 意味着识别完技能已经放完了。这是"打断不了"的头号原因。
 *
 * ## 通道数
 *
 * 虚拟屏帧是 `AImageReader` 的 `RGBA_8888` → `CV_8UC4`；
 * 模板是 `BitmapFactory` → `bitmapToMat` → 也是 `CV_8UC4`。
 * `matchTemplate` 只要求 `type == _templ.type()`，所以**不需要任何颜色转换**。
 *
 * ## 回退
 *
 * 虚拟屏没建好（或刚停掉）时 `latestFrameCopy()` 返回 null，这时退回
 * `screencap`。两条路的画面内容一样，识别逻辑不用区分。
 */
class VirtualDisplayCapturer(
    private val session: VirtualDisplaySession,
    private val fallback: ScreenCapturer
) : ScreenCapturer {

    /** 上一次取帧走的哪条路。用来在日志里确认"到底有没有用上虚拟屏帧"。 */
    @Volatile
    var lastSource: String = "none"
        private set

    override fun capture(): Mat? {
        // 虚拟屏在跑就直接拿，省掉 40~90ms 的 fork + PNG + 解码。
        val fast = session.latestFrameCopy()
        if (fast != null) {
            lastSource = "vd"
            return fast
        }
        lastSource = "screencap"
        if (!warnedFallback) {
            // 只在第一次回退时提示，避免每帧刷屏。
            warnedFallback = true
            Log.w(TAG, "虚拟屏还没有帧，识别回退到 screencap（会慢 40~90ms）")
        }
        return fallback.capture()
    }

    @Volatile
    private var warnedFallback = false

    private companion object {
        const val TAG = "VirtualDisplayCapturer"
    }
}
