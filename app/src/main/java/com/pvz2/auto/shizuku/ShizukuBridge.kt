package com.pvz2.auto.shizuku

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Availability and permission helpers around the official Shizuku API.
 *
 * The permission flow mirrors the Shizuku-API README ("Request permission"), which is
 * modelled on runtime permissions:
 *
 *     if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED)  -> usable
 *     else if (Shizuku.shouldShowRequestPermissionRationale())  -> user chose "Deny and don't ask again"
 *     else                                                      -> Shizuku.requestPermission(code)
 *
 * Every call is guarded because Shizuku throws `IllegalStateException` when its binder has
 * not been received yet, and a `RuntimeException` when the remote call fails.
 */
object ShizukuBridge {

    const val PERMISSION_REQUEST_CODE = 4210

    /** Shizuku (or Sui) is running and its binder is alive. */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** We may call privileged APIs. */
    fun hasPermission(): Boolean = runCatching {
        !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku is installed and the user has not permanently denied us. */
    fun canRequestPermission(): Boolean = runCatching {
        isBinderAlive() && !Shizuku.isPreV11() && !Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    fun requestPermission(code: Int = PERMISSION_REQUEST_CODE) {
        runCatching { Shizuku.requestPermission(code) }
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /**
     * Sticky variant: fires immediately when the binder is already available, otherwise on
     * every (re)connect. The README notes it can fire more than once - for example when the
     * user restarts Shizuku while the app is alive - so callers must be idempotent.
     */
    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.addBinderReceivedListenerSticky(listener) }
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        runCatching { Shizuku.removeBinderReceivedListener(listener) }
    }
}
