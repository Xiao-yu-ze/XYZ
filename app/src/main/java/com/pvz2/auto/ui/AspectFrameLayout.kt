package com.pvz2.auto.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * 按固定宽高比决定自身高度的 FrameLayout。
 *
 * ## 为什么要自己写
 *
 * 预览画面永远是 1280×720（16:9，虚拟屏的固定尺寸）。如果面板高度是固定 dp，
 * 或者交给子 View 的 `wrap_content` 决定，会出现两种都不好的结果：
 *
 *  - 固定高度（之前的 `180dp`）：平板上那行有 950dp 宽、只有 180dp 高，
 *    比例是 5.3:1。16:9 的画面按高度适配后只剩 320dp 宽，
 *    占不到宽度的三分之一，其余全是黑的 —— 正是用户看到的那块"很长的黑条"。
 *  - `wrap_content` + `minHeight`：没有图时 200dp，第一帧到达后突然变成
 *    宽度×9/16（平板上 534dp），画面**跳一下**；停止后又跳回去。
 *
 * 让面板自己保持 16:9，高度就只依赖宽度这一个变量 —— 任何状态下都不跳动。
 *
 * ## 为什么需要 maxHeight
 *
 * 横屏平板的可用宽度能到 1200dp，16:9 算出来是 675dp。这块面板在一个
 * ScrollView 里，675dp 会把下面的状态行、帧率、导出日志全推到首屏以外 ——
 * 用户抱怨的"画面太小"就变成"要滚动才看得到状态"。
 *
 * 所以超限时封顶。但**封顶后宽度必须跟着收**：
 * 如果只是把高度压到 420dp、宽度仍撑满 1200dp，面板就不再是 16:9 了 ——
 * 变成"一块很宽的黑底，中间缩着一个小画面"，正是用户抱怨的那个丑样子。
 * 宽度也按 16:9 收回来并居中，画面才能真正铺满面板。
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)

        // UNSPECIFIED 时宽度是 0，按 16:9 算出来也是 0 —— 面板会整个塌掉。
        // 这种情形（横向 ScrollView、某些自定义父容器）交回给父类，
        // 让它按子 View 的实际尺寸决定高度。
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // 减掉左右 padding：面板的"内容宽度"才是画面能用的宽度。
        // 不减的话，每边 padding 都会被算进 16:9 里，画面被撑得比容器宽。
        //
        // AT_MOST 是 width=wrap_content 时的常态（外层居中容器给的就是它）：
        // getSize() 是**上限**，我们直接拿它当目标宽度 —— 面板本来就该占满
        // 可用宽度，只是触到高度上限时才收窄。所以 EXACTLY 与 AT_MOST 同一套算法。
        val availWidth = (MeasureSpec.getSize(widthMeasureSpec) -
            paddingLeft - paddingRight).coerceAtLeast(0)
        if (availWidth <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        // 先按"宽度撑满"算出自然高度。
        var contentWidth = availWidth
        var contentHeight = (contentWidth * 9f / 16f).roundToInt()

        // 上限：避免在宽松的横屏上把下面的信息推出首屏。
        val maxContentHeight = (resources.displayMetrics.density * MAX_HEIGHT_DP).roundToInt()
        if (contentHeight > maxContentHeight) {
            // ★ 高度被压住时，宽度也要跟着收回来，否则面板不再是 16:9，
            //   会变成"宽黑底 + 中间小画面"。按同一比例反算宽度。
            contentHeight = maxContentHeight
            contentWidth = (contentHeight * 16f / 9f).roundToInt().coerceAtMost(availWidth)
        }

        // 父级给了 AT_MOST/EXACTLY 时不能超过它，否则会被裁掉。
        //
        // ★ 注意账目：MeasureSpec 里的 size 是**总高度**（含 padding），
        //   而 contentHeight 是**内容高度**（不含 padding）。
        //   直接拿 size 去 clamp contentHeight，后面再加回 padding，
        //   结果会超出父约束整整一个 paddingTop + paddingBottom。
        //   当前面板没有 padding，所以没暴露 —— 但这是个定时炸弹。
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        if (heightMode != MeasureSpec.UNSPECIFIED) {
            val maxContentByParent = (MeasureSpec.getSize(heightMeasureSpec) -
                paddingTop - paddingBottom).coerceAtLeast(0)
            if (contentHeight > maxContentByParent) {
                contentHeight = maxContentByParent
                contentWidth = (contentHeight * 16f / 9f).roundToInt().coerceAtMost(availWidth)
            }
        }

        val totalWidth = contentWidth + paddingLeft + paddingRight
        val totalHeight = contentHeight + paddingTop + paddingBottom
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY)
        )
    }

    private companion object {
        /**
         * 面板内容高度的上限（dp）。
         *
         * 420dp 是权衡结果：横屏平板上 16:9 的自然高度是 675dp，
         * 封顶后面板整体会缩窄并居中（仍保持 16:9），
         * 而不是变成"宽黑底中间小画面"。
         */
        const val MAX_HEIGHT_DP = 420f
    }
}
