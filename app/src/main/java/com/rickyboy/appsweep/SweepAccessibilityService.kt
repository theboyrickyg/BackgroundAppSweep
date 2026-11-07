package com.rickyboy.appsweep

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live state of an Easy-mode sweep, watched by the main screen. */
object EasySweep {
    data class State(
        val running: Boolean = false,
        val percent: Int = 0,
        val current: String = "",
        val result: SweepResult? = null,
        /** What Easy mode saw when it couldn't press a button (to send to the developer). */
        val report: String = ""
    )

    val state = MutableStateFlow(State())

    /** Each app as it's closed, so the list can drop it straight away. */
    val closedNow = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 64)
}

/**
 * Remembers which apps you've opened (Easy mode can't ask Android what's running, so the
 * accessibility service notes each app as it comes on screen).
 */
object OpenedApps {
    private const val KEY = "opened_apps"
    private var last: String? = null
    private var launchable: Set<String>? = null

    @Suppress("DEPRECATION")
    private fun launchable(ctx: Context): Set<String> {
        launchable?.let { return it }
        val pm = ctx.packageManager
        val set = HashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)
                .forEach { set.add(it.activityInfo.packageName) }
        }
        launchable = set
        return set
    }

    fun record(ctx: Context, pkg: String) {
        if (pkg == last) return
        last = pkg
        if (pkg !in launchable(ctx)) return
        if (pkg in Sweeper(ctx).neverClose() || pkg in SweepAccessibilityService.SETTINGS_PACKAGES) return
        val map = list(ctx).toMutableMap()
        map[pkg] = System.currentTimeMillis()
        save(ctx, map)
    }

    fun list(ctx: Context): Map<String, Long> {
        val raw = ctx.getSharedPreferences("appsweep", Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()
        val map = HashMap<String, Long>()
        for (entry in raw) {
            val pkg = entry.substringBefore('|')
            val time = entry.substringAfter('|').toLongOrNull() ?: 0L
            map[pkg] = time
        }
        return map
    }

    fun remove(ctx: Context, pkgs: Collection<String>) {
        val map = list(ctx).toMutableMap()
        pkgs.forEach { map.remove(it) }
        save(ctx, map)
        last = null
    }

    private fun save(ctx: Context, map: Map<String, Long>) {
        ctx.getSharedPreferences("appsweep", Context.MODE_PRIVATE).edit()
            .putStringSet(KEY, map.map { "${it.key}|${it.value}" }.toSet()).apply()
    }
}

/**
 * Easy mode: no Developer options. Switched on once in Settings → Accessibility.
 * Closes apps by opening each one's App info page and pressing Force stop (and Clear cache),
 * behind a full-screen progress card so the person doesn't see the screens flicking.
 */
class SweepAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SweepAccessibilityService? = null
            private set

        fun isOn() = instance != null

        val SETTINGS_PACKAGES = setOf(
            "com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings",
            "com.samsung.android.settings", "com.miui.securitycenter"
        )

        /** True if switched on in Settings, even if the service hasn't connected yet. */
        fun isEnabledInSettings(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, SweepAccessibilityService::class.java)
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it)?.let { c -> c == me } ?: false
            }
        }
    }

    enum class Finish { OPEN_APP, GO_HOME }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    private var busy = false
    private val report = StringBuilder()
    private var notes = 0

    private fun note(msg: String) {
        if (notes++ < 4) report.append(msg).append("\n\n")
    }

    /** Words visible on screen right now (for the report). */
    private fun screenTexts(): String {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || out.size >= 45) return
            val t = n.text?.toString()?.trim().orEmpty()
            val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
            if (t.isNotEmpty()) out.add(if (id.isNotEmpty()) "$t [$id]" else t)
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (_: Exception) { null }, depth + 1)
        }
        roots().forEach { walk(it, 0) }
        return out.joinToString(" | ")
    }
    private var overlay: View? = null

    // ---------- Service lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Shell.readyListeners.forEach { it() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (busy || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            try {
                OpenedApps.record(applicationContext, pkg)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- The sweep ----------

    /**
     * Closes [apps] (package to label). [forceStop] / [clearCache] choose what's done to each.
     * Returns false if a sweep is already running.
     */
    fun sweep(
        apps: List<Pair<String, String>>,
        forceStop: Boolean = true,
        clearCache: Boolean = true,
        finish: Finish = Finish.OPEN_APP
    ): Boolean {
        if (busy) return false
        busy = true
        scope.launch {
            val sweeper = Sweeper(applicationContext)
            val ramBefore = sweeper.availableRam()
            val diskBefore = sweeper.freeStorage()
            var closed = 0
            report.setLength(0)
            notes = 0
            report.append("Phone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}\n\n")
            publish(true, 2, "")
            withContext(Dispatchers.Main) { showOverlay() }
            try {
                apps.forEachIndexed { i, (pkg, label) ->
                    publish(true, 3 + 92 * i / apps.size.coerceAtLeast(1), label)
                    updateOverlay(i + 1, apps.size, label)
                    if (processApp(pkg, label, forceStop, clearCache)) {
                        closed++
                        if (forceStop) {
                            OpenedApps.remove(applicationContext, listOf(pkg))
                            EasySweep.closedNow.tryEmit(pkg)
                        }
                    }
                }
                if (forceStop) OpenedApps.remove(applicationContext, apps.map { it.first })
            } catch (_: Exception) {
            }
            publish(true, 98, "")
            delay(3000)   // let Android reclaim memory before the "after" reading
            val result = SweepResult(
                closed = closed,
                ramFreed = (sweeper.availableRam() - ramBefore).coerceAtLeast(0),
                cacheFreed = (sweeper.freeStorage() - diskBefore).coerceAtLeast(0)
            )
            EasySweep.state.value = EasySweep.State(
                running = false, percent = 100, current = "", result = result,
                report = if (notes > 0) report.toString() else ""
            )
            withContext(Dispatchers.Main) {
                when (finish) {
                    Finish.OPEN_APP -> startActivity(
                        Intent(this@SweepAccessibilityService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    Finish.GO_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                }
                delay(400)
                hideOverlay()
            }
            busy = false
        }
        return true
    }

    private fun publish(running: Boolean, pct: Int, current: String) {
        EasySweep.state.value = EasySweep.State(running = running, percent = pct, current = current, result = null)
        overlayProgress()?.post { overlayProgress()?.progress = pct }
    }

    /**
     * Opens the app's App info page and presses the buttons.
     * Returns true if the app is closed afterwards (including if it was already closed).
     */
    private suspend fun processApp(pkg: String, label: String, forceStop: Boolean, clearCache: Boolean): Boolean {
        withContext(Dispatchers.Main) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
            )
        }
        delay(700)

        // Wait for this app's page (its name on screen) and the Force stop button
        val stopButton = waitFor(4000) {
            val node = find(texts.forceStop)
            if (node != null && (onScreen(label) || it > 2500)) node else null
        }
        if (stopButton == null) {
            note("$label: couldn't find the Force stop button.\nScreen showed: ${screenTexts()}")
            return false
        }

        var closed = !isEnabled(stopButton)          // greyed out = already closed
        if (forceStop && !closed) {
            val stopBounds = android.graphics.Rect()
            stopButton.getBoundsInScreen(stopBounds)
            if (!click(stopButton)) {
                note("$label: Force stop button wouldn't press.")
            } else {
                val ok = waitFor(2500) { findConfirm(stopBounds) }
                if (ok == null) {
                    note("$label: no confirm box found after pressing Force stop.\nScreen showed: ${screenTexts()}")
                } else {
                    click(ok)
                }
                delay(600)
                // Success if the confirm was pressed, or the Force stop button has now greyed out
                val after = find(texts.forceStop)
                closed = ok != null || (after != null && !isEnabled(after))
            }
        }
        if (clearCache) clearCacheHere(label)
        return closed
    }

    /** TV: "Clear cache" is on the App info page. Phone: it's inside "Storage & cache". */
    private suspend fun clearCacheHere(label: String) {
        val direct = find(texts.clearCache)
        if (direct != null) {
            if (isEnabled(direct) && click(direct)) {
                waitFor(1500) { findConfirm() }?.let { click(it) }
                delay(400)
            }
            return
        }
        val storage = find(texts.storage)
        if (storage == null) {
            note("$label: couldn't find Clear cache or Storage.\nScreen showed: ${screenTexts()}")
            return
        }
        if (!click(storage)) return
        val clear = waitFor(3000) { find(texts.clearCache) } ?: return
        if (isEnabled(clear) && click(clear)) {
            waitFor(1000) { findConfirm() }?.let { click(it) }
            delay(400)
        }
    }

    // ---------- Finding and pressing buttons ----------

    /** Button wording read from the device's own Settings app (so it works in any language). */
    private inner class Texts {
        val forceStop = settingsStrings(
            listOf("force_stop", "device_apps_app_management_force_stop"),
            listOf("Force stop", "Force Stop", "FORCE STOP")
        )
        val confirm = settingsStrings(
            // Not "Force stop" here: that would match the button we just pressed. Phone dialogs
            // are found by their standard button id instead.
            listOf("dlg_ok", "okay", "settings_ok"),
            listOf("OK", "Ok")
        )
        val clearCache = settingsStrings(
            listOf("clear_cache_btn_text", "device_apps_app_management_clear_cache"),
            listOf("Clear cache", "Clear Cache", "CLEAR CACHE")
        )
        val storage = settingsStrings(
            listOf("storage_settings_for_app", "storage_settings", "storage_label", "application_size_label"),
            listOf("Storage & cache", "Storage and cache", "Storage")
        )
    }

    private val texts by lazy { Texts() }

    private fun settingsStrings(names: List<String>, fallback: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (pkg in SETTINGS_PACKAGES) {
            try {
                val res = packageManager.getResourcesForApplication(pkg)
                for (n in names) {
                    val id = res.getIdentifier(n, "string", pkg)
                    if (id != 0) out.add(res.getString(id))
                }
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }
        return (out + fallback).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun root(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (_: Exception) {
        null
    }

    /** Every window's screen tree, the front one (e.g. a dialog) first. */
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        root()?.let { out.add(it) }
        try {
            windows?.forEach { w -> w.root?.let { r -> if (out.none { it == r }) out.add(r) } }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Walks the whole screen tree looking for matches. Done by hand because newer Settings
     * screens (e.g. Android 16 on Motorola) don't answer Android's built-in text search.
     */
    private fun walkFind(limit: Int = 50, test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 45 || out.size >= limit) return
            try {
                if (test(n)) out.add(n)
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            } catch (_: Exception) {
            }
        }
        for (r in roots()) {
            walk(r, 0)
            if (out.isNotEmpty()) break   // front window wins
        }
        return out
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()

    private fun onScreen(text: String): Boolean =
        walkFind(1) { label(it).contains(text, ignoreCase = true) }.isNotEmpty()

    /** Finds a node whose text is exactly one of [options]. */
    private fun find(options: List<String>): AccessibilityNodeInfo? {
        for (t in options) {
            walkFind(1) { label(it).equals(t, ignoreCase = true) }.firstOrNull()?.let { return it }
        }
        return null
    }

    /** The tappable thing a label belongs to (the text itself or a parent button/row). */
    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && !n.isClickable && depth < 6) {
            n = n.parent
            depth++
        }
        return n ?: node
    }

    /** Greyed-out check that works whether the text or its parent button carries the state. */
    private fun isEnabled(node: AccessibilityNodeInfo): Boolean =
        try { node.isEnabled && clickableOf(node).isEnabled } catch (_: Exception) { true }

    /**
     * The confirm button of the "Force stop?" box (phone dialog or TV confirm screen).
     * [pressed] is where the page's own Force stop button is, so it isn't mistaken for the confirm.
     */
    private fun findConfirm(pressed: android.graphics.Rect? = null): AccessibilityNodeInfo? {
        walkFind(1) { it.viewIdResourceName == "android:id/button1" }.firstOrNull()?.let { return it }
        find(texts.confirm)?.let { return it }
        // Some phones label the confirm button "Force stop" too: take one that isn't the button we pressed
        for (t in texts.forceStop) {
            for (n in walkFind(6) { label(it).equals(t, ignoreCase = true) }) {
                val b = android.graphics.Rect()
                n.getBoundsInScreen(b)
                if (pressed == null || b != pressed) return n
            }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && !n.isClickable && depth < 6) {
            n = n.parent
            depth++
        }
        return try {
            (n ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {
            false
        }
    }

    /** Polls the screen until [check] returns something (or time runs out). Gets elapsed ms. */
    private suspend fun <T> waitFor(timeoutMs: Long, check: (Long) -> T?): T? {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            val v = try {
                check(elapsed)
            } catch (_: Exception) {
                null
            }
            if (v != null) return v
            if (elapsed > timeoutMs) return null
            delay(150)
        }
    }

    // ---------- Full-screen progress card ----------

    private fun showOverlay() {
        if (overlay != null) return
        try {
            val v = LayoutInflater.from(this).inflate(R.layout.overlay_sweep, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, params)
            overlay = v
        } catch (_: Exception) {
            overlay = null
        }
    }

    private fun overlayProgress(): android.widget.ProgressBar? = overlay?.findViewById(R.id.overlayProgress)

    private fun updateOverlay(index: Int, total: Int, label: String) {
        val v = overlay ?: return
        v.post {
            v.findViewById<TextView>(R.id.overlayStatus)?.text = "Closing $index of $total"
            v.findViewById<TextView>(R.id.overlayApp)?.text = label
        }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }
}
