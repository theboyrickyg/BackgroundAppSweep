package com.rickyboy.appsweep

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Phone pairing: a notification with a reply box, so the person can type the 6-digit code
 * while Android's "Pair device with pairing code" screen stays open.
 */
object PairingNotification {
    private const val CHANNEL = "pairing"
    private const val ID = 7001
    const val KEY_CODE = "pairing_code"

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Setup", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun showCodeBox(ctx: Context, message: String = "Type the 6-digit Wi-Fi pairing code shown in Settings") {
        channel(ctx)
        val reply = RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build()
        val flags = if (Build.VERSION.SDK_INT >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, PairingReceiver::class.java), flags
        )
        val action = NotificationCompat.Action.Builder(R.drawable.ic_tile, "Enter code", pi)
            .addRemoteInput(reply)
            .build()
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("App Closer setup")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .addAction(action)
            .build()
        post(ctx, n)
    }

    fun showMessage(ctx: Context, title: String, text: String) {
        channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        post(ctx, n)
    }

    @Suppress("MissingPermission")
    private fun post(ctx: Context, n: android.app.Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(ID, n)
        } catch (_: SecurityException) {
        }
    }
}

class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingNotification.KEY_CODE)?.toString() ?: return
        val app = context.applicationContext
        PairingNotification.showMessage(app, "Pairing…", "Hang on a moment.")
        val pending = goAsync()
        Thread {
            val error = try {
                Adb.pair(app, code)
            } catch (e: Exception) {
                "Pairing didn't work. Try again."
            }
            if (error == null) {
                PairingNotification.showMessage(
                    app, "Paired ✓",
                    "All set. Tap here to go back to App Closer."
                )
            } else {
                PairingNotification.showCodeBox(app, error)
            }
            pending.finish()
        }.start()
    }
}
