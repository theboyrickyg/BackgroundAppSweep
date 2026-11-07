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
    private lateinit var cleanGauge: CleanGaugeView
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
    private lateinit var linkButton: Button
    private lateinit var sweepButton: Button
    private lateinit var resultText: TextView
    private lateinit var resultRow: LinearLayout
    private lateinit var allCleanView: View
    private lateinit var closeAppButton: Button
    private var sweptThisSession = false
    private var awaitingStep = -1   // which Easy step to show after coming back from Settings
    private lateinit var statClosed: TextView
    private lateinit var statRam: TextView
    private lateinit var statCache: TextView
    private lateinit var statsText: TextView
    private lateinit var listHeader: TextView
    private lateinit var listHint: TextView
    private lateinit var adapter: AppAdapter

    private var busy = false
    private var ready = false
    private var currentApps: List<RunningApp> = emptyList()
    private var scanJob: Job? = null
    private var usageGranted = false
    private var lastTotalCache = -1L
    private val prefs by lazy { getSharedPreferences("appsweep", Context.MODE_PRIVATE) }

    private var setupJob: Job? = null

    /** Easy mode = no debugging connection, but the accessibility switch is on. */
    private val easyMode: Boolean get() = !Shell.isReady() && SweepAccessibilityService.isOn()
    private var setupChoice: String?
        get() = prefs.getString("setup_choice", null)
        set(v) { prefs.edit().putString("setup_choice", v).apply() }
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
        cleanGauge = findViewById(R.id.cleanGauge)
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
        linkButton = findViewById(R.id.linkButton)
        pairButton.setOnClickListener { pairFromApp() }
        shizukuButton.setOnClickListener { useShizuku() }
        sweepButton = findViewById(R.id.sweepButton)
        resultText = findViewById(R.id.resultText)
        resultRow = findViewById(R.id.resultRow)
        allCleanView = findViewById(R.id.allCleanView)
        closeAppButton = findViewById(R.id.closeAppButton)
        closeAppButton.setOnClickListener { finishAndRemoveTask() }
        statClosed = findViewById(R.id.statClosed)
        statRam = findViewById(R.id.statRam)
        statCache = findViewById(R.id.statCache)
        statsText = findViewById(R.id.statsText)
        listHeader = findViewById(R.id.listHeader)
        listHint = findViewById(R.id.listHint)

        adapter = AppAdapter(onTap = { showOptions(it) }, onHold = { quickClose(it) }, onAutoStart = { setBlocked(it, !it.backgroundBlocked) })
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EasySweep.state.collect { st ->
                    if (st.running) cleanGauge.setProgress(st.percent.toFloat())
                    val r = st.result
                    if (r != null) {
                        EasySweep.state.value = st.copy(result = null, report = "")
                        cleanGauge.finish()
                        showSweepDone(r)
                        if (r.closed == 0 && st.report.isNotBlank()) showEasyReport(st.report)
                    }
                }
            }
        }
        sweepButton.requestFocus() // TV remote starts on the big button
    }

    override fun onResume() {
        super.onResume()
        updateMemory()
        if (awaitingStep > 0 && setupChoice == "easy" && !SweepAccessibilityService.isEnabledInSettings(this)) {
            easyStep = awaitingStep
        }
        awaitingStep = -1
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
        // Easy mode is on and Pro has never been set up: nothing to connect
        if (!userAsked && SweepAccessibilityService.isOn() && !Adb.hasWorkedBefore(this) && !Shell.shizukuUsable()) {
            onStatus(Shell.Status.READY)
            return
        }
        if (userAsked) onStatus(Shell.Status.CONNECTING)
        setupJob = lifecycleScope.launch {
            var status = Shell.ensureConnected(applicationContext, userAsked)
            if (status != Shell.Status.READY && !userAsked && SweepAccessibilityService.isOn()) {
                status = Shell.Status.READY // fall back to Easy mode
            }
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

        linkButton.visibility = View.GONE
        // People who already set up Pro (before Easy existed) carry on with Pro
        val choice = setupChoice ?: if (Adb.hasWorkedBefore(this) || Adb.hasPaired(this)) "pro" else null
        if (!isReady && status != Shell.Status.CONNECTING && choice != "pro") {
            if (choice == "easy") showEasySetup() else showChoice()
            clearList()
            return
        }
        if (!isReady) {
            linkButton.visibility = View.VISIBLE
            linkButton.text = "← Back to setup choice"
            linkButton.setOnClickListener {
                setupChoice = null
                showChoice()
            }
        }

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
                        "open App Closer again, type the code below and press Pair."
                } else {
                    "Step 3 of 3: pair the app once.\n" +
                        "1. Press \"Start pairing\" below.\n" +
                        "2. In Wireless debugging, tap \"Pair device with pairing code\".\n" +
                        "3. Pull down your notifications and type the 6-digit code into the " +
                        "App Closer notification. Keep the code on screen while you do this.\n" +
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
        if (!isReady) clearList()
    }

    private fun clearList() {
        currentApps = emptyList()
        adapter.items = emptyList()
        listHeader.text = "Running apps"
        statsText.text = ""
        appsCountText.text = "Launched apps: –"
        if (statusButton.visibility == View.VISIBLE) statusButton.requestFocus()
    }

    /** First screen: Easy (one switch) or Pro (debugging). */
    private fun showChoice() {
        statusCard.visibility = View.VISIBLE
        statusText.text = "Welcome! Choose how to set up App Closer:\n\n" +
            "EASY (recommended)\nTwo quick steps in Settings, shown one at a time. No codes, nothing technical. " +
            "Closing apps takes a few seconds each, with a progress screen while it works.\n\n" +
            "PRO\nFor confident users. Uses Developer options. Closes everything instantly and silently, " +
            "shows RAM and CPU for every app, clears all caches at once and can block apps auto-starting."
        statusButton.visibility = View.VISIBLE
        statusButton.text = "Easy setup"
        statusButton.setOnClickListener {
            setupChoice = "easy"
            easyStep = 1
            showEasySetup()
        }
        statusButton2.visibility = View.VISIBLE
        statusButton2.text = "Pro setup"
        statusButton2.setOnClickListener {
            setupChoice = "pro"
            checkConnection(userAsked = false)
        }
        codeRow.visibility = View.GONE
        linkButton.visibility = View.GONE
        statusButton.requestFocus()
    }

    /** Easy setup: switch on the accessibility service. */
    /**
     * Android 13+ blocks Accessibility for apps installed from a link until "Allow restricted
     * settings" is turned on. true = allowed, false = still blocked, null = can't tell on this phone.
     */
    private fun restrictedSettingsAllowed(): Boolean? {
        if (isTv || Build.VERSION.SDK_INT < 33) return true
        if (SweepAccessibilityService.isEnabledInSettings(this)) return true
        return try {
            val ops = getSystemService(android.app.AppOpsManager::class.java)
            val mode = ops.unsafeCheckOpNoThrow(
                "android:access_restricted_settings", applicationInfo.uid, packageName
            )
            mode != android.app.AppOpsManager.MODE_ERRORED && mode != android.app.AppOpsManager.MODE_IGNORED
        } catch (_: Exception) {
            null
        }
    }

    private var easyStep: Int
        get() = prefs.getInt("easy_step", 1)
        set(v) { prefs.edit().putInt("easy_step", v).apply() }

    private fun goEasyStep(step: Int) {
        easyStep = step
        showEasySetup()
    }

    /**
     * Easy setup, one step at a time. On Android 13+ Android only offers "Allow restricted
     * settings" (the ⋮ menu) AFTER you've tried to switch the app on once, so the steps follow
     * that order: try → unlock → switch on.
     */
    private fun showEasySetup() {
        statusCard.visibility = View.VISIBLE
        codeRow.visibility = View.GONE
        statusButton.visibility = View.VISIBLE
        statusButton2.visibility = View.GONE
        linkButton.visibility = View.VISIBLE

        if (SweepAccessibilityService.isEnabledInSettings(this) && !SweepAccessibilityService.isOn()) {
            statusText.text = "Nearly there… starting up.\n\n" +
                "If this doesn't change in a few seconds, switch App Closer off and on again in Accessibility."
            statusButton.visibility = View.GONE
            linkButton.visibility = View.GONE
            return
        }

        val needsUnlock = !isTv && Build.VERSION.SDK_INT >= 33
        if (needsUnlock && restrictedSettingsAllowed() == true && easyStep < 3) easyStep = 3
        val step = if (needsUnlock) easyStep.coerceIn(1, 3) else 3

        when (step) {
            1 -> {
                statusText.text = "EASY SETUP · STEP 1 OF 3\nTry to switch on App Closer\n\n" +
                    "Android blocks this the first time. That's expected, and it unlocks the next step.\n\n" +
                    "1. Tap \"Go to Accessibility\" below.\n" +
                    "2. Tap App Closer (under Downloaded apps).\n" +
                    "3. A \"Restricted setting\" message appears. Tap OK.\n" +
                    "4. Press Back to return here."
                statusButton.text = "Go to Accessibility"
                statusButton.setOnClickListener {
                    awaitingStep = 2
                    openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                statusButton2.visibility = View.VISIBLE
                statusButton2.text = "Next step →"
                statusButton2.setOnClickListener { goEasyStep(2) }
                linkButton.text = "← Back to setup choice"
                linkButton.setOnClickListener {
                    setupChoice = null
                    showChoice()
                }
            }
            2 -> {
                statusText.text = "EASY SETUP · STEP 2 OF 3\nAllow restricted settings\n\n" +
                    "1. Tap \"Open App info\" below.\n" +
                    "2. Tap the three dots ⋮ in the TOP RIGHT corner.\n" +
                    "3. Tap \"Allow restricted settings\".\n" +
                    "4. Enter your PIN or fingerprint if asked.\n" +
                    "5. Press Back to return here.\n\n" +
                    "No three dots? Go back and do step 1 first."
                statusButton.text = "Open App info"
                statusButton.setOnClickListener {
                    awaitingStep = 3
                    openSettingsUri(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")
                }
                statusButton2.visibility = View.VISIBLE
                statusButton2.text = "Next step →"
                statusButton2.setOnClickListener { goEasyStep(3) }
                linkButton.text = "← Back to step 1"
                linkButton.setOnClickListener { goEasyStep(1) }
            }
            else -> {
                statusText.text = (if (needsUnlock) "EASY SETUP · STEP 3 OF 3\n" else "EASY SETUP\n") +
                    "Switch on App Closer\n\n" +
                    "1. Tap \"Go to Accessibility\" below.\n" +
                    (if (isTv) "2. Choose App Closer (under Services or Downloaded services).\n" +
                        "3. Switch it on and choose OK.\n"
                    else "2. Tap App Closer (under Downloaded apps).\n" +
                        "3. Turn on \"Use App Closer\" and tap Allow.\n") +
                    "4. Press Back to return here. You're done!"
                statusButton.text = "Go to Accessibility"
                statusButton.setOnClickListener {
                    awaitingStep = 0
                    openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                }
                if (needsUnlock) {
                    linkButton.text = "← Back to step 2"
                    linkButton.setOnClickListener { goEasyStep(2) }
                } else {
                    linkButton.text = "← Back to setup choice"
                    linkButton.setOnClickListener {
                        setupChoice = null
                        showChoice()
                    }
                }
            }
        }
        statusButton.requestFocus()
    }



    private fun openSettingsUri(action: String, uri: String) {
        try {
            startActivity(Intent(action, Uri.parse(uri)))
        } catch (_: ActivityNotFoundException) {
            openSettings(Settings.ACTION_SETTINGS)
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
            val easy = easyMode
            val apps = withContext(Dispatchers.IO) {
                if (easy) {
                    sweeper.scanEasy()
                } else {
                    if (!usageGranted) {
                        sweeper.grantUsageAccess()
                        usageGranted = true
                    }
                    sweeper.scan(includeSystem = false)
                }
            }
            adapter.easyMode = easy
            showList(apps)
            updateMemory()
            if (easy) {
                statsText.text = "Easy mode · apps you've opened since your last sweep"
                return@launch
            }
            // Total cache across all installed apps takes a moment, so only work it out
            // on a full refresh (start-up, after a sweep), not on the quiet 6-second updates
            if (!silent || lastTotalCache < 0) {
                lastTotalCache = withContext(Dispatchers.IO) { sweeper.totalCache() }
            }
            updateStats(apps, lastTotalCache)
        }
    }

    private fun showList(apps: List<RunningApp>) {
        currentApps = if (easyMode) {
            apps.sortedByDescending { it.openedAt }          // most recently opened first
        } else {
            apps.sortedByDescending { it.memoryKb }          // biggest RAM users first
        }
        adapter.items = currentApps
        val userApps = currentApps.count { !it.isSystem && it.isRunning }
        appsCountText.text = colored("Launched apps: ", 0, "$userApps", RED)
        updateCloseAppButton(userApps)
        if (easyMode) {
            listHeader.text = colored("Opened apps (", 0, "$userApps", RED, ")")
            listHint.text = if (userApps == 0) {
                "Nothing open right now. Apps you open will show up here, ready for your next sweep."
            } else {
                "Most recently opened first · Tap an app for options · Hold to close it"
            }
        } else {
            listHeader.text = colored("Running apps (", 0, "$userApps", RED, ")")
            listHint.text = if (userApps == 0) {
                "No apps running in the background. All clean!"
            } else {
                "Biggest RAM users first · Tap an app for options · Hold to close it"
            }
        }
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
        val apps = currentApps
        if (easyMode) {
            val svc = SweepAccessibilityService.instance ?: return
            val targets = apps.filter { it.isRunning && !it.isProtected }
            if (targets.isEmpty()) {
                toast("Nothing to close yet. Apps you open will show up here.")
                return
            }
            startSweepUi()
            if (!svc.sweep(targets.map { it.packageName to it.label })) endSweepUi()
            return
        }
        startSweepUi()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                sweeper.sweepAll(apps) { pct -> runOnUiThread { cleanGauge.setProgress(pct.toFloat()) } }
            }
            cleanGauge.finish()
            showSweepDone(result)
        }
    }

    private fun startSweepUi() {
        busy = true
        closeAppButton.visibility = View.GONE
        allCleanView.animate().cancel()
        allCleanView.visibility = View.GONE
        sweepButton.visibility = View.VISIBLE
        sweepButton.isEnabled = false
        sweepButton.setBackgroundResource(R.drawable.bg_sweep_button)
        sweepButton.text = "SWEEPING…"
        resultText.visibility = View.GONE
        resultRow.visibility = View.GONE
        cleanGauge.startCleaning()
    }

    private fun endSweepUi() {
        busy = false
        sweepButton.isEnabled = true
        sweepButton.text = getString(R.string.sweep_button)
        cleanGauge.reset()
    }

    /** Easy mode couldn't press the buttons on this phone: show what it saw so it can be fixed. */
    private fun showEasyReport(report: String) {
        AlertDialog.Builder(this)
            .setTitle("Easy mode needs a tweak for this phone")
            .setMessage(
                "It couldn't find or press the Force stop buttons on this phone's Settings screens. " +
                    "Copy this report and send it to the developer so it can be fixed:\n\n" + report.take(1500)
            )
            .setPositiveButton("Copy report") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Easy mode report", report))
                toast("Report copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Green button + "Closed 6 apps · freed …", then back to normal after 5 seconds. */
    private fun showSweepDone(result: SweepResult) {
        statClosed.text = result.closed.toString()
        statRam.text = AppAdapter.formatBytes(result.ramFreed)
        statCache.text = AppAdapter.formatBytes(result.cacheFreed)
        resultRow.visibility = View.VISIBLE
        busy = false
        sweepButton.isEnabled = true
        val worked = result.closed > 0 || result.cacheFreed > 0
        sweptThisSession = true
        if (worked) {
            // The happy ram pops in where the button was
            resultText.visibility = View.GONE
            sweepButton.visibility = View.INVISIBLE   // stays in place for when it comes back
            allCleanView.visibility = View.VISIBLE
            allCleanView.scaleX = 0.6f
            allCleanView.scaleY = 0.6f
            allCleanView.alpha = 0f
            allCleanView.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(450).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
        } else {
            resultText.text = if (easyMode) {
                "Nothing was closed. Easy mode couldn't press the buttons on this phone (see the report)."
            } else {
                "Nothing needed closing. Everything was already clean."
            }
            resultText.visibility = View.VISIBLE
            sweepButton.setBackgroundResource(R.drawable.bg_sweep_button)
            sweepButton.text = getString(R.string.sweep_button)
            cleanGauge.reset()
        }
        refresh()
        lifecycleScope.launch {
            delay(15000)   // the happy ram stays up for 15 seconds
            if (!busy) {
                showSweepButton()
                cleanGauge.reset()
            }
        }
    }

    /** "Close this app" only once a sweep has finished and there's nothing left to clean. */
    private fun updateCloseAppButton(openApps: Int = currentApps.count { !it.isSystem && it.isRunning }) {
        closeAppButton.visibility = if (sweptThisSession && !busy && openApps == 0) View.VISIBLE else View.GONE
    }

    /** Back to the normal pink button (after the happy ram has had its moment). */
    private fun showSweepButton() {
        sweepButton.setBackgroundResource(R.drawable.bg_sweep_button)
        sweepButton.text = getString(R.string.sweep_button)
        if (allCleanView.visibility == View.VISIBLE) {
            allCleanView.animate().alpha(0f).setDuration(250).withEndAction {
                allCleanView.visibility = View.GONE
                sweepButton.visibility = View.VISIBLE
                sweepButton.requestFocus()
            }.start()
        } else {
            sweepButton.visibility = View.VISIBLE
        }
    }


    private fun showOptions(app: RunningApp) {
        val actions = mutableListOf<Action>()
        if (!app.isProtected && app.isRunning) actions.add(Action("Close it now") { closeApp(app) })
        if ((easyMode || Sweeper.canClearSingleCache) && app.cacheBytes != 0L) {
            val size = if (app.cacheBytes > 0) " (${AppAdapter.formatBytes(app.cacheBytes)})" else ""
            actions.add(Action("Clear its cache$size") { clearCache(app) })
        }
        if (Sweeper.canBlockBackground && !easyMode) {
            if (app.backgroundBlocked) {
                actions.add(Action("Let it auto-start again") { setBlocked(app, false) })
            } else {
                actions.add(Action("Stop it auto-starting") { setBlocked(app, true) })
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
        if (!app.isRunning) return
        if (app.isProtected) {
            toast("${app.label} is protected. Tap it to change that.")
            return
        }
        closeApp(app)
    }

    private fun closeApp(app: RunningApp) {
        if (easyMode) {
            val svc = SweepAccessibilityService.instance ?: return
            startSweepUi()
            if (!svc.sweep(listOf(app.packageName to app.label), forceStop = true, clearCache = false)) endSweepUi()
            return
        }
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
        if (easyMode) {
            val svc = SweepAccessibilityService.instance ?: return
            startSweepUi()
            if (!svc.sweep(listOf(app.packageName to app.label), forceStop = false, clearCache = true)) endSweepUi()
            return
        }
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { sweeper.clearAppCache(app.packageName) }
            toast("Cleared ${AppAdapter.formatBytes(freed)} of cache from ${app.label}")
            refresh()
        }
    }

    private fun setBlocked(app: RunningApp, block: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { sweeper.setAutoStartBlocked(app.packageName, block) }
            toast(
                if (block) "${app.label} won't start by itself any more. It still opens when you tap it."
                else "${app.label} can start by itself again."
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
        // Used = red, available = green, everything else white
        ramUsedText.text = colored(gb(used), RED, " / ${gb(info.totalMem)}")
        ramFreeText.text = colored(gb(info.availMem), GREEN, " available")
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

private const val RED = 0xFFE53935.toInt()
private const val GREEN = 0xFF00E676.toInt()

/** Builds text where some parts are coloured: colored("4.97 GB", RED, " / 7.26 GB"). Colour 0 = white. */
private fun colored(vararg parts: Any): CharSequence {
    val sb = android.text.SpannableStringBuilder()
    var i = 0
    while (i < parts.size) {
        val text = parts[i].toString()
        val color = if (i + 1 < parts.size && parts[i + 1] is Int) parts[i + 1] as Int else 0
        val start = sb.length
        sb.append(text)
        if (color != 0) {
            sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        i += if (i + 1 < parts.size && parts[i + 1] is Int) 2 else 1
    }
    return sb
}

private class Action(val label: String, val run: () -> Unit)

fun resultLine(r: SweepResult): String =
    "Closed ${r.closed} app${if (r.closed == 1) "" else "s"} · freed ${AppAdapter.formatBytes(r.ramFreed)} RAM · " +
        "cleared ${AppAdapter.formatBytes(r.cacheFreed)} of cache"
