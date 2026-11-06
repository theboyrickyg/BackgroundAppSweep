package com.rickyboy.appsweep

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/**
 * One place that runs privileged shell commands. Uses the built-in ADB connection
 * ([Adb]) and, if the person already uses Shizuku, that as a backup.
 */
object Shell {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Where the setup is up to. */
    enum class Status {
        READY,
        NEED_DEV_OPTIONS,   // Developer options not switched on yet
        NEED_DEBUGGING,     // Network / Wireless debugging not on, or not reachable
        NEED_PAIRING,       // Wireless debugging is on but this app hasn't been paired
        CONNECTING
    }

    val readyListeners = CopyOnWriteArraySet<() -> Unit>()

    fun isReady() = Adb.isConnected() || shizukuService != null

    fun exec(cmd: String): String = when {
        Adb.isConnected() -> Adb.exec(cmd)
        shizukuService != null -> try {
            shizukuService?.exec(cmd) ?: ""
        } catch (_: Exception) {
            ""
        }
        else -> ""
    }

    /**
     * Gets connected if it can. [userAsked] = the person pressed Connect, so it's fine for the
     * device to show its "Allow debugging?" box. Otherwise only reconnects silently.
     */
    suspend fun ensureConnected(ctx: Context, userAsked: Boolean): Status = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext Status.READY

        // Shizuku, if they already have it running and allowed
        if (shizukuUsable()) {
            bindShizuku(ctx)
            repeat(15) {
                if (shizukuService != null) return@withContext Status.READY
                delay(200)
            }
        }

        if (!Adb.developerOptionsOn(ctx) && !Adb.hasWorkedBefore(ctx)) return@withContext Status.NEED_DEV_OPTIONS

        if (userAsked || Adb.hasWorkedBefore(ctx) || Adb.hasPaired(ctx)) {
            when (Adb.connect(ctx)) {
                Adb.Result.CONNECTED -> {
                    readyListeners.forEach { it() }
                    return@withContext Status.READY
                }
                Adb.Result.NEEDS_PAIRING -> return@withContext Status.NEED_PAIRING
                Adb.Result.NOT_AVAILABLE -> Unit
            }
        }
        if (Adb.wirelessDebuggingOn(ctx) && !Adb.hasPaired(ctx)) Status.NEED_PAIRING else Status.NEED_DEBUGGING
    }

    // ---------- Optional Shizuku backup ----------

    @Volatile
    private var shizukuService: IShellService? = null
    private var shizukuBound = false
    private var shizukuArgs: Shizuku.UserServiceArgs? = null

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder != null && binder.pingBinder()) {
                shizukuService = IShellService.Stub.asInterface(binder)
                readyListeners.forEach { it() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuService = null
            shizukuBound = false
        }
    }

    fun onShizukuDead() {
        shizukuService = null
        shizukuBound = false
    }

    fun shizukuInstalled(ctx: Context) = isInstalled(ctx, SHIZUKU_PACKAGE)

    fun shizukuRunning(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun shizukuUsable(): Boolean = try {
        shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun bindShizuku(ctx: Context) {
        if (shizukuBound || !shizukuUsable()) return
        val a = shizukuArgs ?: Shizuku.UserServiceArgs(
            ComponentName(ctx.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        shizukuArgs = a
        try {
            Shizuku.bindUserService(a, shizukuConnection)
            shizukuBound = true
        } catch (_: Throwable) {
        }
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean =
        try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
}
