package com.pvz2.auto.zjs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 神器字段写回：界面显示中文标签，落盘必须写 wire key。
 *
 * ## 为什么单独立这个测试
 *
 * 真机踩过的坑：`pvzArtifact / pvzGourdSize / pvzCameraMode` 三个字段被误放进
 * [ZFieldEditor] 的 **EXTRA_INTS**（整型白名单）。结果在下拉里选「葫芦神器」时，
 * 写回走 `toIntOrNull()` → null → **键被删掉**，界面上永远显示「未选」。
 *
 * 当时的 `EditorWriteBackGuardTest` 抓不到：它只检查"apply 不返回 null"，
 * 而这里返回的是"成功删除了键"的动作，非 null 但没写进任何值。
 * 所以补一条断言"值真的落盘且等于 key"。
 */
class ArtifactFieldWriteBackTest {

    private fun action(): Z.Action = Z.Action(type = Z.ActionType.PVZ_ARTIFACT)

    @Test
    fun `选葫芦神器——落盘写 gourd 而不是中文标签`() {
        val out = ZFieldEditor.apply(action(), "pvzArtifact", "葫芦神器")
        assertNotNull("apply 返回 null 说明这个字段根本不能写", out)
        assertEquals("gourd", out!!.extra["pvzArtifact"])
    }

    @Test
    fun `五种神器都能落盘成 key`() {
        for ((key, label) in PvzCoords.ARTIFACT_KINDS) {
            val out = ZFieldEditor.apply(action(), "pvzArtifact", label)
            assertNotNull(label, out)
            assertEquals(label, key, out!!.extra["pvzArtifact"])
        }
    }

    @Test
    fun `葫芦体型和相机模式是字符串不是数字`() {
        val a = ZFieldEditor.apply(action(), "pvzGourdSize", "小")
        assertNotNull(a)
        assertEquals("小", a!!.extra["pvzGourdSize"])

        val b = ZFieldEditor.apply(action(), "pvzCameraMode", "粘贴")
        assertNotNull(b)
        assertEquals("粘贴", b!!.extra["pvzCameraMode"])
    }
}
