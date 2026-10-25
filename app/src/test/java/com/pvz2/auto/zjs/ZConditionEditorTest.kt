package com.pvz2.auto.zjs

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件的增删改。
 *
 * 依据 FIELDS.md 第 4 节：13 种条件、8 个通用字段。
 */
class ZConditionEditorTest {

    @Test
    fun `下拉里 12 种类型都能新建，位置周围条件单独创建`() {
        /*
         * AroundCondition 按协议不进「条件类型」下拉（FIELDS 4.2），
         * 它只用于 posData.filterByAroundConditionSet。
         * 但 create() 必须还能造出来 —— 旧脚本里存着这个类型。
         */
        assertEquals(11, ZConditionEditor.TYPE_LABELS.size)
        assertEquals(
            Z.ConditionType.AROUND,
            ZConditionEditor.create(Z.ConditionType.AROUND).type
        )
        for ((type, label) in ZConditionEditor.TYPE_LABELS) {
            val c = ZConditionEditor.create(type)
            assertEquals("[$label] 类型应保持", type, c.type)
            // 新建的条件落盘后必须能被读回来
            val back = Z.Condition.parse(c.toJson())
            assertNotNull("[$label] 应能往返", back)
            assertEquals(type, back!!.type)
        }
    }

    @Test
    fun `随机条件默认 50 百分比`() {
        // 空条件执行时必然判 false，用户会以为动作永远不跑。
        // 所以每种类型都要给个能跑的默认值。
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        assertEquals(50, c.extra["percent"])
    }

    @Test
    fun `限制运行次数默认 1 次`() {
        val c = ZConditionEditor.create(Z.ConditionType.LIMIT_RUN_TIMES)
        assertEquals(1, c.extra["limitTimes"])
        assertEquals("afterFinish", c.extra["resetTime"])
    }

    @Test
    fun `位置类条件带对应类型的空位置`() {
        // 键名必须和类型匹配，否则落盘后自动精灵取不到（FIELDS.md:266-269）
        val img = ZConditionEditor.create(Z.ConditionType.IMAGE)
        assertEquals(Z.PosType.IMAGE, img.posData!!.type)

        val color = ZConditionEditor.create(Z.ConditionType.COLOR)
        assertEquals(Z.PosType.COLOR, color.posData!!.type)
        // 落盘键名是 colorData
        assertTrue(color.toJson().has("colorData"))
        assertTrue(!color.toJson().has("image"))
    }

    @Test
    fun `改条件的标量字段`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        val a = ZConditionEditor.apply(c, "percent", "30")!!
        assertEquals(30, a.extra["percent"])
        // 原对象不变
        assertEquals(50, c.extra["percent"])
    }

    @Test
    fun `改取反和重复检查`() {
        var c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        c = ZConditionEditor.apply(c, "runWhenFalse", "是")!!
        c = ZConditionEditor.apply(c, "repeatWhenFalse", "是")!!
        c = ZConditionEditor.apply(c, "repeatWhenFalseLimitTimes", "5")!!

        assertTrue(c.runWhenFalse)
        assertTrue(c.repeatWhenFalse)
        assertEquals(5, c.repeatWhenFalseLimitTimes)
    }

    @Test
    fun `换类型保留通用开关`() {
        // 用户勾了取反、写了备注，换类型时不该丢
        var c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        c = ZConditionEditor.apply(c, "runWhenFalse", "是")!!
        c = ZConditionEditor.apply(c, "desc", "我的条件")!!

        val switched = ZConditionEditor.changeType(c, Z.ConditionType.IMAGE)
        assertEquals(Z.ConditionType.IMAGE, switched.type)
        assertTrue("取反要保留", switched.runWhenFalse)
        assertEquals("我的条件", switched.desc)
        // 位置对象要是新类型该有的
        assertEquals(Z.PosType.IMAGE, switched.posData!!.type)
    }

    @Test
    fun `不认识的字段返回 null`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        assertNull(ZConditionEditor.apply(c, "不存在的", "x"))
    }

    // ------------------------------------------------------------ 摊平成行

    @Test
    fun `条件摊平后标量和开关都有`() {
        var c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        c = ZConditionEditor.apply(c, "repeatWhenFalse", "是")!!

        val rows = ZConditionEditor.describe(c)
        val labels = rows.map { it.label }
        for (must in listOf("条件类型", "百分比", "备注", "取反", "禁用", "不成立时反复检查")) {
            assertTrue("缺 [$must]：$labels", labels.any { it == must })
        }
        // 识别次数/间隔始终显示 —— 藏在开关后面会让人以为字段没做
        assertTrue("缺 [识别次数]：$labels", labels.contains("识别次数"))
        assertTrue("缺 [识别间隔(ms)]：$labels", labels.contains("识别间隔(ms)"))
        val times = rows.first { it.label == "识别次数" }
        // 协议默认 0 = 无限次，所以未显式设置时显示 0
        assertEquals("0", times.value)
        assertEquals(ZActionDescriber.Kind.NUMBER, times.kind)
    }

    @Test
    fun `没开重复检查时次数也显示真实数字`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        val rows = ZConditionEditor.describe(c)
        val times = rows.firstOrNull { it.label == "识别次数" }
        assertNotNull("识别次数应该一直可见", times)
        // 值恒显示真实数字（默认 0）：原来显示 "—"，用户改了看不到、以为没保存
        assertEquals("0", times!!.value)
        // 但这几个字段能改，不因为开关关着就变成只读
        assertEquals(ZActionDescriber.Kind.NUMBER, times.kind)
    }

    @Test
    fun `条件类型显示中文名`() {
        val c = ZConditionEditor.create(Z.ConditionType.IMAGE)
        val row = ZConditionEditor.describe(c).first()
        assertEquals("出现图片", row.value)
    }

    @Test
    fun `开关字段挂的是枚举让我们能下拉选`() {
        val c = ZConditionEditor.create(Z.ConditionType.RANDOM)
        val row = ZConditionEditor.describe(c).first { it.label == "取反" }
        // 布尔值现在是开关，直接拨，不再弹"否/是"下拉
        assertEquals(ZActionDescriber.Kind.SWITCH, row.kind)
        assertTrue(row.options.isEmpty())
    }

    @Test
    fun `条件组显示匹配方式`() {
        val c = ZConditionEditor.create(Z.ConditionType.CONDITION_SET)
        val rows = ZConditionEditor.describe(c)
        val mode = rows.firstOrNull { it.label == "匹配方式" }
        assertNotNull(mode)
        // 界面上是中文，协议里是 and/or/count —— 写回时由 apply 还原
        assertEquals("全部满足", mode!!.value)
        assertEquals(listOf("全部满足", "任一满足", "满足指定个数"), mode.options)
    }

    @Test
    fun `坐标类型的位置条件显示坐标行并挂取点类型`() {
        // location 类型才有 x/y。/位置周围条件 这类会带坐标基准点。
        val c = Z.Condition.parse(
            JSONObject("""{"type":"image","image":{"type":"location","x":"100","y":"200"}}""")
        )!!
        val rows = ZConditionEditor.describe(c)
        val x = rows.firstOrNull { it.label == "位置 X" }
        assertTrue("应有坐标行：${rows.map { it.label }}", x != null)
        assertEquals(ZActionDescriber.Kind.COORD, x!!.kind)
        assertEquals("100", x.value)
    }

    @Test
    fun `文字条件显示文字而不是坐标`() {
        // textFount 的位置是 text 对象，没有 x/y —— 显示"文字"才对
        val c = Z.Condition.parse(
            JSONObject("""{"type":"textFount","text":{"type":"text","text":"开始"}}""")
        )!!
        val rows = ZConditionEditor.describe(c)
        assertTrue("应有文字行：${rows.map { it.label }}", rows.any { it.label == "文字" })
        assertTrue("不该有坐标行", rows.none { it.label == "位置 X" })
    }
    // ------------------------------------------------------------ 类型专属字段（后补的）

    @Test
    fun `在时间之后能填时间`() {
        // 这条以前只有摘要、没有字段，用户选完类型就卡住了
        var c = ZConditionEditor.create(Z.ConditionType.TIME_AFTER)
        val row = ZConditionEditor.describe(c).first { it.label == "时间" }
        assertEquals(ZActionDescriber.Kind.TEXT, row.kind)

        c = ZConditionEditor.apply(c, "timeValue", "09:30:58")!!
        assertEquals("09:30:58", c.extra["timeValue"])
        assertEquals("09:30:58", ZConditionEditor.describe(c).first { it.label == "时间" }.value)
    }

    @Test
    fun `动作运行状态能填目标动作和状态`() {
        var c = ZConditionEditor.create(Z.ConditionType.SCRIPT_RUN_STATE)
        val rows = ZConditionEditor.describe(c)
        assertTrue(rows.any { it.label == "目标动作" })
        assertTrue(rows.any { it.label == "状态" })

        c = ZConditionEditor.apply(c, "aimPosition", "第3个动作")!!
        c = ZConditionEditor.apply(c, "aimScriptRunState", "失败")!!
        // 界面中文，协议英文 —— 写错就是条件静默失效
        assertEquals("第3个动作", c.extra["aimPosition"])
        assertEquals("fail", c.extra["aimScriptRunState"])
    }

    @Test
    fun `时间间隔的单位和忽略首次都能改`() {
        var c = ZConditionEditor.create(Z.ConditionType.TIME_INTERVAL)
        val unit = ZConditionEditor.describe(c).first { it.label == "间隔单位" }
        assertEquals(ZActionDescriber.Kind.ENUM, unit.kind)
        assertEquals("秒", unit.value)

        c = ZConditionEditor.apply(c, "timeIntervalUnit", "分钟")!!
        c = ZConditionEditor.apply(c, "ignoreInitPass", "是")!!
        assertEquals(2, c.extra["timeIntervalUnit"])
        assertEquals(true, c.extra["ignoreInitPass"])
    }

    @Test
    fun `限制次数的清零时机写回英文`() {
        var c = ZConditionEditor.create(Z.ConditionType.LIMIT_RUN_TIMES)
        val row = ZConditionEditor.describe(c).first { it.label == "清零时机" }
        assertEquals("跑完后", row.value)

        c = ZConditionEditor.apply(c, "resetTime", "每次重复后")!!
        assertEquals("afterRepeat", c.extra["resetTime"])
    }

    @Test
    fun `图片条件能改相似度和范围`() {
        var c = ZConditionEditor.create(Z.ConditionType.IMAGE)
        val labels = ZConditionEditor.describe(c).map { it.label }
        for (must in listOf("模板", "相似度", "范围", "预览")) {
            assertTrue("缺 [$must]：$labels", labels.contains(must))
        }
        // 模板必须是可截取的 IMAGE 行，不是只读文字
        assertEquals(
            ZActionDescriber.Kind.IMAGE,
            ZConditionEditor.describe(c).first { it.label == "模板" }.kind
        )

        c = ZConditionEditor.apply(c, "cond.minSimilarPercent", "75%")!!
        assertEquals(75, c.posData!!.minSimilarPercent)

        c = ZConditionEditor.apply(c, "cond.limitArea", "10% 20% 30% 40%")!!
        assertEquals("10% 20% 30% 40%", c.posData!!.limitArea.toString())
    }

    @Test
    fun `图片条件能挂模板图`() {
        var c = ZConditionEditor.create(Z.ConditionType.IMAGE)
        val img = Z.ImageData(
            data = "AA", width = 12, height = 34,
            left = 1, top = 2, screenWidth = 1280, screenHeight = 720
        )
        c = ZConditionEditor.applyImage(c, "cond.image", img)!!
        assertEquals(12, c.posData!!.imageData!!.width)
        assertEquals("12×34", ZConditionEditor.describe(c).first { it.label == "模板" }.value)
    }

    @Test
    fun `AI 云识别有文字速度和三张图`() {
        val c = ZConditionEditor.create(Z.ConditionType.AI_CLOUD)
        val labels = ZConditionEditor.describe(c).map { it.label }
        for (must in listOf("文字", "速度", "图1", "图2", "图3")) {
            assertTrue("缺 [$must]：$labels", labels.contains(must))
        }
        val speed = ZConditionEditor.describe(c).first { it.label == "速度" }
        assertEquals("普通", speed.value)
        assertEquals(listOf("快", "普通"), speed.options)
    }

    @Test
    fun `条件组选按个数时出现数量字段`() {
        var c = ZConditionEditor.create(Z.ConditionType.CONDITION_SET)
        assertTrue(ZConditionEditor.describe(c).none { it.label == "匹配数量" })

        c = ZConditionEditor.apply(c, "matchMode", "满足指定个数")!!
        assertEquals("count", c.extra["matchMode"])
        assertTrue(ZConditionEditor.describe(c).any { it.label == "匹配数量" })
    }
}