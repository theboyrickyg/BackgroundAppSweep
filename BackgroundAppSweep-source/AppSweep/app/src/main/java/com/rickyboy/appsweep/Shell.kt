package com.rickyboy.appsweep

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/** One shared connection to the Shizuku-powered shell, used by every screen. */
object Shell {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    enum class Status { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NEED_PERMISSION, DENIED, CONNECTING, READY }

    @Volatile
    var service: IShellService? = null
        private set

    private var bound = false
    private var args: Shizuku.UserServiceArgs? = null
    val readyListeners = CopyOnWriteArraySet<() -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                service = IShellService.Stub.asInterface(binder)
                readyListeners.forEach { it() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    fun onBinderDead() {
        service = null
        bound = false
    }

    /** Works out where we are in the setup, and starts connecting if possible. */
    fun status(ctx: Context): Status {
        if (!Shizuku.pingBinder()) {
            service = null
            bound = false
            return if (isInstalled(ctx, SHIZUKU_PACKAGE)) Status.NOT_RUNNING else Status.NOT_INSTALLED
        }
        if (Shizuku.isPreV11()) return Status.TOO_OLD
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return if (Shizuku.shouldShowRequestPermissionRationale()) Status.DENIED else Status.NEED_PERMISSION
        }
        if (service != null) return Status.READY
        bind(ctx)
        return Status.CONNECTING
    }

    private fun bind(ctx: Context) {
        if (bound) return
        val a = args ?: Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        args = a
        try {
            Shizuku.bindUserService(a, connection)
            bound = true
        } catch (_: Exception) {
        }
    }

    /** For the quick screens: waits a few seconds for Shizuku + the shell to come up. */
    suspend fun awaitReady(ctx: Context, timeoutMs: Long = 6000): Status {
        var waited = 0L
        var s = status(ctx)
        while ((s == Status.CONNECTING || s == Status.NOT_RUNNING) && waited < timeoutMs) {
            delay(200)
            waited += 200
            s = status(ctx)
        }
        return s
    }

    fun exec(cmd: String): String =
        try {
            service?.exec(cmd) ?: ""
        } catch (_: Exception) {
            ""
        }

    fun isInstalled(ctx: Context, pkg: String): Boolean =
        try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
