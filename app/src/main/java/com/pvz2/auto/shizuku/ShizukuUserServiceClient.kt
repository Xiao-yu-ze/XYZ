package com.pvz2.auto.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.pvz2.auto.IPvz2Service
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/**
 * Binds the Shizuku UserService and exposes it as [IPvz2Service].
 *
 * Shizuku-API README, "Start the User Service": start it with
 * `bindUserService(UserServiceArgs, ServiceConnection)`. The `tag` gives the service a
 * stable identity so an R8-renamed class does not orphan an already-running instance, and
 * `version` is what tells Shizuku to replace it after a code change.
 *
 * `daemon(false)` means the service is torn down together with this app process, which is
 * what we want for a foreground-service-hosted loop.
 */
class ShizukuUserServiceClient(private val context: Context) {

    @Volatile
    private var service: IPvz2Service? = null

    @Volatile
    var lastError: String? = null
        private set

    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, Pvz2UserService::class.java.name)
    )
        .daemon(false)
        .tag(USER_SERVICE_TAG)
        .version(USER_SERVICE_VERSION)
        // Mandatory: UserServiceArgs.processName is null by default and forAdd() wraps it in
        // Objects.requireNonNull, so bindUserService throws NPE without a suffix.
        .processNameSuffix(USER_SERVICE_PROCESS_SUFFIX)

    private val connection = object : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IPvz2Service.Stub.asInterface(it) }
            Log.i(TAG, "user service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.w(TAG, "user service disconnected")
        }
    }

    /** Only call while [ShizukuBridge.hasPermission] is true. */
    fun bind() {
        lastError = null
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                lastError = it.message
                Log.e(TAG, "bindUserService failed", it)
            }
    }

    /** @param remove also kill the remote process, otherwise it outlives the unbind. */
    fun unbind(remove: Boolean = true) {
        runCatching { Shizuku.unbindUserService(args, connection, remove) }
            .onFailure { Log.w(TAG, "unbindUserService failed", it) }
        service = null
    }

    fun peek(): IPvz2Service? = service

    /**
     * Waits for [ServiceConnection.onServiceConnected], which Shizuku delivers on the main
     * thread. Must be called from a background coroutine.
     */
    suspend fun await(timeoutMs: Long = CONNECT_TIMEOUT_MS): IPvz2Service? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            service?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private companion object {
        const val TAG = "ShizukuUserService"
        const val USER_SERVICE_TAG = "pvz2-auto"

        /** Process name suffix; the remote process ends up named `com.pvz2.auto:pvz2`. */
        const val USER_SERVICE_PROCESS_SUFFIX = "pvz2"

        /** Bump when Pvz2UserService changes so Shizuku recreates the remote instance. */
        const val USER_SERVICE_VERSION = 9

        const val CONNECT_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
