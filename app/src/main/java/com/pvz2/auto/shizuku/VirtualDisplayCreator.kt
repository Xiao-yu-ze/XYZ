package com.pvz2.auto.shizuku

import android.content.Context
import android.util.Log
import android.view.Display
import android.view.Surface

/**
 * 建虚拟屏的唯一入口。
 *
 * 抽成单独一个对象，是为了让**两条调用路径**共用同一份逻辑：
 *  - 正常运行：`Pvz2UserService`（Shizuku 拉起的特权进程）里调；
 *  - 排查故障：`VdProbeMain`（`adb shell` 直接跑 app_process）里调。
 *
 * 第二条第存在很实际的意义：当 Shizuku 那边的失败原因不明时，可以绕开 Shizuku
 * 单独验「系统到底允许不允许建这块屏」，把问题范围从三件事缩小到一件。
 *
 * ## 为什么全程反射
 *
 * `DisplayManager.createVirtualDisplay` 是 @hide。在 app_process 里反射没有问题
 * （隐藏 API 限制针对普通应用进程），所以我们不去依赖任何编译期符号。
 */
internal object VirtualDisplayCreator {

    /**
     * 建成功的结果。
     *
     * 注意这里**两个对象都要留着**，它们不是一回事：
     *  - [virtualDisplay] 是 @hide 的 `android.hardware.display.VirtualDisplay`，
     *    释放要靠它（`VirtualDisplay.release()`）；
     *  - [display] 是公开的 `android.view.Display`，读尺寸/displayId 靠它。
     *
     * 曾经只存了前者却按 `android.view.Display` 转型，结果
     * `ClassCastException: VirtualDisplay cannot be cast to Display` —— 屏建出来了，
     * 却在取 displayId 时炸掉，日志上还看不到 displayId。
     */
    class Created(
        val virtualDisplay: Any,
        val display: Display,
        val displayId: Int
    )

    /**
     * @param callerContext 必须是一个 `getPackageName()` 返回「属于本进程 uid 的包名」的
     *   Context。系统服务会校验 `packageName must match the calling uid`，给错了直接
     *   SecurityException（这一条踩过两次，见 Pvz2UserService 的注释）。
     * @return 建成的屏
     * @throws Throwable 反射链上任何一环失败都原样抛出，调用方负责把原因打清楚
     */
    /**
     * @param callerContext 必须是一个 `getPackageName()` 返回「属于本进程 uid 的包名」的
     *   Context。系统服务会校验 `packageName must match the calling uid`，给错了直接
     *   SecurityException（这一条踩过两次，见 Pvz2UserService 的注释）。
     * @return 建成的屏
     * @throws Throwable 反射链上任何一环失败都原样抛出，调用方负责把原因打清楚
     */
    fun create(
        callerContext: Context,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        refreshRate: Float = 0f
    ): Created {
        val displayManagerClass = Class.forName("android.hardware.display.DisplayManager")
        // ★ 关键：用 FakeContext 造一个**新的 DisplayManager 实例**，再调它的**实例方法**。
        //
        // 这是 scrcpy / MaaFwApp 的做法，也是我们之前一直没走对的地方。
        // FakeContext 把 checkCallingPermission() 重写成恒返回 GRANTED，
        // 于是 createVirtualDisplay 里那几道 canProjectVideo() / TRUSTED 检查才过得去。
        // 用系统 Context 或自己的 ContextWrapper 都会在这里被拒。
        val displayManager = displayManagerClass
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(FakeContext.get())

        // ★ 带刷新率建屏：先走 VirtualDisplayConfig 这条路。
        //
        //   游戏在虚拟屏里的帧率由虚拟屏刷新率决定（App 读 Display.getRefreshRate()
        //   来决定渲染节奏）。老的六参 createVirtualDisplay 不带刷新率，系统用默认
        //   （通常 60Hz），游戏就永远上不去 120。
        //
        //   只在 refreshRate > 0 且系统够新时尝试；失败就回落到老路径 ——
        //   宁可刷新率不对，也不能因为这条新路走不通就建不了屏。
        if (refreshRate > 0f && android.os.Build.VERSION.SDK_INT >= 33) {
            val viaConfig = runCatching {
                createWithConfig(displayManager, displayManagerClass, name, width, height, dpi, surface, flags, refreshRate)
            }.onFailure {
                val root = generateSequence(it) { e -> e.cause }.last()
                Log.w(TAG, "带刷新率建屏失败，回落到默认刷新率：${root.javaClass.name}: ${root.message}")
            }.getOrNull()
            if (viaConfig != null) {
                Log.i(TAG, "createVirtualDisplay(VirtualDisplayConfig) 成功，刷新率 ${refreshRate}Hz")
                return finishCreate(viaConfig)
            }
        }

        val createMethod = displayManagerClass
            .getDeclaredMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
                Int::class.javaPrimitiveType
            )
            .apply { isAccessible = true }

        // ★ flags 用**候选列表逐个降级试**，而不是只赌一种。
        //   做法照抄 ShadowAuto 的 VirtualDisplaySession：从"最全"试到"最简"，
        //   哪个能成用哪个。不同 ROM 对 OWEN_FOCUS / TRUSTED 的支持差异很大，
        //   只试一种的话，一旦那位不被认就直接整条路失败。
        val candidates = flagCandidates(flags)
        var created: Any? = null
        var lastError: Throwable? = null
        for ((index, candidate) in candidates.withIndex()) {
            try {
                created = createMethod.invoke(displayManager, name, width, height, dpi, surface, candidate)
                if (created != null) {
                    Log.i(
                        TAG,
                        "createVirtualDisplay 成功（候选 ${index + 1}/${candidates.size}）：" +
                            VirtualDisplayFlags.describe(candidate)
                    )
                    break
                }
            } catch (t: Throwable) {
                lastError = t
                // InvocationTargetException 的 message 恒为 null，真正的系统原话在 cause 里。
                // 不打出来就只能靠猜，这个坑我已经踩过很多次。
                val root = generateSequence(t) { it.cause }.last()
                Log.w(
                    TAG,
                    "候选 ${index + 1}/${candidates.size} 失败：${root.javaClass.name}: ${root.message}"
                )
            }
        }
        if (created == null) {
            error("createVirtualDisplay 所有 flags 候选都失败（多半是权限被拒）：$lastError")
        }

        return finishCreate(created)
    }

    /**
     * 用 `VirtualDisplayConfig` 建屏，带上目标刷新率。
     *
     * 全程反射：`VirtualDisplayConfig` 及其 Builder 都是 @hide。
     * 三参数的 `createVirtualDisplay(config, surface, callback)` 是 API 33+。
     */
    private fun createWithConfig(
        displayManager: Any,
        displayManagerClass: Class<*>,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        refreshRate: Float
    ): Any? {
        val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
        val builder = builderClass
            .getConstructor(
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .newInstance(name, width, height, dpi)

        builderClass.getMethod("setFlags", Int::class.javaPrimitiveType).invoke(builder, flags)
        builderClass.getMethod("setRequestedRefreshRate", Float::class.javaPrimitiveType)
            .invoke(builder, refreshRate)

        val config = builderClass.getMethod("build").invoke(builder)

        val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
        val callbackClass = Class.forName("android.hardware.display.VirtualDisplay\$Callback")
        val createMethod = displayManagerClass.getDeclaredMethod(
            "createVirtualDisplay",
            configClass,
            Surface::class.java,
            callbackClass
        ).apply { isAccessible = true }

        // flags 仍然逐个降级试，理由同老路径。
        var lastError: Throwable? = null
        for (candidate in flagCandidates(flags)) {
            try {
                builderClass.getMethod("setFlags", Int::class.javaPrimitiveType)
                    .invoke(builder, candidate)
                val cfg = builderClass.getMethod("build").invoke(builder)
                val vd = createMethod.invoke(displayManager, cfg, surface, null)
                if (vd != null) return vd
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("VirtualDisplayConfig 建屏失败")
    }

    /**
     * 把反射拿到的 VirtualDisplay 转成 [Created]，并做两步后置处理。
     *
     * 抽出来是因为现在有**两条**建屏路径（带刷新率 / 不带），
     * 后置步骤必须完全一致 —— 尤其是 IME 策略那一步，漏了的话
     * 屏建出来了但输入进不去。
     */
    private fun finishCreate(created: Any): Created {
        val display = created.javaClass
            .getMethod("getDisplay")
            .apply { isAccessible = true }
            .invoke(created) as? Display
            ?: error("VirtualDisplay.getDisplay() 返回 null")

        val displayId = display.displayId
        if (displayId <= 0) {
            runCatching { created.javaClass.getMethod("release").invoke(created) }
            error("虚拟屏建出来了但 displayId 无效：$displayId")
        }

        // ★ 设 IME 策略 = LOCAL。
        //   不设的话这块屏"不能接收输入焦点"，表现为：任务 displayId 对了但窗口
        //   仍留在 display 0、注入被 InputDispatcher 以 "no touchable window" 丢弃。
        //   这一步是让窗口真正落到副屏上的关键（来自 ShadowAuto 的做法）。
        DisplayImePolicy.set(displayId, DisplayImePolicy.POLICY_LOCAL)

        return Created(created, display, displayId)
    }

    /**
     * 生成 flags 候选列表，按成功率从高到低排。
     *
     * ## `OWN_CONTENT_ONLY` 必须保留（这条是 Android 16 真机日志证明的）
     *
     * 曾经以为「去掉 OWN_CONTENT_ONLY 能让 PUBLIC 生效」，于是把它排到了候选首位。
     * 真机上的后果是 `DisplayManagerService` 打了这两行：
     *
     * ```
     * Public virtual displays are auto mirror by default,
     *   hence adding VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR.
     * Auto mirror displays must be in the default display group,
     *   hence ignoring VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP.
     * ```
     *
     * 也就是说：**没有 OWN_CONTENT_ONLY，PUBLIC 屏会被自动加上 AUTO_MIRROR，
     * 变成一块"镜像屏"**，被塞进默认 display group。这种屏不能承载 Activity ——
     * 应用启动不上去，`launchDisplayId` 完全不起作用。
     *
     * AOSP 里正是 `OWN_CONTENT_ONLY` 负责清掉 `AUTO_MIRROR`：
     * ```java
     * if ((flags & FLAG_OWN_CONTENT_ONLY) != 0) flags &= ~FLAG_AUTO_MIRROR;
     * ```
     *
     * ## 顺带纠正一个更早的错误判断
     *
     * 我曾从「`DisplayDeviceInfo` 里没有 FLAG_PUBLIC」推断出「PUBLIC 被抹掉了」。
     * 那是错的：`DisplayDeviceInfo` **根本没有 FLAG_PUBLIC 这个标志**，
     * 只有反向的 `FLAG_PRIVATE`。缺 PUBLIC 表现为「没有 FLAG_PRIVATE」，
     * 而不是「少了某一行」。我一直在一个不存在的字段上找证据。
     *
     * ## 候选顺序
     *
     * 1. 带 OWN_CONTENT_ONLY + TRUSTED + OWN_FOCUS —— 真机上的正解
     * 2. 带 OWN_CONTENT_ONLY + OWN_FOCUS —— 没有 ADD_TRUSTED_DISPLAY 时（如 MuMu）
     * 3. 带 OWN_CONTENT_ONLY —— 最简可用
     * 4/5. 去掉 OWN_CONTENT_ONLY —— **只在前面全失败时兜底**，
     *      能建出屏但会是镜像屏，应用挂不上去
     */
    private fun flagCandidates(baseFlags: Int): List<Int> {
        val ownContent = VirtualDisplayFlags.OWN_CONTENT_ONLY
        val ownFocus = VirtualDisplayFlags.OWN_FOCUS
        val trusted = VirtualDisplayFlags.TRUSTED
        val withoutOwnContent = baseFlags and ownContent.inv()
        return listOf(
            // 正解：既是 trusted，又不是镜像屏
            baseFlags or trusted or ownFocus,
            baseFlags or ownFocus,
            baseFlags,
            // 兜底：能建屏（但会变成镜像屏，应用挂不上）
            withoutOwnContent or trusted or ownFocus,
            withoutOwnContent,
        ).distinct()
    }

    /** 释放。失败只记日志，不抛 —— 清理路径上抛异常会盖住真正的错。 */
    fun release(created: Any?) {
        if (created == null) return
        runCatching { created.javaClass.getMethod("release").invoke(created) }
    }

    private const val TAG = "VirtualDisplayCreator"
}