package com.pvz2.auto.zjs

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守卫：**每个可编辑字段都要有说明**（界面上点「?」能看到）。
 *
 * 用户的要求是"所有字段都要有"，靠人眼扫一遍必然漏；
 * 所以把动作、条件、位置全铺开，逐行检查：
 * 带 field 的行必须能查到说明（[ZFieldHelp] 或行上显式给的）。
 */
class FieldHelpCoverageTest {

    private val base = "{\"repeatCount\":1,\"delay\":\"0\",\"delayUnit\":0," +
        "\"screenWidth\":1280,\"screenHeight\":720}"

    private fun pos(type: Z.PosType) = Z.PosData(
        type = type,
        x = ZValue.parse("10"),
        y = ZValue.parse("20"),
        text = if (type == Z.PosType.TEXT) "abc" else null
    )

    private fun sampleAction(type: Z.ActionType): Z.Action {
        val posType = ZFieldEditor.expectedPosType(type)
        val p = if (posType == null) null else pos(posType)
        return Z.Action(
            type = type,
            posData = p,
            startPos = if (type == Z.ActionType.SWIPE) p else null,
            endPos = if (type == Z.ActionType.SWIPE) p else null,
            extra = when (type) {
                Z.ActionType.RUN_SCRIPT -> mutableMapOf<String, Any?>("filePath" to "子.zjs")
                Z.ActionType.RUN_JS -> mutableMapOf<String, Any?>("jsCode" to "1")
                else -> LinkedHashMap()
            }
        )
    }

    private fun missing(rows: List<ZActionDescriber.Row>, where: String): List<String> =
        rows.filter { it.field.isNotEmpty() }
            .filter { it.help == null && ZFieldHelp.of(it.field) == null }
            .map { "$where · 「${it.label}」 field=${it.field}" }

    @Test
    fun `动作的每个可编辑字段都有说明`() {
        val bad = ArrayList<String>()
        for (t in Z.ActionType.entries) {
            bad += missing(ZActionDescriber.describe(sampleAction(t)), t.wire)
        }
        assertTrue("这些字段没有说明：\n  " + bad.joinToString("\n  "), bad.isEmpty())
    }

    @Test
    fun `条件的每个可编辑字段都有说明`() {
        val bad = ArrayList<String>()
        for (t in Z.ConditionType.entries) {
            val c = ZConditionEditor.create(t)
            bad += missing(ZConditionEditor.describe(c), t.wire)
        }
        assertTrue("这些条件字段没有说明：\n  " + bad.joinToString("\n  "), bad.isEmpty())
    }

    @Test
    fun `每种位置的每个字段都有说明`() {
        val bad = ArrayList<String>()
        for (pt in Z.PosType.entries) {
            val action = Z.Action(type = Z.ActionType.CLICK, posData = pos(pt))
            bad += missing(ZActionDescriber.describe(action), "位置:" + pt.wire)
        }
        assertTrue("这些位置字段没有说明：\n  " + bad.joinToString("\n  "), bad.isEmpty())
    }
}
