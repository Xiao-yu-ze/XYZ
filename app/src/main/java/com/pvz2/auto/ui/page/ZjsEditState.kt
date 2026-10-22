package com.pvz2.auto.ui.page

import com.pvz2.auto.zjs.Z
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 脚本编辑的**跨页面实例**状态。
 *
 * ## 为什么必须放在单例里
 *
 * 编辑器活在 `ViewPager2` 里，翻页时那一页会被销毁重建（默认
 * `offscreenPageLimit = -1`，不留缓存）。如果 `script` 只是页面对象的
 * 一个字段，**用户翻走再翻回来，没保存的编辑就全没了**。
 *
 * 这不是理论风险：实测点「预览」会被带回第 1 页看画面，
 * 再翻回第 3 页时，刚截的模板和刚调的阈值全都退回了文件里的旧值。
 * 用户完全看不出发生了什么 —— 他会以为自己没点中。
 *
 * ## generation 是干什么的
 *
 * 第 2 页点一条脚本要让第 3 页换成那一份。但第 3 页的实例可能还活着
 * （翻页不总是销毁），它 `bind()` 时如果只看"内存里有没有脚本"就会
 * 继续显示旧的那份。所以每次「从脚本库打开」都递增 [generation]，
 * 编辑器发现代号变了就重新渲染。
 */
object ZjsEditState {

    /** 正在编辑的脚本。null = 还没加载过。 */
    var script: Z.Script? = null

    /** 当前文件名（含 .zjs），保存时用。 */
    var fileName: String = ""

    /** 有未保存的改动。 */
    var dirty: Boolean = false

    /** 编辑器内容代号。换脚本时递增，让活着的页面实例知道要重画。 */
    var generation: Int = 0
        private set

    private val _savedRevision = MutableStateFlow(0)

    /**
     * 磁盘上的脚本集合变了（保存、删除）。
     *
     * 第 2 页订阅它来重列 —— 否则用户刚存完回到列表，看不到自己刚做的东西，
     * 会以为保存失败了。
     */
    val savedRevision: StateFlow<Int> = _savedRevision.asStateFlow()

    /** 从脚本库打开一份脚本。 */
    fun open(name: String, script: Z.Script) {
        fileName = name
        this.script = script
        dirty = false
        generation++
    }

    /** 保存成功：清脏标记并通知列表刷新。 */
    fun markSaved() {
        dirty = false
        _savedRevision.value = _savedRevision.value + 1
    }

    /** 用户在文件列表里选了另一份时必须清掉，否则会把旧内容当新脚本。 */
    fun reset() {
        script = null
        fileName = ""
        dirty = false
        generation++
    }
}
