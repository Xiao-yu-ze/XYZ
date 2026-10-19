package com.pvz2.auto.shizuku

/**
 * 虚拟屏的 flags 位组合。
 *
 * ## 为什么这个要单独一个文件、还要写单测
 *
 * 这些常量在 Android SDK 里是 `@hide` 的（`DisplayManager.VIRTUAL_DISPLAY_FLAG_*`），
 * 我们能拿到的只有数字。而**错一位的表现是"整机性失败"**：屏建出来了，
 * 但游戏挂不上去 / 触摸进不去 / shell 一碰就 SecurityException —— 而且不会告诉你
 * 是哪一位错。所以这几十个 bit 值得当成可验证的算式来写，而不是散在调用处。
 *
 * ## 值的来源
 *
 * 全部来自 AOSP `android14-release` 分支的
 * `core/java/android/hardware/display/DisplayManager.java`，并已与会话 7 的三路
 * 独立实现交叉核对（MaaFwApp / scrcpy / AOSP 源码），三份一致：
 *
 * ```
 * PUBLIC                      1        PRESENTATION              2
 * OWN_CONTENT_ONLY            8        SUPPORTS_TOUCH           64
 * ROTATES_WITH_CONTENT        128      DESTROY_CONTENT_ON_REMOVAL 256
 * TRUSTED                     1024     OWN_DISPLAY_GROUP      2048
 * ALWAYS_UNLOCKED             4096     TOUCH_FEEDBACK_DISABLED 8192
 * OWN_FOCUS                   16384    DEVICE_DISPLAY_GROUP   32768
 * STEAL_TOP_FOCUS_DISABLED    65536
 * ```
 *
 * ## 三条必须记住的推理（错了就是整机失败，不是小 bug）
 *
 * ① **`PUBLIC` 必需**。缺它 → 这块屏被标 `DisplayDeviceInfo.FLAG_PRIVATE` →
 *    `DisplayInfo.hasAccess(uid)` 对**非 owner uid** 返回 false。而 ownerUid 是
 *    `com.android.shell`，游戏是另一个 uid → **窗口根本挂不上去**。
 *
 * ② **绝不含 `AUTO_MIRROR`**（值 4）。`DisplayManagerService.createVirtualDisplay`
 *    里那条 `canProjectVideo()` 权限检查，判定开关就是 AUTO_MIRROR：
 *    ```java
 *    if ((flags & FLAG_OWN_CONTENT_ONLY) != 0) flags &= ~FLAG_AUTO_MIRROR;
 *    if (callingUid != SYSTEM_UID && (flags & FLAG_AUTO_MIRROR) != 0) {
 *        if (!canProjectVideo(projection)) throw new SecurityException(...);
 *    }
 *    ```
 *    我们永远不带 AUTO_MIRROR，所以这条检查直接跳过，shell 身份就够了。
 *    注意 `OWN_CONTENT_ONLY`(8) 本身就会把 AUTO_MIRROR 清掉，是双保险。
 *
 * ③ **API 33+ 必须带 `TRUSTED`**（高置信推断，未逐行取到原文）。13 起为 TRUSTED
 *    另加了一道 `ADD_TRUSTED_DISPLAY` 检查；不带 TRUSTED 会落到需要
 *    `CAPTURE_VIDEO_OUTPUT` 的分支而被拒。shell 持有 `ADD_TRUSTED_DISPLAY`
 *    （`packages/Shell/Android.bp` 是 platform 签名 + privileged），但没有
 *    `CAPTURE_VIDEO_OUTPUT` —— 所以这一位是"能过 / 被拒"的分水岭。
 *
 * ## 版本阶梯
 *
 * | 系统 | flags | 合计 |
 * |---|---|---|
 * | 任意 | BASE | **459** |
 * | 33+ | BASE + API33_ADDED | **15819** |
 * | 34+ | BASE + API33_ADDED + API34_ADDED | **64971** |
 *
 * （会话 7 的文档把 34+ 记作 74971，是笔误：459+15360+49152 = **64971**。
 *   这里以实测可核对的加法为准，见单测。真机上按 logcat 打印的实际值再校一次。）
 */
object VirtualDisplayFlags {

    // ---------------------------------------------------------------- 位定义

    /** 屏是 public 的，别的 uid 才被允许往上挂窗口。**不可省**。 */
    const val PUBLIC = 1

    /** 标记为 presentation 类显示（系统 UI 会把它当外接屏对待）。 */
    const val PRESENTATION = 2

    /** 只显示这块屏自己的内容，不镜像主屏。顺带会清掉 AUTO_MIRROR。 */
    const val OWN_CONTENT_ONLY = 8

    /** 允许触摸事件路由到这块屏。 */
    const val SUPPORTS_TOUCH = 64

    /** 内容旋转时屏也跟着转。 */
    const val ROTATES_WITH_CONTENT = 128

    /** 虚拟屏被移除时销毁其上内容（避免残留 task）。 */
    const val DESTROY_CONTENT_ON_REMOVAL = 256

    /** API 33+：受信任显示。详见类注释 ③。 */
    const val TRUSTED = 1024

    /** API 33+：自成 display group。 */
    const val OWN_DISPLAY_GROUP = 2048

    /** API 33+：常亮解锁态，熄屏也不影响这块屏。 */
    const val ALWAYS_UNLOCKED = 4096

    /** API 33+：关掉触摸反馈圈（自动化时那圈水波纯属干扰）。 */
    const val TOUCH_FEEDBACK_DISABLED = 8192

    /** API 34+：这块屏自己持有焦点。 */
    const val OWN_FOCUS = 16384

    /** API 34+：独立 display group（与 OWN_DISPLAY_GROUP 是两套语义，34 起用这个）。 */
    const val DEVICE_DISPLAY_GROUP = 32768

    // ---------------------------------------------------------------- 组合

    /** 所有版本都要的基础集 = 459。 */
    const val BASE: Int = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or
        SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL

    /** API 33 起追加 = 15360。 */
    const val API33_ADDED: Int = TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or
        TOUCH_FEEDBACK_DISABLED

    /** API 34 起再追加 = 49152。 */
    const val API34_ADDED: Int = OWN_FOCUS or DEVICE_DISPLAY_GROUP

    /**
     * 按系统版本算出该传的 flags。
     *
     * @param sdkInt `Build.VERSION.SDK_INT`。传 0 或负数按最低算（只有 BASE），
     *   这样单测和误传都不会算出负数。
     */
    fun forSdk(sdkInt: Int): Int {
        var flags = BASE
        if (sdkInt >= 33) flags = flags or API33_ADDED
        if (sdkInt >= 34) flags = flags or API34_ADDED
        return flags
    }

    /** 把 flags 拆成人可读的名字串，用于日志。排查"哪一位"时直接打印这个。 */
    fun describe(flags: Int): String {
        val known = listOf(
            "PUBLIC" to PUBLIC,
            "PRESENTATION" to PRESENTATION,
            "OWN_CONTENT_ONLY" to OWN_CONTENT_ONLY,
            "SUPPORTS_TOUCH" to SUPPORTS_TOUCH,
            "ROTATES_WITH_CONTENT" to ROTATES_WITH_CONTENT,
            "DESTROY_CONTENT_ON_REMOVAL" to DESTROY_CONTENT_ON_REMOVAL,
            "TRUSTED" to TRUSTED,
            "OWN_DISPLAY_GROUP" to OWN_DISPLAY_GROUP,
            "ALWAYS_UNLOCKED" to ALWAYS_UNLOCKED,
            "TOUCH_FEEDBACK_DISABLED" to TOUCH_FEEDBACK_DISABLED,
            "OWN_FOCUS" to OWN_FOCUS,
            "DEVICE_DISPLAY_GROUP" to DEVICE_DISPLAY_GROUP,
        )
        val set = known.filter { (flags and it.second) != 0 }.map { it.first }

        // 把已知位全部清掉，剩下的就是没人认识的位 —— 那才是真正要查的东西。
        var unknown = flags
        for ((_, value) in known) unknown = unknown and value.inv()

        return "0x%X (%d) [%s]%s".format(
            flags, flags,
            set.joinToString(" + "),
            if (unknown != 0) " + 未知位0x%X".format(unknown) else ""
        )
    }
}
