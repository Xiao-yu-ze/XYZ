package com.pvz2.auto.core

/**
 * 植物大战僵尸 2 的各渠道包名。
 *
 * ## 为什么需要这个
 *
 * 之前代码里硬编码了 `com.popcap.pvz2cthdop` —— 那是 **OPPO 渠道**。
 * 用户装的若是别的渠道（华为 / vivo / 九游 / 应用宝 …），包名不同，
 * 「启动游戏」这一步直接找不到应用，后面的建屏、搬移全都无从谈起。
 *
 * 包名清单来自 MaaPVZ 的 jobset（`StartApp` 动作），逐条对照过。
 *
 * ## 关于「同一个包名对应多个渠道」
 *
 * 官服 / taptap / 好游快报 三个渠道的包名都是 `com.popcap.pvz2cthdbk` ——
 * 这不是笔误：它们是同一个安装包，只是登录入口不同。所以列表里保留三条，
 * 但 [detect] 只会返回第一个匹配到的。
 */
object Pvz2Channels {

    /**
     * 一个渠道。
     *
     * @param key 稳定标识，用于存储用户选择（不要用 label，文案会改）
     * @param label 界面上显示的名字
     * @param packageName Android 包名
     */
    data class Channel(
        val key: String,
        val label: String,
        val packageName: String,
    )

    /** 全部已知渠道。顺序按使用频率大致排。 */
    val ALL: List<Channel> = listOf(
        Channel("oppo", "OPPO", "com.popcap.pvz2cthdop"),
        Channel("vivo", "vivo", "com.popcap.pvz2cthdbbg"),
        Channel("honor", "荣耀", "com.popcap.pvz2cthd.honor"),
        Channel("huawei", "华为", "com.popcap.pvz2cthdhwct"),
        Channel("lenovo", "联想", "com.popcap.pvz2cthdlx"),
        Channel("uc", "九游", "com.popcap.pvz2cthduc"),
        Channel("360", "360", "com.popcap.pvz2cthd360"),
        Channel("bilibili", "B 服", "com.popcap.pvz2cthd.bilibili"),
        Channel("baidu", "百度", "com.popcap.pvz2cthd.g.baidu"),
        Channel("4399", "4399", "com.popcap.pvz2cthd4399"),
        Channel("xm", "小米", "com.popcap.pvz2cthdxm"),
        Channel("yyb", "应用宝", "com.tencent.tmgp.pvz2hdtxyyb"),
        Channel("bazhang", "7723", "com.popcap.pvz2cthdbazhang"),
        // 下面三条是同一个包（官服安装包），只是登录方式不同
        Channel("official", "官服", "com.popcap.pvz2cthdbk"),
        Channel("taptap", "TapTap", "com.popcap.pvz2cthdbk"),
        Channel("goodgame", "好游快报", "com.popcap.pvz2cthdbk"),
    )

    /** 默认渠道（OPPO 是流传最广的一版）。 */
    val DEFAULT: Channel = ALL.first { it.key == "oppo" }

    fun byKey(key: String): Channel? = ALL.firstOrNull { it.key == key }

    /**
     * 从已安装列表里自动认渠道。
     *
     * @param installedPackages 设备上已安装的包名集合
     * @return 第一个匹配到的渠道；一个都没有返回 null
     */
    fun detect(installedPackages: Collection<String>): Channel? =
        ALL.firstOrNull { it.packageName in installedPackages }
}
