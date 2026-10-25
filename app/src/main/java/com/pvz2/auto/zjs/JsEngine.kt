package com.pvz2.auto.zjs

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * 跑「运行JS代码」和「JS 表达式」条件的脚本引擎。
 *
 * ## API 照抄自动精灵
 *
 * 自动精灵把它的 JS API 以 TypeScript 声明的形式内嵌在产物里
 * （`ren-runarea.pretty.js` 里那段 `declare var zdjl: ZdjlApi`），
 * 脚本里写的就是 `zdjl.getVar('关卡')` 这种形式。所以这里**不自己发明接口**，
 * 而是照那份声明实现同名同参的方法：
 *
 * ```js
 * zdjl.getVar(name, scope?)      zdjl.setVar(name, value, scope?)
 * zdjl.deleteVar(name)           zdjl.getVars(scope?)      zdjl.clearVars()
 * zdjl.getStorage(key)           zdjl.setStorage(key, v)   zdjl.removeStorage(key)
 * zdjl.sleep(ms)                 zdjl.toast(msg)
 * zdjl.getClipboard()            zdjl.setClipboard(text)
 * zdjl.getDeviceInfo()           zdjl.click(x, y, dur)     zdjl.swipe(...)
 * ```
 *
 * 变量名规则也照它的 `checkVarNameValid`：不能为空、不能以数字开头、
 * 只允许字母/数字/下划线/中文。
 *
 * **本机没实现的**（findNode / requestUrl / writeFile / vibrator / keyPress…）
 * 一律在调用时抛错并说明"本机未实现"，不会静默返回 undefined ——
 * 静默返回才是最难查的那种。
 *
 * ## 为什么是 Rhino
 *
 * 自动精灵用 QuickJS。我们没有它的 Android 预编译库；
 * WebView 的 evaluateJavascript 必须主线程且异步，而执行器是一条同步阻塞线程，
 * 接进来只能死等。Rhino 纯 Java、就在调用线程上算，解释模式
 * （`optimizationLevel = -1`）能绕开 Android 上生成字节码的坑。
 * 代价：它是 ES5 级别的引擎，用到箭头函数/let/模板串会报语法错误。
 */
object JsEngine {

    /**
     * 脚本能碰到的宿主能力。执行器把它需要的那部分传进来，
     * 单测里传假的即可（不需要虚拟屏/文件系统）。
     */
    class Env(
        /** 运行时变量表 —— 读和写都是这一份 */
        val vars: MutableMap<String, String>,
        val log: (String) -> Unit,
        /** 持久化存储（zdjl.getStorage/setStorage）。null = 没接通道 */
        val storageGet: ((String) -> String?)? = null,
        val storageSet: ((String, String) -> Unit)? = null,
        val storageRemove: ((String) -> Unit)? = null,
        /** 屏幕信息（zdjl.getDeviceInfo） */
        val deviceInfo: () -> Map<String, Any> = { emptyMap() },
        /** 注入能力（zdjl.click/swipe） */
        val tap: ((Int, Int, Int) -> Unit)? = null,
        val swipe: ((Int, Int, Int, Int, Int) -> Unit)? = null,
        val clipboardGet: (() -> String?)? = null,
        val clipboardSet: ((String) -> Boolean)? = null,
        val toast: ((String) -> Unit)? = null,
        /**
         * 外部「停止」检查：返回 true 就让 JS 立刻中断。
         *
         * 没有它的话 `while(true){}` 会把执行线程**永久**占住 ——
         * 用户点「停止」也救不回来（Rhino 自己不会让出）。
         */
        val shouldStop: (() -> Boolean)? = null
    )

    data class Out(val ok: Boolean, val error: String? = null, val value: String? = null)

    /** 单次 JS 的墙钟上限：正常脚本远用不到，纯粹防死循环。 */
    private const val JS_TIMEOUT_MS = 30_000L

    /** 每执行这么多条 Rhino 指令回调一次（检查超时/停止）。 */
    private const val JS_OBSERVE_THRESHOLD = 10_000

    /**
     * 让 Rhino 定期回调，好中断死循环。
     *
     * Rhino 默认**不回调**，`while(true){}` 会一直跑；装了
     * instructionObserverThreshold + 这个 factory 之后，回调返回 false
     * 会以 Error 打断执行（我们捕获后报「超时/已停止」）。
     */
    private object GuardedContextFactory : org.mozilla.javascript.ContextFactory() {
        override fun makeContext(): Context = Context()

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            val deadline = deadlineNanos.get() ?: 0L
            if (deadline > 0 && System.nanoTime() > deadline) {
                throw Error("JS interrupted: timeout")
            }
            val stop = stopCheck.get()
            if (stop != null && stop.invoke()) throw Error("JS interrupted: stopped")
        }
    }

    private val deadlineNanos = ThreadLocal<Long>()
    private val stopCheck = ThreadLocal<(() -> Boolean)?>()

    /** 只注册一次；重复 init 会抛异常，所以吞掉。 */
    private val jsFactoryReady: Boolean by lazy {
        runCatching {
            org.mozilla.javascript.ContextFactory.initGlobal(GuardedContextFactory)
        }.isSuccess
    }

    /** 跑一段 JS。 */
    fun run(code: String, env: Env): Out {
        val cx = Context.enter()
        try {
            jsFactoryReady
            // 装了阈值 Rhino 才会回调 observeInstructionCount
            cx.instructionObserverThreshold = JS_OBSERVE_THRESHOLD
            deadlineNanos.set(System.nanoTime() + JS_TIMEOUT_MS * 1_000_000L)
            stopCheck.set(env.shouldStop)
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initSafeStandardObjects(null, false)

            // 变量也铺成全局名，方便 `关卡 + 1` 这种写法
            for ((k, v) in env.vars) {
                if (isValidVarName(k)) ScriptableObject.putProperty(scope, k, toJsValue(v))
            }
            ScriptableObject.putProperty(scope, "zdjl", buildZdjl(cx, scope, env))
            ScriptableObject.putProperty(scope, "console", buildConsole(cx, env))
            // 裸 log(...) 别名：自动精灵只有 console.log，但写成 log 太常见了，
            // 给个便宜的别名，免得报 "log is not defined" 让人摸不着头脑
            ScriptableObject.putProperty(scope, "log", fn(cx, "log") { a ->
                env.log("   JS log: " + a.joinToString(" ") { jsToString(it) })
                Undefined.instance
            })

            val result = cx.evaluateString(scope, code, "运行JS代码", 1, null)

            // 全局里的原始类型写回变量表（zdjl.setVar 已经是直接写，这里兜住 `关卡 = 关卡 + 1`）
            for (k in env.vars.keys.toList()) {
                if (!isValidVarName(k)) continue
                val v = ScriptableObject.getProperty(scope, k)
                if (v is Number || v is String || v is Boolean) env.vars[k] = jsToString(v)
            }
            val text = if (result is Undefined || result == null) null else jsToString(result)
            if (text != null) env.vars["result"] = text
            return Out(ok = true, value = text)
        } catch (e: Throwable) {
            /*
             * Rhino 被打断时抛的是 Error("...interrupted...")，原文用户看不懂，
             * 换成能操作的说法。
             */
            val raw = e.message ?: e.javaClass.simpleName
            val msg = if (raw.contains("interrupt", ignoreCase = true) ||
                raw.contains("observeInstructionCount", ignoreCase = true)
            ) {
                "JS 执行被中断（超过 ${JS_TIMEOUT_MS / 1000} 秒，或运行被停止）—— 检查有没有死循环"
            } else {
                raw
            }
            return Out(ok = false, error = msg)
        } finally {
            deadlineNanos.remove()
            stopCheck.remove()
            Context.exit()
        }
    }

    /** 求布尔（「JS 表达式」条件用）。null = 求值失败，**不是** false。 */
    fun evalBool(expr: String, env: Env): Boolean? {
        val out = run(expr, env)
        if (!out.ok) return null
        val v = out.value ?: return null
        return v.toBooleanStrictOrNull() ?: when (v.lowercase()) {
            "1" -> true
            "0", "", "null", "undefined", "nan" -> false
            else -> true          // JS 真值语义：非空字符串即 true
        }
    }

    /** 变量名规则：照自动精灵的 checkVarNameValid。 */
    fun isValidVarName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name[0].isDigit()) return false
        return name.all { it.isLetterOrDigit() || it == '_' || it in '\u4e00'..'\u9fa5' }
    }

    // ------------------------------------------------------------ zdjl 对象

    private fun buildZdjl(cx: Context, scope: Scriptable, env: Env): NativeObject {
        val o = NativeObject()

        fun put(name: String, body: (Array<Any?>) -> Any?) {
            o.put(name, o, fn(cx, name, body))
        }

        // ---- 变量
        put("getVar") { a ->
            val n = arg(a, 0)
            checkVarName(n)
            env.vars[n]?.let { toJsValue(it) } ?: Undefined.instance
        }
        put("setVar") { a ->
            val n = arg(a, 0)
            checkVarName(n)
            val text = jsToString(a.getOrNull(1))
            env.vars[n] = text
            ScriptableObject.putProperty(scope, n, toJsValue(text))
            Undefined.instance
        }
        put("deleteVar") { a ->
            val n = arg(a, 0)
            env.vars.remove(n)
            Undefined.instance
        }
        put("getVars") { _ ->
            NativeObject().apply {
                for ((k, v) in env.vars) put(k, this, toJsValue(v))
            }
        }
        put("printVars") { _ ->
            env.log("变量：" + env.vars.entries.joinToString("，") { "${it.key}=${it.value}" })
            Undefined.instance
        }
        put("clearVars") { _ ->
            env.vars.clear()
            Undefined.instance
        }

        // ---- 持久化存储（跨运行保留：关卡数这类就用它）
        put("getStorage") { a ->
            val k = arg(a, 0)
            env.storageGet?.invoke(k)?.let { toJsValue(it) } ?: Undefined.instance
        }
        put("setStorage") { a ->
            val k = arg(a, 0)
            val setter = env.storageSet ?: throw IllegalStateException("zdjl.setStorage：本机未接存储通道")
            setter(k, jsToString(a.getOrNull(1)))
            Undefined.instance
        }
        put("removeStorage") { a ->
            val k = arg(a, 0)
            val rm = env.storageRemove ?: throw IllegalStateException("zdjl.removeStorage：本机未接存储通道")
            rm(k)
            Undefined.instance
        }

        // ---- 等待 / 提示
        put("sleep") { a ->
            val ms = jsToString(a.firstOrNull()).toDoubleOrNull()?.toLong() ?: 0L
            if (ms > 0) Thread.sleep(ms.coerceAtMost(60_000))
            Undefined.instance
        }
        put("toast") { a ->
            val msg = jsToString(a.firstOrNull())
            env.log("   toast: $msg")
            env.toast?.invoke(msg)
            Undefined.instance
        }
        put("alert") { a ->
            env.log("   alert: " + jsToString(a.firstOrNull()))
            Undefined.instance
        }

        // ---- 剪贴板
        put("getClipboard") { _ ->
            env.clipboardGet?.invoke().orEmpty()
        }
        put("setClipboard") { a ->
            val text = jsToString(a.firstOrNull())
            val ok = env.clipboardSet?.invoke(text) ?: false
            if (!ok) throw IllegalStateException("zdjl.setClipboard：本机未接剪贴板通道")
            Undefined.instance
        }

        // ---- 设备信息
        put("getDeviceInfo") { _ ->
            NativeObject().apply {
                for ((k, v) in env.deviceInfo()) {
                    put(k, this, if (v is Number) v.toDouble() else v)
                }
            }
        }

        // ---- 注入
        put("click") { a ->
            val x = px(a.getOrNull(0), env, true)
            val y = px(a.getOrNull(1), env, false)
            val dur = jsToString(a.getOrNull(2)).toDoubleOrNull()?.toInt() ?: 0
            val t = env.tap ?: throw IllegalStateException("zdjl.click：本机未接注入通道")
            t(x, y, dur)
            Undefined.instance
        }
        put("press") { a ->
            val x = px(a.getOrNull(0), env, true)
            val y = px(a.getOrNull(1), env, false)
            val dur = jsToString(a.getOrNull(2)).toDoubleOrNull()?.toInt() ?: 0
            (env.tap ?: throw IllegalStateException("zdjl.press：本机未接注入通道"))(x, y, dur)
            Undefined.instance
        }
        put("longClick") { a ->
            val x = px(a.getOrNull(0), env, true)
            val y = px(a.getOrNull(1), env, false)
            (env.tap ?: throw IllegalStateException("zdjl.longClick：本机未接注入通道"))(x, y, 800)
            Undefined.instance
        }
        put("swipe") { a ->
            val x1 = px(a.getOrNull(0), env, true)
            val y1 = px(a.getOrNull(1), env, false)
            val x2 = px(a.getOrNull(2), env, true)
            val y2 = px(a.getOrNull(3), env, false)
            val dur = jsToString(a.getOrNull(4)).toDoubleOrNull()?.toInt() ?: 300
            (env.swipe ?: throw IllegalStateException("zdjl.swipe：本机未接注入通道"))(x1, y1, x2, y2, dur)
            Undefined.instance
        }

        // ---- 本机暂不支持的（照声明列出来，调用即明确报错）
        for (name in UNSUPPORTED) {
            put(name) { throw IllegalStateException("zdjl.$name：本机未实现") }
        }
        return o
    }

    /** 自动精灵 zdjl 里本机没有对应能力的那些方法。 */
    private val UNSUPPORTED = listOf(
        "findNode", "findNodeAsync", "findLocation", "findLocationAsync",
        "recognitionScreen", "recognitionScreenAsync", "ocr", "ocrAsync",
        "getScreenColor", "getScreenColorAsync", "getScreenAreaColors", "getScreenAreaColorsAsync",
        "runAction", "runActionAsync", "check", "checkAsync",
        "requestUrl", "requestUrlAsync", "readFile", "readFileAsync",
        "writeFile", "writeFileAsync", "appendFile", "appendFileAsync",
        "playMedia", "playMediaAsync", "vibrator", "vibratorAsync",
        "keyDown", "keyUp", "keyPress", "keyPressAsync",
        "touchDown", "touchMove", "touchUp",
        "gesture", "gestures", "wakeupScreen", "getLocation", "getLocationAsync",
        "getUser", "getAppVersion", "getInstalledAppInfo", "getMousePosition",
        "setScreenBrightness", "setWifiEnable", "setBluetoothEnable", "setCameraFlashEnable",
        "confirm", "confirmAsync", "prompt", "promptAsync", "select", "selectAsync",
        "deleteVarWithConfirm", "clearVarsWithConfirm", "alertAsync"
    )

    private fun buildConsole(cx: Context, env: Env): NativeObject {
        val o = NativeObject()
        for ((name, level) in listOf(
            "log" to "", "info" to "", "debug" to "", "warn" to "警告：", "error" to "错误："
        )) {
            o.put(name, o, fn(cx, name) { a ->
                env.log("   JS $name: $level" + a.joinToString(" ") { jsToString(it) })
                Undefined.instance
            })
        }
        return o
    }

    // ------------------------------------------------------------ 小工具

    private fun fn(cx: Context, name: String, body: (Array<Any?>) -> Any?): BaseFunction =
        object : BaseFunction() {
            override fun getFunctionName(): String = name
            override fun call(
                c: Context,
                s: Scriptable,
                thisObj: Scriptable?,
                args: Array<out Any?>
            ): Any? = body(args as Array<Any?>)
        }

    private fun arg(a: Array<Any?>, i: Int): String = jsToString(a.getOrNull(i))

    private fun checkVarName(name: String) {
        require(isValidVarName(name)) {
            "变量名「$name」不合法：不能为空、不能以数字开头，只能用字母/数字/下划线/中文"
        }
    }

    /** 坐标参数：可以是数字，也可以是 `"50%"` / dp 这类字符串（协议允许）。 */
    private fun px(v: Any?, env: Env, isX: Boolean): Int {
        val text = jsToString(v).trim()
        if (text.isEmpty()) throw IllegalArgumentException("坐标为空")
        val info = env.deviceInfo()
        val w = (info["width"] as? Number)?.toInt() ?: 1280
        val h = (info["height"] as? Number)?.toInt() ?: 720
        val base = if (isX) w else h
        if (text.endsWith("%")) {
            return ((text.dropLast(1).toDoubleOrNull() ?: 0.0) / 100.0 * base).toInt()
        }
        return text.toDoubleOrNull()?.toInt() ?: throw IllegalArgumentException("坐标「$text」不是数字")
    }

    private fun toJsValue(text: String): Any = text.toDoubleOrNull() ?: text

    private fun jsToString(v: Any?): String = when (v) {
        null, Undefined.instance -> ""
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Number -> v.toString()
        else -> Context.toString(v)
    }
}
