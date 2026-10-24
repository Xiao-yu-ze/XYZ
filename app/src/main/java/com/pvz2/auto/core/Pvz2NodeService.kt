package com.pvz2.auto.core

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 「出现节点」/「点击节点」用的无障碍服务。
 *
 * ## 为什么需要它
 *
 * 游戏跑在我们自己建的虚拟屏上（`displayId` 是动态的，真机上这次是 19）。
 * 图片/文字/颜色都能靠截帧自己算，**节点不行** —— 节点树只有无障碍框架有。
 * 之前这个服务为了 MediaProjection 降级方案被移除过，于是 `findNode` 只能
 * 老实返回空并报"拿不到节点树"。
 *
 * 实测（`dumpsys accessibility`）虚拟屏那层窗口是登记在案的：
 * ```
 * Display[19] :
 *   A11yWindow[title=植物大战僵尸2, displayId=19, type=TYPE_APPLICATION,
 *              bounds=Rect(0,0 - 1280,720), focused=true]
 * ```
 * 说明无障碍能看到它 —— 只要服务带 `flagRetrieveInteractiveWindows`。
 *
 * ## 坐标
 *
 * 那个窗口的 bounds 就是 `0,0 - 1280,720`，和虚拟屏分辨率一致，
 * 所以节点 bounds **可以直接当虚拟屏坐标用**，不需要换算。
 */
class Pvz2NodeService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
        /*
         * 把当前能看到的窗口打出来。
         *
         * 「出现节点在虚拟屏上到底能不能用」全靠这一行 ——
         * 游戏跑在自建虚拟屏（displayId 动态），如果这里列不出
         * 那个 1280x720 的窗口，就说明无障碍看不到它，节点位置对本游戏无效。
         */
        runCatching {
            val ws = windows
            Log.i(TAG, "可见窗口 ${ws.size} 个：")
            for ((i, w) in ws.withIndex()) {
                val r = Rect()
                w.getBoundsInScreen(r)
                val root = w.root
                Log.i(
                    TAG,
                    "  窗口$i type=${w.type} bounds=${r.toShortString()} " +
                        "包名=${root?.packageName} 子节点=${root?.childCount ?: -1}"
                )
            }
        }.onFailure { Log.w(TAG, "列举窗口失败：${it.message}", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不用事件驱动：脚本执行到那一步时主动来查（见 find）
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 一次节点命中的结果（坐标是虚拟屏坐标）。 */
    data class NodeHit(
        val text: String,
        val className: String,
        val idResName: String,
        val packageName: String,
        val desc: String,
        val depth: Int,
        val bounds: Rect,
        val clickable: Boolean
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
    }

    companion object {
        private const val TAG = "Pvz2Node"

        @Volatile
        private var instance: Pvz2NodeService? = null

        fun isReady(): Boolean = instance != null

        /**
         * 在所有窗口里找节点。
         *
         * @param text 要匹配的文字（null/空 = 不看文字）
         * @param textIsRegExp text 按正则匹配
         * @param className 类名（null = 不看）
         * @param idResName view id（null = 不看）
         * @param packageName 包名（null = 不看）
         * @param desc contentDescription（null = 不看）
         * @param minDepth 最小深度（0 = 不限）
         * @param matchContains 文字用"包含"还是"相等"
         * @param rect 只在这个区域里找（虚拟屏坐标；null = 全屏）
         */
        fun find(
            text: String?,
            textIsRegExp: Boolean = false,
            className: String? = null,
            idResName: String? = null,
            packageName: String? = null,
            desc: String? = null,
            minDepth: Int = 0,
            matchContains: Boolean = true,
            rect: Rect? = null,
            limit: Int = 200
        ): List<NodeHit> {
            val svc = instance ?: return emptyList()
            val out = ArrayList<NodeHit>()
            val regex = if (textIsRegExp && !text.isNullOrBlank()) {
                runCatching { Regex(text) }.getOrNull()
            } else {
                null
            }

            fun matchesText(node: AccessibilityNodeInfo): Boolean {
                if (text.isNullOrBlank()) return true
                val t = node.text?.toString() ?: ""
                if (t.isEmpty()) return false
                return when {
                    regex != null -> regex.containsMatchIn(t)
                    matchContains -> t.contains(text)
                    else -> t == text
                }
            }

            fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || out.size >= limit) return
                val b = Rect()
                node.getBoundsInScreen(b)
                val inRect = rect == null || Rect.intersects(rect, b)
                if (inRect && matchesText(node) &&
                    (className.isNullOrBlank() || node.className?.toString() == className) &&
                    (idResName.isNullOrBlank() || node.viewIdResourceName == idResName) &&
                    (packageName.isNullOrBlank() || node.packageName?.toString() == packageName) &&
                    (desc.isNullOrBlank() || node.contentDescription?.toString() == desc) &&
                    depth >= minDepth
                ) {
                    out.add(
                        NodeHit(
                            text = node.text?.toString().orEmpty(),
                            className = node.className?.toString().orEmpty(),
                            idResName = node.viewIdResourceName.orEmpty(),
                            packageName = node.packageName?.toString().orEmpty(),
                            desc = node.contentDescription?.toString().orEmpty(),
                            depth = depth,
                            bounds = b,
                            clickable = node.isClickable
                        )
                    )
                }
                for (i in 0 until node.childCount) {
                    // getChild 返回的实例不能复用后直接回收，交给 GC（childCount 通常很小）
                    walk(node.getChild(i), depth + 1)
                }
            }

            try {
                for (w in svc.windows) {
                    val root = w.root ?: continue
                    walk(root, 0)
                    if (out.size >= limit) break
                }
                Log.i(TAG, "查节点：命中 ${out.size} 个（窗口 ${svc.windows.size} 个）")
            } catch (e: Throwable) {
                Log.w(TAG, "查节点失败：${e.message}", e)
            }
            return out
        }

        /** 把当前所有窗口摘要写进日志（排查"为什么找不到"用）。 */
        fun dumpWindows(): String {
            val svc = instance ?: return "无障碍服务未连接"
            val sb = StringBuilder()
            for ((i, w) in svc.windows.withIndex()) {
                val r = Rect()
                w.getBoundsInScreen(r)
                sb.append("窗口$i type=${w.type} bounds=${r.toShortString()} 节点数=${w.root?.childCount ?: -1}")
                sb.append('\n')
            }
            return sb.toString().trimEnd()
        }
    }
}
