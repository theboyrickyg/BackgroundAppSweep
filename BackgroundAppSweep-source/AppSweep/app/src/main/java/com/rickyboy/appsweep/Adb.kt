package com.rickyboy.appsweep

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Built-in ADB connection: the app talks to the device's own debugging service, so it gets
 * the same rights as a USB/ADB connection without any helper app.
 *
 *  - TV / older devices: "Network debugging" (ADB over TCP, port 5555). The device shows an
 *    "Allow debugging?" box the first time.
 *  - Android 11+ phones (and TVs): "Wireless debugging", paired once with a 6-digit code.
 */
object Adb {

    enum class Result { CONNECTED, NEEDS_PAIRING, NOT_AVAILABLE }

    private const val PREFS = "appsweep"
    private const val KEY_WORKED = "adb_worked"
    private const val KEY_PAIRED = "adb_paired"

    @Volatile
    private var manager: Manager? = null

    // ---------- Key + certificate (identifies this app to the debugging service) ----------

    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val mKey: PrivateKey
        private val mCert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(30, TimeUnit.SECONDS) // time for the person to press "Allow"
            val keyFile = File(ctx.filesDir, "adb_key.pk8")
            val certFile = File(ctx.filesDir, "adb_cert.der")
            if (keyFile.exists() && certFile.exists()) {
                mKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                mCert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } else {
                val generator = KeyPairGenerator.getInstance("RSA")
                generator.initialize(2048)
                val pair = generator.generateKeyPair()
                val name = X500Name("CN=Background App Sweep")
                val now = Date()
                val until = Date(now.time + 3650L * 24 * 60 * 60 * 1000) // 10 years
                val builder = JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(now.time), now, until, name, pair.public
                )
                val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
                mKey = pair.private
                mCert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
                keyFile.writeBytes(mKey.encoded)
                certFile.writeBytes(mCert.encoded)
            }
        }

        override fun getPrivateKey(): PrivateKey = mKey
        override fun getCertificate(): Certificate = mCert
        override fun getDeviceName(): String = "BackgroundAppSweep"
    }

    @Synchronized
    private fun manager(ctx: Context): Manager =
        manager ?: Manager(ctx.applicationContext).also { manager = it }

    // ---------- State ----------

    fun isConnected(): Boolean = try {
        manager?.isConnected == true
    } catch (_: Exception) {
        false
    }

    /** True once a connection has worked on this device, so we can reconnect silently. */
    fun hasWorkedBefore(ctx: Context) = prefs(ctx).getBoolean(KEY_WORKED, false)

    fun hasPaired(ctx: Context) = prefs(ctx).getBoolean(KEY_PAIRED, false)

    fun developerOptionsOn(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    fun wirelessDebuggingOn(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled", 0) == 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Connecting ----------

    /** Blocking. Tries Network debugging (port 5555) first, then Wireless debugging. */
    @Synchronized
    fun connect(ctx: Context): Result {
        if (isConnected()) return Result.CONNECTED
        val m = try {
            manager(ctx)
        } catch (_: Exception) {
            return Result.NOT_AVAILABLE
        }

        // 1. Network debugging / ADB over TCP (most TVs, Fire TV, NVIDIA Shield…)
        try {
            m.connect("127.0.0.1", 5555)
        } catch (_: Exception) {
        }
        if (isConnected()) return success(ctx)

        // 2. Wireless debugging (Android 11+)
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                m.connectTls(ctx.applicationContext, 5000)
            } catch (_: AdbPairingRequiredException) {
                return Result.NEEDS_PAIRING
            } catch (_: Exception) {
            }
            if (isConnected()) return success(ctx)
            if (wirelessDebuggingOn(ctx) && !hasPaired(ctx)) return Result.NEEDS_PAIRING
        }
        return Result.NOT_AVAILABLE
    }

    private fun success(ctx: Context): Result {
        prefs(ctx).edit().putBoolean(KEY_WORKED, true).apply()
        return Result.CONNECTED
    }

    // ---------- Pairing (Wireless debugging, once per device) ----------

    /** Finds the pairing port the "Pair device with pairing code" screen is advertising. */
    fun findPairingPort(ctx: Context, timeoutMs: Long = 8000): Int {
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(ctx.applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, p ->
            if (host != null && p > 0) {
                port.set(p)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        } finally {
            mdns.stop()
        }
        return port.get()
    }

    /** Blocking. Returns null on success, or a plain-English reason it failed. */
    fun pair(ctx: Context, code: String): String? {
        if (Build.VERSION.SDK_INT < 30) return "Pairing needs Android 11 or newer."
        val digits = code.filter { it.isDigit() }
        if (digits.length != 6) return "The pairing code should be 6 digits."
        val port = findPairingPort(ctx)
        if (port <= 0) {
            return "Couldn't find the pairing screen. Keep \"Pair device with pairing code\" open and try again."
        }
        return try {
            manager(ctx).pair("127.0.0.1", port, digits)
            prefs(ctx).edit().putBoolean(KEY_PAIRED, true).apply()
            null
        } catch (e: Exception) {
            "Pairing didn't work (wrong or expired code?). Open the pairing screen again for a new code."
        }
    }

    // ---------- Running commands ----------

    fun exec(cmd: String): String {
        val m = manager ?: return ""
        val stream = try {
            m.openStream("shell:$cmd")
        } catch (_: Exception) {
            return ""
        }
        val out = ByteArrayOutputStream()
        try {
            val input = stream.openInputStream()
            val buf = ByteArray(8192)
            while (true) {
                // The library reports "command finished" as an IOException, so treat it as the end
                val n = try {
                    input.read(buf)
                } catch (_: java.io.IOException) {
                    -1
                }
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: Exception) {
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
            }
        }
        return out.toString("UTF-8")
    }

    fun disconnect() {
        try {
            manager?.disconnect()
        } catch (_: Exception) {
        }
    }
}
