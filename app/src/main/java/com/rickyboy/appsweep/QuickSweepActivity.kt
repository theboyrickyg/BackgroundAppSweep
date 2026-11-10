package com.rickyboy.appsweep

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One press, no menus: closes everything, clears caches, shows the result, then disappears.
 * Launched from the "Quick Sweep" tile on the TV home screen, the phone icon shortcut,
 * or the Quick Settings tile.
 */
class QuickSweepActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick)
        val status = findViewById<TextView>(R.id.quickStatus)
        val result = findViewById<TextView>(R.id.quickResult)
        val openButton = findViewById<Button>(R.id.quickOpen)
        openButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        val sweeper = Sweeper(applicationContext)
        // Free cleans used up: open App Closer on its unlock screen instead
        if (!Unlock.canClean(this)) {
            startActivity(Intent(this, MainActivity::class.java).putExtra("show_unlock", true))
            finishAndRemoveTask()
            return
        }
        lifecycleScope.launch {
            status.text = "Connecting…"
            val state = Shell.ensureConnected(applicationContext, userAsked = false)
            // Easy mode: hand the sweep to the accessibility service and get out of the way
            if (state != Shell.Status.READY && SweepAccessibilityService.isOn()) {
                val svc = SweepAccessibilityService.instance
                val targets = withContext(Dispatchers.IO) { sweeper.scanEasy() }
                    .filter { !it.isProtected }
                    .map { it.packageName to it.label }
                if (svc != null && targets.isNotEmpty()) {
                    Unlock.useClean(applicationContext)
                    svc.sweep(targets, finish = SweepAccessibilityService.Finish.GO_HOME)
                    finishAndRemoveTask()
                    return@launch
                }
                status.text = "All clean ✓"
                result.text = "Nothing to close yet."
                result.visibility = View.VISIBLE
                delay(2500)
                finishAndRemoveTask()
                return@launch
            }
            // TV with nothing set up: the main screen does the guided closing
            if (state != Shell.Status.READY && packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                startActivity(Intent(this@QuickSweepActivity, MainActivity::class.java).putExtra("auto_sweep", true))
                finishAndRemoveTask()
                return@launch
            }
            if (state != Shell.Status.READY) {
                status.text = "Not set up yet"
                result.text = "Open App Closer to finish setting up."
                result.visibility = View.VISIBLE
                openButton.visibility = View.VISIBLE
                openButton.requestFocus()
                return@launch
            }
            status.text = "Sweeping…"
            Unlock.useClean(applicationContext)
            val r = withContext(Dispatchers.IO) {
                sweeper.sweepAll(sweeper.scan(includeSystem = false))
            }
            Unlock.addTotals(applicationContext, r.closed, r.cacheFreed)
            status.text = "All clean ✓"
            result.text = resultLine(r)
            result.visibility = View.VISIBLE
            delay(3000)
            finishAndRemoveTask()
        }
    }
}
