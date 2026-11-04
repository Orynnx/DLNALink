package com.orynnx.dlnalink.tvbridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.net.Uri
import android.widget.Button
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.net.NetworkInterface
import java.net.Inet4Address
import java.security.SecureRandom

internal object Pairing {
    @Synchronized
    fun token(context: Context): String {
        val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
        return prefs.getString("token", null) ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
        }.joinToString("") { "%02x".format(it.toInt() and 255) }.also {
            check(prefs.edit().putString("token", it).commit())
        }
    }
}

class BridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        val address = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
        val outer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(32, 24, 32, 24) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 24)
        }
        outer.addView(layout, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        val preferences = getSharedPreferences("bridge-control", MODE_PRIVATE)
        var enabled = preferences.getBoolean("enabled", true)
        layout.addView(Button(this).apply {
            fun updateLabel() { text = if (enabled) "Disable bridge" else "Enable bridge" }
            updateLabel()
            setOnClickListener {
                enabled = !enabled
                preferences.edit().putBoolean("enabled", enabled).commit()
                if (enabled) startForegroundService(Intent(this@BridgeActivity, BridgeService::class.java))
                else stopService(Intent(this@BridgeActivity, BridgeService::class.java))
                updateLabel()
            }
            requestFocus()
        })
        layout.addView(TextView(this).apply {
            textSize = 22f
            text = "DLNALink TV Bridge\n\n${address ?: "Connect to Wi-Fi or Ethernet"}\n\n" +
                "Enable the bridge, then tap Scan TV pairing QR on your phone.\n\n" +
                "Anyone scanning this code can request playback. Trusted LAN only; do not expose this port to the Internet."
        })
        if (address != null) {
            val value = "dlnalink://pair?v=1&host=$address&port=${BridgeService.PORT}&key=${Pairing.token(this)}"
            val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(value, com.google.zxing.BarcodeFormat.QR_CODE, 720, 720)
            val pixels = IntArray(720 * 720) { index -> if (matrix[index % 720, index / 720]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            val bitmap = android.graphics.Bitmap.createBitmap(pixels, 720, 720, android.graphics.Bitmap.Config.ARGB_8888)
            val side = (320 * resources.displayMetrics.density).toInt()
            outer.addView(android.widget.ImageView(this).apply {
                setImageBitmap(bitmap); contentDescription = "TV pairing QR code"
            }, LinearLayout.LayoutParams(side, side).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
        }
        if (!Settings.canDrawOverlays(this)) {
            layout.addView(Button(this).apply {
                text = "Allow background VLC launch (optional special access)"
                setOnClickListener {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                }
            })
        }
        setContentView(outer)
        if (enabled) startForegroundService(Intent(this, BridgeService::class.java))
    }
}
