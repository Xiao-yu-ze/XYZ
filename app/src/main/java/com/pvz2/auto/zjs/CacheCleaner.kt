package com.pvz2.auto.zjs

import android.content.Context
import java.io.File

/**
 * 截图缓存清理。
 *
 * ## 为什么需要
 *
 * 调试用的两处截图都会自己长大，而它们在"应用外部目录"里，用户用文件管理器
 * 也未必找得到：
 *
 * | 目录 | 谁写的 | 真机实测 |
 * |---|---|---|
 * | `files/ocr_fail/` | [ZRealMatcher] OCR 失败样本 | **8498 个文件 / 667MB** |
 * | `files/zjs_logs/on_error/` | [ZjsLog] 节点失败现场 | 27 张 / 约 40MB |
 *
 * ocr_fail 那条以前是"每次 OCR 都存一张"，已改成失败时 30 秒一张、上限 120 张
 * （见 [ZRealMatcher] 里的限流），但历史垃圾要能一条命令清掉。
 *
 * ## 清理范围（用户要求：只留日志和脚本）
 *
 * - 删：ocr_fail 目录下的全部 PNG、zjs_logs/on_error 目录下的全部 PNG
 * - 留：zjs 目录下的 .zjs（脚本）、zjs_logs 目录下的 .log（文本日志）
 */
object CacheCleaner {

    /** 清理结果：删了多少个文件、释放多少字节。 */
    data class Report(val files: Int, val bytes: Long) {
        fun mb(): String = String.format(java.util.Locale.US, "%.1f", bytes / 1024.0 / 1024.0)
    }

    private fun targets(ctx: Context): List<File> {
        val files = ctx.getExternalFilesDir(null) ?: return emptyList()
        return listOf(
            File(files, "ocr_fail"),
            File(files, "zjs_logs/on_error")
        )
    }

    /** 只统计，不删 —— 给按钮显示"当前占多少"。 */
    fun size(ctx: Context): Report {
        var n = 0
        var b = 0L
        for (dir in targets(ctx)) {
            dir.listFiles()?.forEach { f ->
                if (f.isFile) {
                    n++
                    b += f.length()
                }
            }
        }
        return Report(n, b)
    }

    /** 真删。返回实际删掉的数量与字节数。 */
    fun clean(ctx: Context): Report {
        var n = 0
        var b = 0L
        for (dir in targets(ctx)) {
            val list = dir.listFiles() ?: continue
            for (f in list) {
                if (!f.isFile) continue
                val size = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    n++
                    b += size
                }
            }
        }
        return Report(n, b)
    }
}
