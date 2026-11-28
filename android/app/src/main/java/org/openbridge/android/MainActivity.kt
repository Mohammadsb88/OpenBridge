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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openbridge.android.audio.LocalAudioRelayServer
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.data.RelayedEvent
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeModels
import org.openbridge.android.service.BridgeForegroundService
import org.openbridge.android.service.BridgeRuntime
import org.openbridge.android.ui.OpenBridgeApp
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    private var missingPermissions by mutableStateOf<List<String>>(emptyList())
    private var shizukuReady by mutableStateOf(false)
    private var shizukuAlive by mutableStateOf(false)
    private var compatibilityChecking by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        BridgeForegroundService.refreshForegroundType(this)
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        shizukuReady = grantResult == PackageManager.PERMISSION_GRANTED
        BridgeStore.log("Shizuku permission result granted=$shizukuReady")
    }

    override fun attachBaseContext(newBase: Context) {
        val lang = BridgeStore.getSavedLanguage(newBase)
        val wrapped = applyLocale(newBase, lang)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        BridgeStore.init(applicationContext)
        refreshPermissions()
        refreshShizukuState()
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }

        setContent {
            OpenBridgeApp(
                allPermissionsGranted = missingPermissions.isEmpty(),
                missingPermissionsList = missingPermissions.map { it.substringAfterLast('.') },
                shizukuReady = shizukuReady,
                shizukuAlive = shizukuAlive,
                rootAvailable = LocalAudioRelayServer.isRootAvailable(),
                compatibilityChecking = compatibilityChecking,
                onRequestPermissions = { requestMissingPermissions() },
                onRequestBatteryUnrestricted = { openBatteryOptimizationSettings() },
                onOpenOemAutostart = { openOemAutostartSettings() },
                onRequestShizuku = { requestShizukuPermission() },
                onRunCompatibilityCheck = { runAudioCompatibilityCheck() },
                onToggleForwarding = { enable, targetNumber ->
                    runCallForwardingCode(enable, targetNumber)
                },
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

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshShizukuState()
    }

    private fun refreshShizukuState() {
        shizukuAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        shizukuReady = LocalAudioRelayServer.isShizukuReady()
    }

    private fun runAudioCompatibilityCheck() {
        if (compatibilityChecking) return
        compatibilityChecking = true
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                refreshShizukuState()
                val relay = BridgeRuntime.audioRelay ?: LocalAudioRelayServer(applicationContext)
                relay.probeCompatibility()
            } finally {
                compatibilityChecking = false
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        super.onDestroy()
    }

    private fun requiredPermissions(): List<String> {
        val perms = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO
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

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                BridgeStore.log("Shizuku service is not running on this device")
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuReady = true
            } else {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Throwable) {
            BridgeStore.log("Shizuku error: ${e.message}")
        }
    }

    private fun runCallForwardingCode(enable: Boolean, targetNumber: String) {
        val ussd = if (enable) {
            val clean = targetNumber.trim()
            if (clean.isEmpty()) return
            "*21*$clean#"
        } else {
            "#21#"
        }
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(ussd)}"))
            startActivity(intent)
        } catch (e: Exception) {
            BridgeStore.log("Call forward USSD failed: ${e.message}")
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

    private fun openOemAutostartSettings() {
        val candidates = listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Intent().setClassName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
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
        val wifiIp = LocalAudioRelayServer.getLocalWifiOrHotspotIp()
        val payload = BridgeModels.callStateJson(
            id = id,
            state = BridgeConstants.CALL_RINGING,
            number = "+989123456789",
            contactName = "Test Caller",
            ts = System.currentTimeMillis(),
            wifiIp = wifiIp
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
