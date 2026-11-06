package com.rickyboy.appsweep

import android.content.ActivityNotFoundException
import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
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
        private const val PERMISSION_REQUEST = 4242
        private const val NOTIFY_REQUEST = 4243
    }

    private lateinit var sweeper: Sweeper
    private lateinit var ramGauge: RamGaugeView
    private lateinit var ramUsedText: TextView
    private lateinit var ramFreeText: TextView
    private lateinit var appsCountText: TextView
    private lateinit var statusCard: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusButton: Button
    private lateinit var statusButton2: Button
    private lateinit var codeRow: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var pairButton: Button
    private lateinit var shizukuButton: Button
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

    private var setupJob: Job? = null
    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }

    private val onShellReady: () -> Unit = { runOnUiThread { checkConnection(userAsked = false) } }
    private val binderReceived = Shizuku.OnBinderReceivedListener { checkConnection(userAsked = false) }
    private val binderDead = Shizuku.OnBinderDeadListener {
        Shell.onShizukuDead()
        checkConnection(userAsked = false)
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> checkConnection(userAsked = false) }

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
        statusButton2 = findViewById(R.id.statusButton2)
        codeRow = findViewById(R.id.codeRow)
        codeInput = findViewById(R.id.codeInput)
        pairButton = findViewById(R.id.pairButton)
        shizukuButton = findViewById(R.id.shizukuButton)
        pairButton.setOnClickListener { pairFromApp() }
        shizukuButton.setOnClickListener { useShizuku() }
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
        checkConnection(userAsked = false)
        if (ready && !busy) refresh()
    }

    override fun onDestroy() {
        Shell.readyListeners.remove(onShellReady)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    // ---------- Connection + guided setup ----------

    private fun checkConnection(userAsked: Boolean) {
        if (Shell.isReady()) {
            onStatus(Shell.Status.READY)
            return
        }
        if (setupJob?.isActive == true) return
        if (userAsked) onStatus(Shell.Status.CONNECTING)
        setupJob = lifecycleScope.launch {
            val status = Shell.ensureConnected(applicationContext, userAsked)
            onStatus(status)
        }
    }

    private fun onStatus(status: Shell.Status) {
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
        statusButton2.visibility = View.GONE
        codeRow.visibility = View.GONE
        shizukuButton.visibility = if (!isReady && Shell.shizukuInstalled(this)) View.VISIBLE else View.GONE

        when (status) {
            Shell.Status.NEED_DEV_OPTIONS -> {
                statusText.text = if (isTv) {
                    "One-time setup (about a minute)\n\n" +
                        "Step 1 of 2: turn on Developer options.\n" +
                        "Settings → System → About → click \"Android TV OS build\" 7 times, " +
                        "until it says you're a developer. Then come back here."
                } else {
                    "One-time setup (about a minute)\n\n" +
                        "Step 1 of 3: turn on Developer options.\n" +
                        "Settings → About phone → tap \"Build number\" 7 times " +
                        "(on some phones it's under Software information). Then come back here."
                }
                statusButton.text = "Open Settings"
                statusButton.setOnClickListener { openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) }
                statusButton2.visibility = View.VISIBLE
                statusButton2.text = "Done, next step"
                statusButton2.setOnClickListener { checkConnection(userAsked = false) }
            }
            Shell.Status.NEED_DEBUGGING -> {
                statusText.text = when {
                    isTv ->
                        "Step 2 of 2: turn on debugging.\n" +
                            "Settings → System → Developer options → switch on \"Network debugging\" " +
                            "(some TVs call it \"ADB debugging\"; if yours only has \"Wireless debugging\", switch that on).\n\n" +
                            "Then press Connect. If a box asks \"Allow debugging?\", tick \"Always allow\" and press OK."
                    Adb.hasPaired(this) ->
                        "Wireless debugging is off. Android switches it off after a restart or a Wi-Fi change.\n\n" +
                            "Developer options → switch \"Wireless debugging\" back on (no new code needed), then press Connect."
                    else ->
                        "Step 2 of 3: turn on Wireless debugging.\n" +
                            "Settings → System → Developer options → switch on \"Wireless debugging\" " +
                            "(you need to be on Wi-Fi). Tap Allow if it asks. Then come back and press Connect."
                }
                statusButton.text = "Open Developer options"
                statusButton.setOnClickListener { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                statusButton2.visibility = View.VISIBLE
                statusButton2.text = "Connect"
                statusButton2.setOnClickListener { checkConnection(userAsked = true) }
            }
            Shell.Status.NEED_PAIRING -> {
                statusText.text = if (isTv) {
                    "Last step: pair the app once.\n" +
                        "Developer options → Wireless debugging → \"Pair device with pairing code\". " +
                        "Write down the 6-digit code, press HOME on the remote (not Back), " +
                        "open Background App Sweep again, type the code below and press Pair."
                } else {
                    "Step 3 of 3: pair the app once.\n" +
                        "1. Press \"Start pairing\" below.\n" +
                        "2. In Wireless debugging, tap \"Pair device with pairing code\".\n" +
                        "3. Pull down your notifications and type the 6-digit code into the " +
                        "Background App Sweep notification. Keep the code on screen while you do this.\n" +
                        "4. Come back here when it says Paired ✓."
                }
                codeRow.visibility = View.VISIBLE
                if (isTv) {
                    statusButton.text = "Open Developer options"
                    statusButton.setOnClickListener { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
                } else {
                    statusButton.text = "Start pairing"
                    statusButton.setOnClickListener { startPhonePairing() }
                }
                statusButton2.visibility = View.VISIBLE
                statusButton2.text = "Connect"
                statusButton2.setOnClickListener { checkConnection(userAsked = true) }
            }
            Shell.Status.CONNECTING -> {
                statusText.text = "Connecting…\n\nIf a box pops up asking \"Allow debugging?\", " +
                    "tick \"Always allow\" and press OK."
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

    private fun startPhonePairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFY_REQUEST)
            return
        }
        PairingNotification.showCodeBox(applicationContext)
        openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        toast("Now tap Wireless debugging → Pair device with pairing code")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFY_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startPhonePairing()
            } else {
                toast("No problem. Type the code into the box here instead (use split screen if you can).")
            }
        }
    }

    /** Pairing with the code typed into the app itself (TV, or phone in split screen). */
    private fun pairFromApp() {
        val code = codeInput.text.toString()
        pairButton.isEnabled = false
        pairButton.text = "Pairing…"
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { Adb.pair(applicationContext, code) }
            pairButton.isEnabled = true
            pairButton.text = "Pair"
            if (error == null) {
                toast("Paired ✓ Connecting…")
                checkConnection(userAsked = true)
            } else {
                toast(error)
            }
        }
    }

    private fun useShizuku() {
        when {
            !Shell.shizukuRunning() -> openShizuku()
            !Shell.shizukuUsable() -> try {
                Shizuku.requestPermission(PERMISSION_REQUEST)
            } catch (_: Throwable) {
                openShizuku()
            }
            else -> checkConnection(userAsked = false)
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                toast("Open the Settings app")
            }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

private class Action(val label: String, val run: () -> Unit)

fun resultLine(r: SweepResult): String =
    "Closed ${r.closed} app${if (r.closed == 1) "" else "s"} · freed ${AppAdapter.formatBytes(r.ramFreed)} RAM · " +
        "cleared ${AppAdapter.formatBytes(r.cacheFreed)} of cache"
