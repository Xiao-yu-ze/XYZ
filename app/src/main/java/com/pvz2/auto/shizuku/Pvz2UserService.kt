package com.pvz2.auto.shizuku

import android.os.ParcelFileDescriptor
import com.pvz2.auto.IPvz2Service

/**
 * The privileged side of the app.
 *
 * Shizuku instantiates this class inside its own `app_process`, running with shell
 * (uid 2000) or root (uid 0) identity, and drives it through `IPvz2Service`. It is
 * therefore **not** an `android.app.Service`, is never declared in the manifest, and must
 * not touch framework APIs that need a real application Context.
 *
 * It must also keep a public no-argument constructor: that is what Shizuku calls.
 */
class Pvz2UserService : IPvz2Service.Stub() {

    /** Read end of the most recent screenshot pipe, closed on the next call / on destroy. */
    @Volatile
    private var previousCaptureRead: ParcelFileDescriptor? = null

    override fun screencap(): ParcelFileDescriptor {
        // The Binder transaction that handed the previous descriptor to the caller has
        // already completed (and duplicated the fd), so dropping our copy is safe and keeps
        // the process from accumulating one descriptor per frame.
        closePreviousCapture()

        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        previousCaptureRead = read

        // Copying must happen off this Binder thread: `screencap` emits ~1-3 MB while a pipe
        // buffer holds only 64 KB, so an inline copy would block here and deadlock the
        // caller before it ever gets the descriptor back.
        Thread({
            try {
                // `2>/dev/null` matters - the child's stderr is a pipe nobody drains, and a
                // chatty failure would otherwise block it forever.
                val process = ProcessBuilder("sh", "-c", "screencap -p 2>/dev/null").start()
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { output ->
                    process.inputStream.use { input -> input.copyTo(output) }
                }
                process.waitFor()
            } catch (_: Throwable) {
                // The pipe just closes early; the caller fails to decode and skips the frame.
            } finally {
                runCatching { write.close() }
            }
        }, "pvz2-screencap").start()

        return read
    }

    override fun tap(x: Int, y: Int) {
        runCatching { runShell("input tap $x $y") }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        runCatching { runShell("input swipe $x1 $y1 $x2 $y2 $durationMs") }
    }

    override fun exec(command: String): String =
        runCatching { runShell(command) }.getOrElse { "ERROR: ${it.message}" }

    override fun getDisplaySize(): String {
        // `wm size` prints "Physical size: 1080x2340" and, when an override exists,
        // "Override size: 720x1560". The override is the effective size, so take the last.
        val output = runCatching { runShell("wm size") }.getOrDefault("")
        return SIZE_PATTERN.findAll(output).lastOrNull()?.value.orEmpty()
    }

    override fun destroy() {
        closePreviousCapture()
        System.exit(0)
    }

    /**
     * Runs a command through `sh -c`.
     *
     * `redirectErrorStream(true)` merges stderr into stdout so a command that writes to
     * stderr can never fill its pipe buffer and hang.
     */
    private fun runShell(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }

    private fun closePreviousCapture() {
        runCatching { previousCaptureRead?.close() }
        previousCaptureRead = null
    }

    private companion object {
        val SIZE_PATTERN = Regex("""\d+x\d+""")
    }
}
