package com.pvz2.auto.ui.page

import com.pvz2.auto.zjs.Z

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
 * ## 为什么不像别处那样用 StateFlow
 *
 * 这里只有编辑器一个消费者，而且它每次重建都会主动 `load()` 一次。
 * 用普通字段 + "已有内容就别重读文件"就够了，多一层 Flow 只会
 * 让读写路径变长。
 */
object ZjsEditState {

    /** 正在编辑的脚本。null = 还没加载过。 */
    var script: Z.Script? = null

    /** 当前文件名，保存时用。 */
    var fileName: String = ""

    /**
     * 是否已经丢失过编辑（页面重建时检测到内存里有未保存改动）。
     *
     * 用来在界面上提示"有未保存的改动"，而不是让用户以为改动生效了。
     */
    var dirty: Boolean = false

    /** 用户在文件列表里选了另一个脚本时必须清掉，否则会把旧内容当新脚本。 */
    fun reset() {
        script = null
        fileName = ""
        dirty = false
    }
}
