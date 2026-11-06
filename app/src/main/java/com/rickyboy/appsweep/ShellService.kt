package com.rickyboy.appsweep

import android.content.Context
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Started by Shizuku in its own process with shell (ADB-level) rights.
 * This is what lets AppSweep see and force-stop other apps and wipe caches —
 * things a normal Android app is not allowed to do.
 */
class ShellService : IShellService.Stub {

    @Keep
    constructor() : super()

    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
}
