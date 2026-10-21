package com.pvz2.auto.core

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * OpenCV 加载守卫。
 *
 * ## 为什么需要它
 *
 * `OpenCVLoader.initLocal()` 原本只在两条路径里调用：启动自动化、跑识别自检。
 * 而**采集帧**是第三条路径 —— 它一上来就 `Mat(h, w, CvType.CV_8UC4)` 建缓冲，
 * 和那两条路径毫无关系。
 *
 * 于是"只建虚拟屏、还没启动自动化"这个状态下，每帧都抛：
 *
 * ```
 * UnsatisfiedLinkError: No implementation found for
 *   org.opencv.core.Mat.n_Mat(int, int, int)
 * ```
 *
 * 现象是游戏在虚拟屏上跑、声音都听得见，但**预览永远黑屏** ——
 * 因为每一帧都在建缓冲那一步就失败了，而异常被 catch 成"读帧失败"打在 W 级别日志里，
 * 界面上看不到任何原因。
 *
 * 这和之前注入器那次是同一类错误：一条路径假设"别处已经初始化过了"。
 * 初始化这种全局前置，应该由**用到它的地方自己保证**，而不是依赖调用顺序。
 *
 * ## 为什么是懒加载 + 只做一次
 *
 * `initLocal()` 会 dlopen 一堆 .so，重复调用没必要。
 * 结果缓存起来，包括失败 —— 失败再重试一般也不会成功，反而每帧刷日志。
 */
object OpenCv {

    private const val TAG = "OpenCv"

    @Volatile
    private var ready: Boolean? = null

    /**
     * 确保 OpenCV 已加载。@return 是否可用。
     *
     * 线程安全：可能被采集线程和识别线程同时调用，用双重检查锁。
     */
    fun ensureLoaded(): Boolean {
        ready?.let { return it }
        synchronized(this) {
            ready?.let { return it }
            val ok = runCatching { OpenCVLoader.initLocal() }
                .onFailure { Log.e(TAG, "OpenCV 加载抛异常", it) }
                .getOrDefault(false)
            if (ok) {
                Log.i(TAG, "OpenCV 已加载")
            } else {
                Log.e(TAG, "OpenCV 加载失败 —— 采集和识别都无法工作")
            }
            ready = ok
            return ok
        }
    }
}
