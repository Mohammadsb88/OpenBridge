package org.openbridge.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import org.openbridge.android.audio.LocalAudioRelayServer
import org.openbridge.android.ble.BleGattServerManager
import org.openbridge.android.data.BleConnectionState
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.data.RelayedEvent
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeModels
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object BridgeRuntime {
    @Volatile
    var gattManager: BleGattServerManager? = null

    @Volatile
    var audioRelay: LocalAudioRelayServer? = null

    fun sendToIphone(plainJson: String): Boolean {
        val mgr = gattManager ?: return false
        return mgr.sendEncryptedJson(plainJson)
    }
}

private data class PendingSmsSend(
    val recipient: String,
    val body: String,
    var remainingParts: Int,
    var anyFailure: Boolean = false,
    var errorDetail: String = ""
)

class BridgeForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "openbridge_service_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "org.openbridge.android.ACTION_STOP"
        const val ACTION_REFRESH_FOREGROUND = "org.openbridge.android.ACTION_REFRESH_FOREGROUND"
        private const val ACTION_SMS_SENT = "org.openbridge.android.ACTION_SMS_SENT"

        fun start(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refreshForegroundType(context: Context) {
            if (BridgeRuntime.gattManager == null) return
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_REFRESH_FOREGROUND
            }
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
    private val pendingSms = ConcurrentHashMap<String, PendingSmsSend>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            pushDeviceStatus()
        }
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra("sms_id") ?: return
            val entry = pendingSms[id] ?: return
            val result = resultCode
            if (result != Activity.RESULT_OK) {
                entry.anyFailure = true
                entry.errorDetail = when (result) {
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Carrier rejected or no balance/SIM"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "No cellular signal"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "Invalid PDU"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "Airplane mode / Radio off"
                    else -> "SMS error code $result"
                }
            }
            entry.remainingParts -= 1
            if (entry.remainingParts <= 0) {
                pendingSms.remove(id)
                val success = !entry.anyFailure
                BridgeRuntime.sendToIphone(
                    BridgeModels.smsSentJson(
                        id = id,
                        recipient = entry.recipient,
                        body = entry.body,
                        success = success,
                        error = entry.errorDetail
                    )
                )
                BridgeStore.log("Outbound SMS id=$id to ${entry.recipient} success=$success ${entry.errorDetail}".trim())
            }
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
        BridgeRuntime.audioRelay = LocalAudioRelayServer(applicationContext)
        val started = manager.start()
        if (!started) {
            BridgeStore.log("Stopping service: BLE could not start")
            stopSelf()
            return
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val smsFilter = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, smsFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(smsSentReceiver, smsFilter)
        }

        statusJob = serviceScope.launch {
            BridgeStore.connectionState.collect {
                updateNotification()
            }
        }
        serviceScope.launch {
            while (isActive) {
                delay(35_000)
                if (BridgeRuntime.gattManager?.isConnected() == true) {
                    pushDeviceStatus()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH_FOREGROUND -> {
                startForegroundSafe()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(smsSentReceiver) }
        statusJob?.cancel()
        serviceScope.cancel()
        BridgeRuntime.audioRelay?.stop()
        BridgeRuntime.audioRelay = null
        BridgeRuntime.gattManager?.stop()
        BridgeRuntime.gattManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafe() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasMic = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val fgsTypes = if (hasMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIF_ID, notification, fgsTypes)
            } catch (e: SecurityException) {
                BridgeStore.log("FGS mic type deferred until foreground: ${e.message}")
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            }
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

        val wifiIp = LocalAudioRelayServer.getLocalWifiOrHotspotIp()
        BridgeStore.setWifiIp(wifiIp)

        val payload = BridgeModels.deviceStatusJson(
            battery = battery,
            charging = charging,
            signal = signal,
            networkType = "Cellular",
            operatorName = operatorName,
            wifiIp = wifiIp
        )
        BridgeRuntime.sendToIphone(payload)
    }

    private fun handleRemoteCommand(cmd: JSONObject) {
        when (cmd.optString("type")) {
            "PING" -> {
                BridgeRuntime.sendToIphone(BridgeModels.pongJson())
                pushDeviceStatus()
            }
            "CALL_ACTION" -> handleCallAction(cmd)
            "DIAL_REQUEST" -> handleDialRequest(cmd.optString("number", ""))
            "SEND_SMS" -> handleSendSmsRequest(cmd)
            else -> BridgeStore.log("Unknown command from iOS: ${cmd.optString("type")}")
        }
    }

    private fun handleSendSmsRequest(cmd: JSONObject) {
        val id = cmd.optString("id").ifBlank { UUID.randomUUID().toString() }
        val recipient = cmd.optString("recipient").trim()
        val body = cmd.optString("body")

        if (recipient.isBlank() || body.isBlank()) {
            BridgeRuntime.sendToIphone(
                BridgeModels.smsSentJson(id, recipient, body, false, "Empty recipient or body")
            )
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            BridgeRuntime.sendToIphone(
                BridgeModels.smsSentJson(id, recipient, body, false, "Android missing SEND_SMS permission")
            )
            BridgeStore.log("Cannot send SMS: missing SEND_SMS permission")
            return
        }

        try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(body)
            pendingSms[id] = PendingSmsSend(
                recipient = recipient,
                body = body,
                remainingParts = parts.size.coerceAtLeast(1)
            )
            val sentIntents = ArrayList<PendingIntent>(parts.size)
            for (i in parts.indices) {
                val intent = Intent(ACTION_SMS_SENT).apply {
                    setPackage(packageName)
                    putExtra("sms_id", id)
                    putExtra("part_index", i)
                }
                val pi = PendingIntent.getBroadcast(
                    this,
                    (id.hashCode() * 31) + i,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                sentIntents.add(pi)
            }
            smsManager.sendMultipartTextMessage(recipient, null, parts, sentIntents, null)
            BridgeStore.addEvent(
                RelayedEvent(
                    id = id,
                    kind = "SMS_OUT",
                    title = "To: $recipient",
                    subtitle = body
                )
            )
            BridgeStore.log("Dispatched SMS (${parts.size} part(s)) to $recipient")
        } catch (e: Exception) {
            pendingSms.remove(id)
            BridgeRuntime.sendToIphone(
                BridgeModels.smsSentJson(id, recipient, body, false, e.message ?: "Send failed")
            )
            BridgeStore.log("Send SMS failed: ${e.message}")
        }
    }

    private fun handleCallAction(cmd: JSONObject) {
        val action = cmd.optString("action")
        val number = cmd.optString("number", "")
        val smsBody = cmd.optString("smsBody", getString(R.string.quick_sms_default))
        BridgeStore.log("Received CALL_ACTION=$action number=$number")

        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        when (action) {
            BridgeConstants.ACTION_ACCEPT -> {
                BridgeRuntime.audioRelay?.startListening()
                pushDeviceStatus()
                try {
                    @Suppress("DEPRECATION")
                    telecom.acceptRingingCall()
                    BridgeStore.log("Accepted ringing call & audio relay ready")
                } catch (e: SecurityException) {
                    BridgeStore.log("Missing ANSWER_PHONE_CALLS permission: ${e.message}")
                } catch (e: Exception) {
                    BridgeStore.log("acceptRingingCall failed: ${e.message}")
                }
            }
            BridgeConstants.ACTION_REJECT -> {
                BridgeRuntime.audioRelay?.stop()
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
            BridgeConstants.ACTION_MUTE -> BridgeRuntime.audioRelay?.setMuted(true)
            BridgeConstants.ACTION_UNMUTE -> BridgeRuntime.audioRelay?.setMuted(false)
            BridgeConstants.ACTION_SPEAKER_ON -> BridgeRuntime.audioRelay?.setSpeakerEnabled(true)
            BridgeConstants.ACTION_SPEAKER_OFF -> BridgeRuntime.audioRelay?.setSpeakerEnabled(false)
            BridgeConstants.ACTION_QUICK_SMS -> {
                BridgeRuntime.audioRelay?.stop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        @Suppress("DEPRECATION")
                        telecom.endCall()
                    } catch (_: Exception) {
                    }
                }
                if (number.isNotBlank()) {
                    handleSendSmsRequest(
                        JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("recipient", number)
                            .put("body", smsBody)
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDialRequest(number: String) {
        if (number.isBlank()) return
        BridgeStore.log("Received DIAL_REQUEST: $number")
        BridgeRuntime.audioRelay?.startListening()
        pushDeviceStatus()

        val uri = Uri.fromParts("tel", number, null)
        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            // Works from background services on Xiaomi HyperOS / Android 10+ without activity-launch blocks
            telecom.placeCall(uri, Bundle())
            BridgeStore.log("Placed call via TelecomManager.placeCall")
            return
        } catch (e: SecurityException) {
            BridgeStore.log("placeCall SecurityException: ${e.message}")
        } catch (e: Exception) {
            BridgeStore.log("placeCall fallback to Intent: ${e.message}")
        }

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
