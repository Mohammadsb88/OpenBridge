package org.openbridge.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.openbridge.android.MainActivity
import org.openbridge.android.R
import org.openbridge.android.ble.BleGattServerManager
import org.openbridge.android.data.BleConnectionState
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeModels

object BridgeRuntime {
    @Volatile
    var gattManager: BleGattServerManager? = null

    fun sendToIphone(plainJson: String): Boolean {
        val mgr = gattManager ?: return false
        return mgr.sendEncryptedJson(plainJson)
    }
}

class BridgeForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "openbridge_service_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "org.openbridge.android.ACTION_STOP"

        fun start(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BridgeForegroundService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statusJob: Job? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pushDeviceStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        BridgeStore.init(applicationContext)
        createNotificationChannel()
        startForegroundSafe()

        val manager = BleGattServerManager(
            context = applicationContext,
            onEncryptedCommand = { cmd -> handleRemoteCommand(cmd) },
            onDeviceConnected = {
                updateNotification()
                pushDeviceStatus()
            }
        )
        BridgeRuntime.gattManager = manager
        val started = manager.start()
        if (!started) {
            BridgeStore.log("Stopping service: BLE could not start")
            stopSelf()
            return
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        statusJob = serviceScope.launch {
            BridgeStore.connectionState.collect {
                updateNotification()
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(45_000)
                if (BridgeRuntime.gattManager?.isConnected() == true) {
                    pushDeviceStatus()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        statusJob?.cancel()
        serviceScope.cancel()
        BridgeRuntime.gattManager?.stop()
        BridgeRuntime.gattManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafe() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val state = BridgeStore.connectionState.value
        val text = if (state == BleConnectionState.CONNECTED) {
            getString(R.string.notif_running_connected)
        } else {
            getString(R.string.notif_running_waiting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.notif_running_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun pushDeviceStatus() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val operatorName = tm.networkOperatorName?.takeIf { it.isNotBlank() } ?: "SIM"
        val signal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tm.signalStrength?.level ?: 0
        } else {
            2
        }

        val payload = BridgeModels.deviceStatusJson(
            battery = battery,
            charging = charging,
            signal = signal,
            networkType = "Cellular",
            operatorName = operatorName
        )
        BridgeRuntime.sendToIphone(payload)
    }

    private fun handleRemoteCommand(cmd: JSONObject) {
        when (cmd.optString("type")) {
            "CALL_ACTION" -> handleCallAction(cmd)
            "DIAL_REQUEST" -> handleDialRequest(cmd.optString("number", ""))
            else -> BridgeStore.log("Unknown command from iOS: ${cmd.optString("type")}")
        }
    }

    private fun handleCallAction(cmd: JSONObject) {
        val action = cmd.optString("action")
        val number = cmd.optString("number", "")
        val smsBody = cmd.optString("smsBody", getString(R.string.quick_sms_default))
        BridgeStore.log("Received CALL_ACTION=$action number=$number")

        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        when (action) {
            BridgeConstants.ACTION_REJECT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        @Suppress("DEPRECATION")
                        telecom.endCall()
                        BridgeStore.log("Executed TelecomManager.endCall()")
                    } catch (e: SecurityException) {
                        BridgeStore.log("Missing ANSWER_PHONE_CALLS permission: ${e.message}")
                    }
                }
            }
            BridgeConstants.ACTION_SILENCE -> {
                try {
                    telecom.silenceRinger()
                    BridgeStore.log("Executed TelecomManager.silenceRinger()")
                } catch (e: SecurityException) {
                    BridgeStore.log("Cannot silence ringer: ${e.message}")
                }
            }
            BridgeConstants.ACTION_QUICK_SMS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        @Suppress("DEPRECATION")
                        telecom.endCall()
                    } catch (_: Exception) {
                    }
                }
                if (number.isNotBlank()) {
                    try {
                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            getSystemService(SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsManager.getDefault()
                        }
                        smsManager.sendTextMessage(number, null, smsBody, null, null)
                        BridgeStore.log("Sent quick reply SMS to $number")
                    } catch (e: Exception) {
                        BridgeStore.log("Failed sending quick SMS: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleDialRequest(number: String) {
        if (number.isBlank()) return
        BridgeStore.log("Received DIAL_REQUEST: $number")
        try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(callIntent)
        } catch (e: SecurityException) {
            BridgeStore.log("Missing CALL_PHONE permission, falling back to dialer: ${e.message}")
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(dialIntent)
        } catch (e: Exception) {
            BridgeStore.log("Dial request failed: ${e.message}")
        }
    }
}
