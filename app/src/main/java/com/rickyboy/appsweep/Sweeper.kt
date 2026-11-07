package com.rickyboy.appsweep

import android.app.ActivityManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings

data class SweepResult(val closed: Int, val ramFreed: Long, val cacheFreed: Long)

/**
 * The engine: finds what's really running (with RAM, CPU and cache), and closes/cleans it.
 * Everything here is blocking — call it from a background thread.
 */
class Sweeper(private val ctx: Context) {

    private val pm: PackageManager = ctx.packageManager
    private val prefs = ctx.getSharedPreferences("appsweep", Context.MODE_PRIVATE)

    // ---------- Protected list ----------

    fun protectedApps(): Set<String> = prefs.getStringSet("protected_apps", emptySet())?.toSet() ?: emptySet()

    fun setProtected(pkg: String, protect: Boolean) {
        val set = protectedApps().toMutableSet()
        if (protect) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet("protected_apps", set).apply()
    }

    // ---------- Auto-start blocked list ----------

    fun autoStartBlockedApps(): Set<String> =
        prefs.getStringSet("autostart_blocked", emptySet())?.toSet() ?: emptySet()

    private fun rememberAutoStart(pkg: String, blocked: Boolean) {
        val set = autoStartBlockedApps().toMutableSet()
        if (blocked) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet("autostart_blocked", set).apply()
    }

    // ---------- Scanning ----------

    /** Lets AppSweep read cache sizes. Done once through Shizuku, no settings screen needed. */
    fun grantUsageAccess() {
        Shell.exec("appops set ${ctx.packageName} GET_USAGE_STATS allow")
    }

    fun scan(includeSystem: Boolean): List<RunningApp> {
        val memory = readProcessMemory()
        if (memory.isEmpty()) return emptyList()
        val cpu = readCpu()
        val launchable = launchablePackages()
        val skip = neverClose()
        val protected = protectedApps()

        val running = memory.keys.filter { pkg ->
            pkg !in skip && (pkg in launchable || includeSystem)
        }
        // Apps you've stopped from auto-starting stay in the list (even when not running) so you can undo it
        val rememberedSet = autoStartBlockedApps()
        val remembered = rememberedSet.filter { it !in running && it !in skip }
        val candidates = running + remembered
        val blocked = readBackgroundBlocked(candidates)

        return candidates.mapNotNull { pkg ->
            val info = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (_: Exception) {
                return@mapNotNull null // not an app (kernel thread, native daemon, etc.)
            }
            RunningApp(
                packageName = pkg,
                label = info.loadLabel(pm).toString(),
                icon = try { info.loadIcon(pm) } catch (_: Exception) { null },
                memoryKb = memory[pkg] ?: 0L,
                cpuPercent = cpu[pkg] ?: 0f,
                cacheBytes = cacheBytes(pkg),
                isSystem = pkg !in launchable,
                isProtected = pkg in protected,
                backgroundBlocked = pkg in blocked || pkg in rememberedSet,
                isRunning = pkg in memory
            )
        }
    }

    /** Easy mode: the apps you've opened since the last sweep, most recent first. */
    fun scanEasy(): List<RunningApp> {
        val protected = protectedApps()
        val skip = neverClose()
        return OpenedApps.list(ctx)
            .filterKeys { it !in skip }
            .mapNotNull { (pkg, time) ->
                val info = try {
                    pm.getApplicationInfo(pkg, 0)
                } catch (_: Exception) {
                    return@mapNotNull null
                }
                RunningApp(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    icon = try { info.loadIcon(pm) } catch (_: Exception) { null },
                    memoryKb = 0,
                    cpuPercent = 0f,
                    cacheBytes = cacheBytes(pkg),
                    isSystem = false,
                    isProtected = pkg in protected,
                    backgroundBlocked = false,
                    isRunning = true,
                    openedAt = time
                )
            }
            .sortedByDescending { it.openedAt }
    }

    /**
     * TV guided mode: apps that have been launched since they were last stopped. Android marks a
     * force-stopped / never-opened app as "stopped", which any app can read without permissions.
     */
    fun scanTvLaunched(): List<RunningApp> {
        val protected = protectedApps()
        val skip = neverClose()
        return launchablePackages()
            .filter { it !in skip && !isStopped(it) }
            .mapNotNull { pkg ->
                val info = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return@mapNotNull null }
                RunningApp(
                    packageName = pkg,
                    label = info.loadLabel(pm).toString(),
                    icon = try { info.loadIcon(pm) } catch (_: Exception) { null },
                    memoryKb = 0, cpuPercent = 0f, cacheBytes = -1,
                    isSystem = false, isProtected = pkg in protected,
                    backgroundBlocked = false, isRunning = true
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun isStopped(pkg: String): Boolean = try {
        (pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_STOPPED) != 0
    } catch (_: Exception) {
        true
    }

    /** RAM per package, from `ps` (sums every process an app has). */
    private fun readProcessMemory(): Map<String, Long> {
        var output = Shell.exec("ps -A -o RSS,NAME 2>/dev/null")
        if (output.lines().size < 5) output = Shell.exec("ps") // very old Android
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyMap()
        val header = lines.first().trim().split(Regex("\\s+"))
        val rssIndex = header.indexOf("RSS").takeIf { it >= 0 } ?: 0
        val result = HashMap<String, Long>()
        for (line in lines.drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 2 || cols.size <= rssIndex) continue
            val pkg = cols.last().substringBefore(':')
            if (!pkg.contains('.')) continue
            result[pkg] = (result[pkg] ?: 0L) + (cols[rssIndex].toLongOrNull() ?: 0L)
        }
        return result
    }

    /** Recent CPU load per package, from `dumpsys cpuinfo`. */
    private fun readCpu(): Map<String, Float> {
        val regex = Regex("""^\s*([\d.]+)%\s+\d+/([^\s:]+)""")
        val result = HashMap<String, Float>()
        for (line in Shell.exec("dumpsys cpuinfo").lines()) {
            val m = regex.find(line) ?: continue
            val pct = m.groupValues[1].toFloatOrNull() ?: continue
            val pkg = m.groupValues[2]
            result[pkg] = (result[pkg] ?: 0f) + pct
        }
        return result
    }

    private fun readBackgroundBlocked(pkgs: List<String>): Set<String> {
        if (Build.VERSION.SDK_INT < 26 || pkgs.isEmpty()) return emptySet()
        val op = if (Build.VERSION.SDK_INT >= 28) "RUN_ANY_IN_BACKGROUND" else "RUN_IN_BACKGROUND"
        val script = pkgs.joinToString("; ") { "echo \"@@$it\"; appops get $it $op" }
        val result = HashSet<String>()
        var current: String? = null
        for (line in Shell.exec(script).lines()) {
            if (line.startsWith("@@")) {
                current = line.removePrefix("@@").trim()
            } else if (current != null && line.contains(op) &&
                (line.contains("ignore") || line.contains("deny"))
            ) {
                result.add(current)
            }
        }
        return result
    }

    fun cacheBytes(pkg: String): Long {
        if (Build.VERSION.SDK_INT < 26) return -1
        return try {
            val ssm = ctx.getSystemService(StorageStatsManager::class.java)
            ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle()).cacheBytes
        } catch (_: Exception) {
            -1
        }
    }

    /** Cache held by every installed app (what "Clear cache" can free). -1 if unknown. */
    fun totalCache(): Long {
        if (Build.VERSION.SDK_INT < 26) return -1
        var total = 0L
        var any = false
        for (app in pm.getInstalledApplications(0)) {
            val c = cacheBytes(app.packageName)
            if (c >= 0) {
                total += c
                any = true
            }
        }
        return if (any) total else -1
    }

    @Suppress("DEPRECATION")
    private fun launchablePackages(): Set<String> {
        val result = HashSet<String>()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            pm.queryIntentActivities(intent, 0).forEach { result.add(it.activityInfo.packageName) }
        }
        return result
    }

    /** Things that would break the device, the remote, or this app if force-stopped. */
    @Suppress("DEPRECATION")
    fun neverClose(): Set<String> {
        val skip = hashSetOf(
            ctx.packageName, Shell.SHIZUKU_PACKAGE, "android", "com.android.systemui",
            "com.android.phone", "com.android.shell", "com.google.android.gms",
            "com.google.android.gsf", "com.android.providers.media",
            "com.google.android.tvrecommendations", "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher", "com.android.bluetooth", "com.android.nfc",
            "com.google.android.inputmethod.latin", "com.google.android.katniss"
        )
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { skip.add(it) }
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { skip.add(it) }
        return skip
    }

    // ---------- Actions ----------

    fun forceStop(pkg: String) {
        Shell.exec("am force-stop $pkg")
    }

    /** Clears just one app's cache (Android 14+). Returns bytes freed. */
    fun clearAppCache(pkg: String): Long {
        val before = cacheBytes(pkg)
        Shell.exec("pm clear --cache-only $pkg")
        val after = cacheBytes(pkg)
        return if (before >= 0 && after >= 0) (before - after).coerceAtLeast(0) else 0
    }

    /**
     * Stops an app starting itself: no background running, no jobs/alarms waking it
     * (restricted standby on Android 11+), then force-stops it. It still opens normally
     * when you tap it.
     */
    fun setAutoStartBlocked(pkg: String, block: Boolean) {
        val mode = if (block) "ignore" else "allow"
        val cmds = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 26) cmds.add("appops set $pkg RUN_IN_BACKGROUND $mode")
        if (Build.VERSION.SDK_INT >= 28) cmds.add("appops set $pkg RUN_ANY_IN_BACKGROUND $mode")
        if (Build.VERSION.SDK_INT >= 30) {
            cmds.add("am set-standby-bucket $pkg ${if (block) "restricted" else "active"}")
        } else if (Build.VERSION.SDK_INT >= 28) {
            cmds.add("am set-standby-bucket $pkg ${if (block) "rare" else "active"}")
        }
        if (block) cmds.add("am force-stop $pkg")
        Shell.exec(cmds.joinToString("; "))
        rememberAutoStart(pkg, block)
    }

    /** The big button. [onProgress] gets 0–100 as it goes (called from a background thread). */
    fun sweepAll(apps: List<RunningApp>, onProgress: (Int) -> Unit = {}): SweepResult {
        val targets = apps.filter { it.isRunning && !it.isProtected && !it.isSystem }
        val ramBefore = availableRam()
        val diskBefore = freeStorage()
        onProgress(3)

        // Close each app in turn (0–65%)
        targets.forEachIndexed { i, app ->
            Shell.exec("am force-stop ${app.packageName}")
            onProgress(5 + 60 * (i + 1) / targets.size)
        }
        onProgress(66)
        Shell.exec("am kill-all")            // other cached background processes that are safe to kill
        onProgress(75)
        Shell.exec("pm trim-caches 999G")    // every app's cache
        onProgress(90)
        Thread.sleep(1500)                   // let Android reclaim memory
        onProgress(100)

        return SweepResult(
            closed = targets.size,
            ramFreed = (availableRam() - ramBefore).coerceAtLeast(0),
            cacheFreed = (freeStorage() - diskBefore).coerceAtLeast(0)
        )
    }

    // ---------- Device stats ----------

    fun memoryInfo(): ActivityManager.MemoryInfo {
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info
    }

    fun availableRam(): Long = memoryInfo().availMem

    fun freeStorage(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    companion object {
        val canClearSingleCache = Build.VERSION.SDK_INT >= 34
        val canBlockBackground = Build.VERSION.SDK_INT >= 26

        fun isSystemFlag(info: ApplicationInfo) = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }
}
