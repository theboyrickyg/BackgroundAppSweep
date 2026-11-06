package com.rickyboy.appsweep

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
        private const val PERMISSION_REQUEST = 4242
    }

    private lateinit var sweeper: Sweeper
    private lateinit var ramGauge: RamGaugeView
    private lateinit var ramUsedText: TextView
    private lateinit var ramFreeText: TextView
    private lateinit var appsCountText: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusButton: Button
    private lateinit var sweepButton: Button
    private lateinit var resultText: TextView
    private lateinit var statsText: TextView
    private lateinit var listHeader: TextView
    private lateinit var adapter: AppAdapter

    private var busy = false
    private var ready = false
    private var currentApps: List<RunningApp> = emptyList()
    private var scanJob: Job? = null
    private var usageGranted = false
    private var lastTotalCache = -1L
    private val prefs by lazy { getSharedPreferences("appsweep", Context.MODE_PRIVATE) }

    private val onShellReady: () -> Unit = { runOnUiThread { checkShizuku() } }
    private val binderReceived = Shizuku.OnBinderReceivedListener { checkShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        Shell.onBinderDead()
        checkShizuku()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> checkShizuku() }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sweeper = Sweeper(applicationContext)

        ramGauge = findViewById(R.id.ramGauge)
        ramUsedText = findViewById(R.id.ramUsedText)
        ramFreeText = findViewById(R.id.ramFreeText)
        appsCountText = findViewById(R.id.appsCountText)
        statusCard = findViewById(R.id.statusCard)
        statusText = findViewById(R.id.statusText)
        statusButton = findViewById(R.id.statusButton)
        sweepButton = findViewById(R.id.sweepButton)
        resultText = findViewById(R.id.resultText)
        statsText = findViewById(R.id.statsText)
        listHeader = findViewById(R.id.listHeader)

        adapter = AppAdapter(onTap = { showOptions(it) }, onHold = { quickClose(it) })
        val list = findViewById<RecyclerView>(R.id.appList)
        val widthDp = resources.configuration.screenWidthDp
        val columns = when {
            widthDp >= 900 -> 3
            widthDp >= 600 -> 2
            else -> 1
        }
        list.layoutManager = GridLayoutManager(this, columns)
        list.adapter = adapter
        list.itemAnimator = null // smooth live updates, keeps TV remote focus in place

        sweepButton.setOnClickListener { sweepAll() }

        Shell.readyListeners.add(onShellReady)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        updateMemory()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Gauge every 2 s, app list every 6 s — no refresh button needed
                var tick = 0
                while (true) {
                    updateMemory()
                    if (tick % 3 == 2 && ready && !busy) refresh(silent = true)
                    tick++
                    delay(2000)
                }
            }
        }
        sweepButton.requestFocus() // TV remote starts on the big button
    }

    override fun onResume() {
        super.onResume()
        updateMemory()
        checkShizuku()
        if (ready && !busy) refresh()
    }

    override fun onDestroy() {
        Shell.readyListeners.remove(onShellReady)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    // ---------- Shizuku status ----------

    private fun checkShizuku() {
        val status = Shell.status(this)
        val wasReady = ready
        ready = status == Shell.Status.READY
        showStatus(status)
        if (ready && (!wasReady || currentApps.isEmpty())) refresh()
    }

    private fun showStatus(status: Shell.Status) {
        val isReady = status == Shell.Status.READY
        statusCard.visibility = if (isReady) View.GONE else View.VISIBLE
        sweepButton.isEnabled = isReady && !busy
        sweepButton.alpha = if (isReady) 1f else 0.4f
        statusButton.visibility = View.VISIBLE

        when (status) {
            Shell.Status.NOT_INSTALLED -> {
                statusText.text = "Background App Sweep needs the free Shizuku app to close other apps and clear caches. " +
                    "Install Shizuku, start it, then come back here."
                statusButton.text = "Get Shizuku"
                statusButton.setOnClickListener { openUrl(SHIZUKU_DOWNLOAD) }
            }
            Shell.Status.NOT_RUNNING -> {
                statusText.text = "Shizuku is installed but not running. Open Shizuku and start it " +
                    "(it needs starting again after every reboot)."
                statusButton.text = "Open Shizuku"
                statusButton.setOnClickListener { openShizuku() }
            }
            Shell.Status.TOO_OLD -> {
                statusText.text = "Your Shizuku is too old. Please update it to the latest version."
                statusButton.text = "Update Shizuku"
                statusButton.setOnClickListener { openUrl(SHIZUKU_DOWNLOAD) }
            }
            Shell.Status.NEED_PERMISSION -> {
                statusText.text = "Shizuku is running. Allow Background App Sweep to use it."
                statusButton.text = "Allow"
                statusButton.setOnClickListener { Shizuku.requestPermission(PERMISSION_REQUEST) }
            }
            Shell.Status.DENIED -> {
                statusText.text = "Background App Sweep was refused access. Open Shizuku → Authorized applications " +
                    "and switch it on."
                statusButton.text = "Open Shizuku"
                statusButton.setOnClickListener { openShizuku() }
            }
            Shell.Status.CONNECTING -> {
                statusText.text = "Connecting to Shizuku…"
                statusButton.visibility = View.GONE
            }
            Shell.Status.READY -> Unit
        }
        if (!isReady) {
            currentApps = emptyList()
            adapter.items = emptyList()
            listHeader.text = "Running apps"
            statsText.text = ""
            appsCountText.text = "Launched apps: –"
            if (statusButton.visibility == View.VISIBLE) statusButton.requestFocus()
        }
    }

    // ---------- Scanning ----------

    private fun refresh(silent: Boolean = false) {
        if (!ready) return
        if (silent && scanJob?.isActive == true) return
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            if (currentApps.isEmpty()) listHeader.text = "Scanning…"
            val apps = withContext(Dispatchers.IO) {
                if (!usageGranted) {
                    sweeper.grantUsageAccess()
                    usageGranted = true
                }
                sweeper.scan(includeSystem = false)
            }
            showList(apps)
            updateMemory()
            // Total cache across all installed apps takes a moment, so only work it out
            // on a full refresh (start-up, after a sweep), not on the quiet 6-second updates
            if (!silent || lastTotalCache < 0) {
                lastTotalCache = withContext(Dispatchers.IO) { sweeper.totalCache() }
            }
            updateStats(apps, lastTotalCache)
        }
    }

    private fun showList(apps: List<RunningApp>) {
        currentApps = apps.sortedByDescending { it.memoryKb } // biggest RAM users first
        adapter.items = currentApps
        val userApps = currentApps.count { !it.isSystem }
        appsCountText.text = "Launched apps: $userApps"
        listHeader.text = "Running apps ($userApps)"
    }

    private fun updateStats(apps: List<RunningApp>, totalCache: Long) {
        val appRam = apps.filter { !it.isSystem }.sumOf { it.memoryKb }
        val parts = mutableListOf("Apps using ${AppAdapter.formatKb(appRam)} RAM")
        if (totalCache >= 0) parts.add("${AppAdapter.formatBytes(totalCache)} cache can be cleared")
        statsText.text = parts.joinToString("  ·  ")
    }

    // ---------- Actions ----------

    private fun sweepAll() {
        if (busy || !ready) return
        busy = true
        sweepButton.isEnabled = false
        sweepButton.setBackgroundResource(R.drawable.bg_sweep_button)
        sweepButton.text = "SWEEPING…"
        resultText.visibility = View.GONE
        val apps = currentApps

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { sweeper.sweepAll(apps) }
            resultText.text = resultLine(result)
            resultText.visibility = View.VISIBLE
            // Turn the button green to show it's finished
            sweepButton.setBackgroundResource(R.drawable.bg_sweep_done)
            sweepButton.text = "✓  ALL CLEAN"
            busy = false
            sweepButton.isEnabled = true
            refresh()
            delay(5000)
            if (!busy) {
                sweepButton.setBackgroundResource(R.drawable.bg_sweep_button)
                sweepButton.text = getString(R.string.sweep_button)
            }
        }
    }

    private fun showOptions(app: RunningApp) {
        val actions = mutableListOf<Action>()
        if (!app.isProtected) actions.add(Action("Close it now") { closeApp(app) })
        if (Sweeper.canClearSingleCache && app.cacheBytes != 0L) {
            val size = if (app.cacheBytes > 0) " (${AppAdapter.formatBytes(app.cacheBytes)})" else ""
            actions.add(Action("Clear its cache$size") { clearCache(app) })
        }
        if (Sweeper.canBlockBackground) {
            if (app.backgroundBlocked) {
                actions.add(Action("Allow background running again") { setBlocked(app, false) })
            } else {
                actions.add(Action("Block background running (stops it restarting itself)") { setBlocked(app, true) })
            }
        }
        if (app.isProtected) {
            actions.add(Action("Remove protection") { setProtected(app, false) })
        } else if (!app.isSystem) {
            actions.add(Action("Protect it (Close All will skip it)") { setProtected(app, true) })
        }
        actions.add(Action("Open Android's App info page") { openAppInfo(app) })

        val title = if (app.isSystem) "${app.label}  ·  system app, close with care" else app.label
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.label }.toTypedArray()) { _, which -> actions[which].run() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun quickClose(app: RunningApp) {
        if (app.isProtected) {
            toast("${app.label} is protected. Tap it to change that.")
            return
        }
        closeApp(app)
    }

    private fun closeApp(app: RunningApp) {
        lifecycleScope.launch {
            val before = sweeper.availableRam()
            withContext(Dispatchers.IO) {
                sweeper.forceStop(app.packageName)
                Thread.sleep(600)
            }
            val freed = (sweeper.availableRam() - before).coerceAtLeast(0)
            toast("Closed ${app.label} · freed ${AppAdapter.formatBytes(freed)} RAM")
            refresh()
        }
    }

    private fun clearCache(app: RunningApp) {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { sweeper.clearAppCache(app.packageName) }
            toast("Cleared ${AppAdapter.formatBytes(freed)} of cache from ${app.label}")
            refresh()
        }
    }

    private fun setBlocked(app: RunningApp, block: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { sweeper.setBackgroundBlocked(app.packageName, block) }
            toast(
                if (block) "${app.label} can no longer run in the background. It still opens normally."
                else "${app.label} can run in the background again."
            )
            refresh()
        }
    }

    private fun setProtected(app: RunningApp, protect: Boolean) {
        sweeper.setProtected(app.packageName, protect)
        toast(if (protect) "${app.label} won't be closed by Close All" else "${app.label} is no longer protected")
        showList(currentApps.map {
            if (it.packageName == app.packageName) it.copy(isProtected = protect) else it
        })
    }

    // ---------- Helpers ----------

    private fun updateMemory() {
        val info = sweeper.memoryInfo()
        if (info.totalMem <= 0) return
        val used = info.totalMem - info.availMem
        ramGauge.setPercent(used * 100f / info.totalMem)
        ramUsedText.text = "${gb(used)} / ${gb(info.totalMem)}"
        ramFreeText.text = "${gb(info.availMem)} available"
    }

    /** 4.25 GB style */
    private fun gb(bytes: Long): String {
        val v = bytes / 1024.0 / 1024.0 / 1024.0
        return String.format("%.2f", v).trimEnd('0').trimEnd('.') + " GB"
    }

    private fun openAppInfo(app: RunningApp) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")))
        } catch (_: ActivityNotFoundException) {
            toast("This device has no App info page")
        }
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PACKAGE)
            ?: packageManager.getLeanbackLaunchIntentForPackage(Shell.SHIZUKU_PACKAGE)
        if (intent != null) startActivity(intent) else toast("Couldn't open Shizuku")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("No web browser found. Get Shizuku from $url (or use Downloader on TV)")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

private class Action(val label: String, val run: () -> Unit)

fun resultLine(r: SweepResult): String =
    "Closed ${r.closed} app${if (r.closed == 1) "" else "s"} · freed ${AppAdapter.formatBytes(r.ramFreed)} RAM · " +
        "cleared ${AppAdapter.formatBytes(r.cacheFreed)} of cache"
