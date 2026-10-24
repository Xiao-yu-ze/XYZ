package com.pvz2.auto.zjs

import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 守卫：**界面上能改的每一个字段，都必须真的写得回去**。
 *
 * 这条测试是为一次真实事故写的：`运行JS代码` 的 `jsCode` 和 `执行脚本` 的
 * `filePath` 在描述器里有行、用户能点开输入框，但 `ZFieldEditor` 没有对应
 * 分支 —— 改完弹「值不合法，未保存」，文件一个字都没变。用户以为改了。
 *
 * 逐个人工核对每个动作的每一行不现实（26 种动作 × 十几行），所以用这条
 * 机械守卫：拿每种动作造一个样本 → 摊成行 → 对每个带 field 的行调一次
 * `ZFieldEditor.apply`，返回 null 就是"能改不能存"。
 */
class EditorWriteBackGuardTest {

    /** 这些 field 不是"存值"，是界面动作或子结构，点开就进别的地方。 */
    private val uiOnly = setOf(
        "preview", "colorPick", "around", "callbacks", "scriptSet", "condition",
        "cond.type", "children",
        // 这三个的 field 是完整字符串（vars.input 等），按整串比较
        "vars.input", "vars.mustInput", "vars.remember"
    )

    private fun sample(type: Z.ActionType): Z.Action {
        val posType = ZFieldEditor.expectedPosType(type)
        val pos = if (posType == null) null else Z.PosData(
            type = posType,
            x = ZValue.parse("10"),
            y = ZValue.parse("20"),
            text = if (posType == Z.PosType.TEXT) "abc" else null
        )
        return Z.Action(
            type = type,
            posData = pos,
            startPos = if (type == Z.ActionType.SWIPE) pos else null,
            endPos = if (type == Z.ActionType.SWIPE) pos else null,
            fingers = if (type == Z.ActionType.MULTI_SWIPE) {
                listOf(Z.Action.Finger(startPos = pos, endPos = pos))
            } else emptyList(),
            extra = when (type) {
                Z.ActionType.KEY -> mutableMapOf<String, Any?>("keyCode" to 4)
                Z.ActionType.INPUT_TEXT -> mutableMapOf<String, Any?>("inputText" to "abc")
                Z.ActionType.RUN_JS -> mutableMapOf<String, Any?>("jsCode" to "1+1")
                Z.ActionType.RUN_SCRIPT -> mutableMapOf<String, Any?>("filePath" to "子.zjs")
                Z.ActionType.PROMPT -> mutableMapOf<String, Any?>("promptText" to "hi")
                else -> LinkedHashMap()
            }
        )
    }

    @Test
    fun `每种动作的每个可编辑字段都能写回`() {
        val bad = ArrayList<String>()
        for (type in Z.ActionType.entries) {
            val action = sample(type)
            for (row in ZActionDescriber.describe(action)) {
                val field = row.field
                if (field.isEmpty()) continue
                if (field in uiOnly) continue
                if (field.substringAfterLast('.') in uiOnly) continue
                if (row.kind == ZActionDescriber.Kind.ENTER) continue
                if (row.kind == ZActionDescriber.Kind.IMAGE) continue
                if (row.kind == ZActionDescriber.Kind.AREA) continue
                if (row.kind == ZActionDescriber.Kind.COORD) continue

                val value = when (row.kind) {
                    ZActionDescriber.Kind.SWITCH -> if (row.value == "是") "否" else "是"
                    ZActionDescriber.Kind.NUMBER -> "1"
                    // 相似度只收 50~100（协议），用 90 而不是 1
                    ZActionDescriber.Kind.PERCENT -> "90"
                    else -> row.value.ifBlank { "1" }
                }
                val out = ZFieldEditor.apply(action, field, value)
                if (out == null) bad.add("${type.wire} · 「${row.label}」 field=$field")
            }
        }
        assertNotNull(bad)
        if (bad.isNotEmpty()) {
            throw AssertionError(
                "以下行能点、能输入，但写不回去（用户以为改了，文件没变）：\n  " +
                    bad.joinToString("\n  ")
            )
        }
    }
}
