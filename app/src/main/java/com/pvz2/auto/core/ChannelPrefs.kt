package com.pvz2.auto.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户选的渠道（持久化）。
 *
 * 为什么单独一个对象而不是塞进 `BotState`：这是**配置**不是**状态** ——
 * 它要跨进程重启保留，而 BotState 是纯内存的运行时状态。
 */
object ChannelPrefs {

    private const val FILE = "pvz2_channel"
    private const val KEY_CHANNEL = "channel_key"
    private const val KEY_CUSTOM = "custom_package"

    /** 记住用户选的渠道 key。 */
    fun saveChannel(context: Context, key: String) {
        prefs(context).edit().putString(KEY_CHANNEL, key).apply()
    }

    /** 记住用户输入的自定义包名。 */
    fun saveCustomPackage(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY_CUSTOM, packageName).apply()
    }

    fun savedChannelKey(context: Context): String? =
        prefs(context).getString(KEY_CHANNEL, null)

    fun savedCustomPackage(context: Context): String =
        prefs(context).getString(KEY_CUSTOM, "").orEmpty()

    /**
     * 算出「这次该启动哪个包」。
     *
     * 优先级：自定义包名 > 已保存的渠道 > 默认渠道。
     * 返回 null 表示自定义包名为空且没有可用渠道（调用方应提示用户）。
     */
    fun resolvePackage(context: Context): String? {
        val custom = savedCustomPackage(context).trim()
        if (custom.isNotEmpty()) return custom

        val key = savedChannelKey(context) ?: return Pvz2Channels.DEFAULT.packageName
        return Pvz2Channels.byKey(key)?.packageName ?: Pvz2Channels.DEFAULT.packageName
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
