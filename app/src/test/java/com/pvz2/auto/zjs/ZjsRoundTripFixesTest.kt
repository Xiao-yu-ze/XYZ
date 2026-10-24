package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉死这一轮修复的四处「读写会丢内容 / 会改坏用户文件」的行为。
 *
 * 这些坑的共同点是**不报错**：文件能存，界面正常，要到导入自动精灵之后
 * 或者跑起来点错位置才发现。所以每条都断言"原文还在"。
 */
class ZjsRoundTripFixesTest {

    // ---------------------------------------------------------------- 版本号

    @Test
    fun `版本号公式与真实文件一致`() {
        // 前两个数据点直接来自 SPEC.md:740 的真实工程文件：
        //   minVerName "2.26.0"  → minVerCode  2026000
        //   savedVerName "26.0.0" → savedVerCode 26000000
        assertEquals(2_026_000, ZjsFileFormat.versionCode("2.26.0"))
        assertEquals(26_000_000, ZjsFileFormat.versionCode("26.0.0"))
        // 审计里给的第三个数据点
        assertEquals(2_022_000, ZjsFileFormat.versionCode("2.22.0"))
        // patch 位是 *10（不是 *1）
        assertEquals(2_026_010, ZjsFileFormat.versionCode("2.26.1"))
    }

    @Test
    fun `写出去的版本码不再是旧公式的 100 倍小值`() {
        val text = ZjsFileFormat.stringify(Z.Script())
        val first = JSONObject(text.split("\r\n").first())
        assertEquals(26_000_000, first.getInt("minVerCode"))
        assertEquals(26_000_000, first.getInt("savedVerCode"))
        assertEquals("26.0.0", first.getString("minVerName"))
    }

    // ---------------------------------------------------------------- 区间

    @Test
    fun `百分比区间保留原文，取值时才随机`() {
        val v = ZValue.parse("30%-70%")
        assertTrue("应是 Range，实际 ${v.javaClass.simpleName}", v is ZValue.Range)
        // ★ 关键：原文不变。旧实现这里会变成 Percent("30%-70%", 0.0) → 点 x=0
        assertEquals("30%-70%", v.raw)
        repeat(30) {
            val px = v.toPx(1000, 1f)
            assertTrue("取到 $px，应落在 300..700", px in 300f..700f)
        }
    }

    @Test
    fun `像素区间保留原文，不会被改写成随机数`() {
        val v = ZValue.parse("100-300")
        assertTrue(v is ZValue.Range)
        // ★ 关键：旧实现在解析那一刻就随机取一个，raw 被换成 "234.56"，
        //   存盘后区间永久消失。
        assertEquals("100-300", v.raw)
        repeat(30) { assertTrue(v.toPx(1000, 1f) in 100f..300f) }
        // 落盘写回的是原文，不是随机值
        val p = Z.PosData(type = Z.PosType.LOCATION, x = v, y = ZValue.parse("10"))
        assertEquals("100-300", p.toJson().getString("x"))
    }

    @Test
    fun `区间里的四种单位都能各自解析`() {
        assertEquals("10dp-20dp", ZValue.parse("10dp-20dp").raw)
        assertEquals("5-9", ZValue.parse("5-9").raw)
        // 单个负号不是区间
        assertEquals("-5", ZValue.parse("-5").raw)
        // 负区间
        assertEquals("-5--1", ZValue.parse("-5--1").raw)
    }

    // ---------------------------------------------------------------- limitArea

    @Test
    fun `limitArea 的 JSON 对象写法能解析成区域`() {
        val area = ZArea.parseObject(
            JSONObject("""{"left":"10%","top":100,"right":"200dp","bottom":"50%"}""")
        )
        assertNotNull("对象写法必须能解析", area)
        // x/left 用宽、y/top 用高；dp 乘密度
        val r = area!!.toPxRect(1000, 500, 2f)
        assertEquals(100, r[0])   // 10% * 1000
        assertEquals(100, r[1])   // 100px
        assertEquals(400, r[2])   // 200dp * 2
        assertEquals(250, r[3])   // 50% * 500
    }

    @Test
    fun `limitArea 对象写法在位置里往返不丢`() {
        val p = Z.PosData.parse(
            JSONObject("""{"type":"image","limitArea":{"left":"10%","top":20,"right":"30%","bottom":40}}""")
        )!!
        assertNotNull("对象写法不该被丢掉", p.limitArea)
        // 写回统一成字符串形态（协议的另一种合法写法），内容不变
        assertEquals("10% 20 30% 40", p.toJson().getString("limitArea"))
    }

    @Test
    fun `解析不了的 limitArea 原样保留而不是静默丢`() {
        val p = Z.PosData.parse(
            JSONObject("""{"type":"image","limitArea":{"left":"10%"}}""")
        )!!
        assertNull(p.limitArea)
        // 半块区域不猜，但原文必须留着
        assertTrue("原文应留在 extra", p.extra.containsKey("limitArea"))
        assertEquals("10%", p.toJson().getJSONObject("limitArea").getString("left"))
    }

    // ---------------------------------------------------------------- 显式 null

    @Test
    fun `显式 null 坐标不会被写成 0%`() {
        val p = Z.PosData.parse(
            JSONObject("""{"type":"location","x":null,"y":"300"}""")
        )!!
        assertNull("null 应保持未设置", p.x)
        assertEquals("300", p.y!!.raw)
        val back = p.toJson()
        assertTrue("未设置就不写这个键", !back.has("x"))
        assertEquals("300", back.getString("y"))
    }

    // ---------------------------------------------------------------- ImageData

    @Test
    fun `ImageData 的 base64 变量形态能读且原样写回`() {
        val img = Z.ImageData.parse(
            JSONObject(
                """{"base64":"iVBORw0KGgo=","width":86,"height":32,
                   "left":300,"top":400,"screenWidth":1920,"screenHeight":1080,"scale":0.5}"""
            )
        )
        assertNotNull("base64 形态必须能解析", img)
        assertEquals("iVBORw0KGgo=", img!!.data)
        assertEquals(86, img.width)
        assertEquals(1920, img.screenWidth)
        assertEquals(0.5, (img.extra["scale"] as Number).toDouble(), 0.0001)

        val back = img.toJson()
        // 变量形态按变量形态写回，不擅自改成 data/imageWidth
        assertTrue("应写 base64", back.has("base64"))
        assertEquals(86, back.getInt("width"))
        assertTrue("scale 应保留", back.has("scale"))
        assertTrue("不该冒出 data", !back.has("data"))
    }

    // ---------------------------------------------------------------- 文字正则

    @Test
    fun `文字的正则对象形态原样写回，不被摊成字符串`() {
        val p = Z.PosData.parse(
            JSONObject("""{"type":"text","text":{"type":"RegExp","source":"第\\d+关","flags":""}}""")
        )!!
        assertEquals(Z.PosType.TEXT, p.type)
        // 运行时契约：text 里保留"对象的 JSON 文本"，ZPosResolver.textRegex 靠它认正则
        assertTrue("text 应保留对象文本，实际 ${p.text}", p.text!!.startsWith("{"))

        val back = p.toJson()
        // ★ 关键：落盘必须是对象。旧实现按 String 写，正则变成它自己的 JSON 文本，
        //   自动精灵不再当正则解析 → 这条"出现文字"永远匹配不到。
        val obj = back.optJSONObject("text")
        assertNotNull("text 必须是对象，不能摊成字符串", obj)
        assertEquals("RegExp", obj!!.getString("type"))
        assertEquals("""第\d+关""", obj.getString("source"))
    }

    @Test
    fun `编辑器改过文字之后以新值为准，不写回旧正则`() {
        val p = Z.PosData.parse(
            JSONObject("""{"type":"text","text":{"type":"RegExp","source":"第\\d+关","flags":""}}""")
        )!!
        // ZFieldEditor 只改 text（String），不认识 textRaw
        val edited = p.copy(text = "第5关")
        val back = edited.toJson()
        assertEquals("第5关", back.getString("text"))
        assertNull("旧的正则对象不该再写出去", back.optJSONObject("text"))
    }

    // ---------------------------------------------------------------- 动作类型

    @Test
    fun `多指手势写回协议原名而不是我们自己改的名字`() {
        val a = Z.Action.parse(JSONObject("""{"type":"多指手势","delayUnit":1}"""))!!
        assertEquals(Z.ActionType.MULTI_SWIPE, a.type)
        // ★ 关键：自动精灵没有"多指滑动"这个类型，遇到未知 type 会让整份文件打不开
        assertEquals("多指手势", a.toJson().getString("type"))
    }

    @Test
    fun `我们自己写出去的旧名字还能读进来`() {
        val a = Z.Action.parse(JSONObject("""{"type":"多指滑动","delayUnit":1}"""))!!
        assertEquals(Z.ActionType.MULTI_SWIPE, a.type)
        // 读一次存一次自动升级成协议名
        assertEquals("多指手势", a.toJson().getString("type"))
    }

    @Test
    fun `gestureActions 递归解析并写回`() {
        val a = Z.Action.parse(
            JSONObject(
                """{"type":"多指手势","delayUnit":1,
                   "gestureActions":[
                     {"type":"点击","delayUnit":1,"posData":{"type":"location","x":"10","y":"20"}},
                     {"type":"滑动","delayUnit":1,
                      "startPos":{"type":"location","x":"1","y":"2"},
                      "endPos":{"type":"location","x":"3","y":"4"}}]}"""
            )
        )!!
        assertEquals(2, a.gestureActions.size)
        assertEquals(Z.ActionType.SWIPE, a.gestureActions[1].type)

        val back = a.toJson().getJSONArray("gestureActions")
        assertEquals(2, back.length())
        assertEquals("点击", back.getJSONObject(0).getString("type"))
        assertEquals("滑动", back.getJSONObject(1).getString("type"))
    }

    // ---------------------------------------------------------------- controlRunType

    @Test
    fun `未知 controlRunType 原样写回而不是变成等待`() {
        val a = Z.Action.parse(
            JSONObject("""{"type":"控制执行","controlRunType":"loopForever","delayUnit":1}""")
        )!!
        // 解析兜底成"等待"（否则整条动作会被丢掉），但原文必须留着
        assertEquals("loopForever", a.rawControlRunType)
        // ★ 关键：旧实现这里写回的是 "waitDelay"，用户文件被改写
        assertEquals("loopForever", a.toJson().getString("controlRunType"))
        assertEquals("控制执行", a.toJson().getString("type"))
    }

    @Test
    fun `已知 controlRunType 不受影响`() {
        val a = Z.Action.parse(
            JSONObject("""{"type":"控制执行","controlRunType":"jumpTo","jumpToPosition":"x"}""")
        )!!
        assertEquals(Z.ActionType.CONTROL_JUMP, a.type)
        assertNull(a.rawControlRunType)
        assertEquals("jumpTo", a.toJson().getString("controlRunType"))
    }

    // ---------------------------------------------------------------- 监听槽位

    @Test
    fun `认不出的监听槽位原样保留`() {
        val a = Z.Action.parse(
            JSONObject(
                """{"type":"点击","delayUnit":1,
                   "scriptCallbacks":{
                     "afterExecSuc":{"type":"点击","delayUnit":1},
                     "onSomeFutureHook":{"type":"按键","delayUnit":1,"keyCode":4}}}"""
            )
        )!!
        assertEquals(1, a.callbacks.size)              // 已知槽位
        assertTrue(a.extraCallbacks.containsKey("onSomeFutureHook"))

        val cbs = a.toJson().getJSONObject("scriptCallbacks")
        assertTrue("已知槽位还在", cbs.has("afterExecSuc"))
        // ★ 关键：旧实现 `?: continue` 直接丢掉未知槽位，注释却说要保留
        assertEquals("按键", cbs.getJSONObject("onSomeFutureHook").getString("type"))
    }

    // ---------------------------------------------------------------- linkedFile

    @Test
    fun `内嵌子脚本保存时原样写回，且顺序在 baseInfo 之后`() {
        val linked = """{"type":"linkedFile","name":"sub.zjs","content":"{\"x\":1}"}"""
        val text = "{\"delayUnit\":1,\"count\":0}\r\n" +
            linked + "\r\n" +
            "{\"type\":\"点击\",\"delayUnit\":1,\"posData\":{\"type\":\"location\",\"x\":\"1\",\"y\":\"2\"}}\r\n"

        val parsed = ZjsFileFormat.parse(text)
        assertNull(parsed.error)
        assertEquals(1, parsed.script!!.actions.size)
        assertEquals(1, parsed.script!!.linkedFiles.size)

        val out = ZjsFileFormat.stringify(parsed.script!!).split("\r\n")
        // baseInfo / 内嵌子脚本 / 动作
        assertEquals(3, out.size)
        assertTrue("第 1 行应是 baseInfo", out[0].contains("minVerName"))
        // ★ 关键：旧实现 stringify 从不写回，这一行会消失
        assertEquals(linked, out[1])
        assertEquals("点击", JSONObject(out[2]).getString("type"))
    }

    @Test
    fun `没有内嵌子脚本时不多写空行`() {
        val s = Z.Script()
        assertEquals(1, ZjsFileFormat.stringify(s).split("\r\n").size)
    }

    // ---------------------------------------------------------------- count

    @Test
    fun `count 也算上 scriptSet 与 gestureActions`() {
        val s = Z.Script.parse(
            JSONObject(
                """{"baseInfo":{},"actions":[
                     {"type":"运行多个动作","delayUnit":1,
                      "scriptSet":[{"type":"点击","delayUnit":1},{"type":"点击","delayUnit":1}]},
                     {"type":"多指手势","delayUnit":1,
                      "gestureActions":[{"type":"点击","delayUnit":1}]}]}"""
            )
        )
        val first = JSONObject(ZjsFileFormat.stringify(s).split("\r\n").first())
        // 2 个主动作 + scriptSet 2 个 + gestureActions 1 个 = 5
        assertEquals(5, first.getInt("count"))
    }

    @Test
    fun `count 也算上 baseInfo 的全局监听`() {
        val s = Z.Script.parse(
            JSONObject(
                """{"baseInfo":{"globalCallbacks":{
                     "afterEveryScriptSuc":{"type":"点击","delayUnit":1}}},
                   "actions":[{"type":"点击","delayUnit":1}]}"""
            )
        )
        val first = JSONObject(ZjsFileFormat.stringify(s).split("\r\n").first())
        assertEquals(2, first.getInt("count"))
    }
}
