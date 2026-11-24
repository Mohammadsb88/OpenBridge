package org.openbridge.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.data.RelayedEvent
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeModels
import org.openbridge.android.service.BridgeForegroundService
import org.openbridge.android.service.BridgeRuntime
import org.openbridge.android.ui.OpenBridgeApp
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var missingPermissions by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = BridgeStore.getSavedLanguage(newBase)
        val wrapped = applyLocale(newBase, lang)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BridgeStore.init(applicationContext)
        refreshPermissions()

        setContent {
            MaterialTheme {
                OpenBridgeApp(
                    allPermissionsGranted = missingPermissions.isEmpty(),
                    missingPermissionsList = missingPermissions.map { it.substringAfterLast('.') },
                    onRequestPermissions = { requestMissingPermissions() },
                    onRequestBatteryUnrestricted = { openBatteryOptimizationSettings() },
                    onStartBridge = { startBridgeIfPermitted() },
                    onStopBridge = { BridgeForegroundService.stop(this) },
                    onSendTestSms = { sendTestSms() },
                    onSendTestCall = { sendTestCall() },
                    onUnpair = { BridgeStore.unpairAndRotateKeys() },
                    onLanguageChange = { lang ->
                        BridgeStore.setLanguage(lang)
                        recreate()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms
    }

    private fun refreshPermissions() {
        missingPermissions = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startBridgeIfPermitted() {
        refreshPermissions()
        val blePermsMissing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        if (blePermsMissing) {
            requestMissingPermissions()
            return
        }
        BridgeForegroundService.start(this)
    }

    @SuppressLint("BatteryLife")
    private fun openBatteryOptimizationSettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun sendTestSms() {
        val id = UUID.randomUUID().toString()
        val body = "بانک تست: رمز پویا: 482910 (Test OTP)"
        val payload = BridgeModels.smsReceivedJson(
            id = id,
            sender = "+989120000000",
            contactName = "Test Bank",
            body = body,
            otp = "482910",
            ts = System.currentTimeMillis()
        )
        val sent = BridgeRuntime.sendToIphone(payload)
        BridgeStore.addEvent(RelayedEvent(id, "SMS", "Test Bank", body, "482910"))
        BridgeStore.log("Test SMS sent=$sent")
    }

    private fun sendTestCall() {
        val id = UUID.randomUUID().toString()
        val payload = BridgeModels.callStateJson(
            id = id,
            state = BridgeConstants.CALL_RINGING,
            number = "+989123456789",
            contactName = "Test Caller",
            ts = System.currentTimeMillis()
        )
        val sent = BridgeRuntime.sendToIphone(payload)
        BridgeStore.addEvent(RelayedEvent(id, "CALL", "Test Caller", "RINGING +989123456789"))
        BridgeStore.log("Test Call sent=$sent")
    }

    private fun applyLocale(context: Context, lang: String): Context {
        if (lang == "system") return context
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
