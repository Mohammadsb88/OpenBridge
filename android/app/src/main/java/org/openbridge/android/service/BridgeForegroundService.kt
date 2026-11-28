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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
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
import org.json.JSONArray
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
import org.openbridge.android.protocol.OtpParser
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
                BridgeStore.voiceCapability.value?.let { cap ->
                    BridgeRuntime.sendToIphone(
                        BridgeModels.voiceCapabilityJson(
                            verdict = cap.verdict,
                            mode = cap.mode,
                            downlinkOk = cap.downlinkOk,
                            uplinkOk = cap.uplinkOk,
                            downlinkSource = cap.downlinkSource,
                            uplinkLabel = cap.uplinkLabel,
                            summary = cap.summary
                        )
                    )
                }
            },
            onBleAudioPcm16k = { pcm16k ->
                BridgeRuntime.audioRelay?.onIncomingBleAudioPcm(pcm16k)
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

    @SuppressLint("MissingPermission")
    private fun getActiveSimsJson(): JSONArray {
        val arr = JSONArray()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return arr
        }
        try {
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val list = sm.activeSubscriptionInfoList.orEmpty()
            for (info in list) {
                arr.put(
                    JSONObject()
                        .put("slot", info.simSlotIndex)
                        .put("subId", info.subscriptionId)
                        .put("name", (info.displayName ?: info.carrierName ?: "SIM ${info.simSlotIndex + 1}").toString())
                )
            }
        } catch (_: Exception) {
        }
        return arr
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
            wifiIp = wifiIp,
            sims = getActiveSimsJson()
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
            "DIAL_REQUEST" -> handleDialRequest(
                number = cmd.optString("number", ""),
                subId = cmd.optInt("subId", -1).takeIf { it > 0 }
            )
            "SEND_SMS" -> handleSendSmsRequest(cmd)
            "RUN_USSD" -> handleUssdRequest(
                code = cmd.optString("code", ""),
                subId = cmd.optInt("subId", -1).takeIf { it > 0 }
            )
            "CALL_FORWARD" -> {
                val enable = cmd.optBoolean("enable", true)
                val target = cmd.optString("target", "").trim()
                val subId = cmd.optInt("subId", -1).takeIf { it > 0 }
                val ussd = if (enable) {
                    if (target.isBlank()) return
                    "*21*$target#"
                } else {
                    "#21#"
                }
                handleDialRequest(ussd, subId)
            }
            "SYNC_REQUEST" -> serviceScope.launch(Dispatchers.IO) {
                performHistoryAndContactsSync()
            }
            "PROBE_VOICE_CAPABILITY" -> serviceScope.launch(Dispatchers.IO) {
                BridgeRuntime.audioRelay?.probeCompatibility()
            }
            "AUDIO_PREF" -> {
                val transport = cmd.optString("transport", "")
                if (transport.isNotBlank()) {
                    BridgeRuntime.audioRelay?.setPreferredTransport(transport)
                }
            }
            else -> BridgeStore.log("Unknown command from iOS: ${cmd.optString("type")}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleUssdRequest(code: String, subId: Int?) {
        val clean = code.trim()
        if (clean.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            BridgeRuntime.sendToIphone(
                BridgeModels.ussdResponseJson(clean, false, "Missing CALL_PHONE permission on Android")
            )
            return
        }
        try {
            val baseTm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val tm = if (subId != null) baseTm.createForSubscriptionId(subId) else baseTm
            tm.sendUssdRequest(
                clean,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        val text = response.toString()
                        BridgeRuntime.sendToIphone(BridgeModels.ussdResponseJson(request, true, text))
                        BridgeStore.log("USSD $request -> $text")
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        // Some carriers reject silent USSD API for MMI codes; try Telecom placeCall fallback if failureCode
                        BridgeRuntime.sendToIphone(
                            BridgeModels.ussdResponseJson(
                                request,
                                false,
                                "USSD code error ($failureCode). If this is a forwarding MMI, use Call Forward button."
                            )
                        )
                        BridgeStore.log("USSD $request failed code=$failureCode")
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            BridgeRuntime.sendToIphone(
                BridgeModels.ussdResponseJson(clean, false, e.message ?: "USSD error")
            )
        }
    }

    private fun performHistoryAndContactsSync() {
        BridgeStore.log("Syncing SMS Inbox, Call Log, and Contacts to iPhone...")
        syncRecentSms()
        syncRecentCallLog()
        syncContacts()
    }

    private fun syncRecentSms() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val items = JSONArray()
            contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type"),
                null,
                null,
                "date DESC LIMIT 35"
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow("_id")
                val idxAddr = c.getColumnIndexOrThrow("address")
                val idxBody = c.getColumnIndexOrThrow("body")
                val idxDate = c.getColumnIndexOrThrow("date")
                val idxType = c.getColumnIndexOrThrow("type")
                while (c.moveToNext()) {
                    val id = "sync-sms-${c.getString(idxId)}"
                    val addr = c.getString(idxAddr) ?: ""
                    val body = c.getString(idxBody) ?: ""
                    val ts = c.getLong(idxDate)
                    val type = c.getInt(idxType) // 1 = inbox, 2 = sent
                    val name = if (addr.isNotBlank()) lookupContactName(addr) else null
                    val otp = if (type == 1) (OtpParser.extractOtp(body) ?: "") else ""
                    items.put(
                        JSONObject()
                            .put("id", id)
                            .put("sender", addr)
                            .put("contactName", name ?: "")
                            .put("body", body)
                            .put("otp", otp)
                            .put("ts", ts)
                            .put("isOutgoing", type == 2)
                            .put("deliveryStatus", if (type == 2) "sent" else JSONObject.NULL)
                    )
                    if (items.length() >= 10) {
                        BridgeRuntime.sendToIphone(
                            JSONObject().put("type", "SMS_HISTORY").put("items", items).toString()
                        )
                        while (items.length() > 0) items.remove(0)
                        Thread.sleep(60)
                    }
                }
            }
            if (items.length() > 0) {
                BridgeRuntime.sendToIphone(
                    JSONObject().put("type", "SMS_HISTORY").put("items", items).toString()
                )
            }
        } catch (e: Exception) {
            BridgeStore.log("SMS history sync error: ${e.message}")
        }
    }

    private fun syncRecentCallLog() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val items = JSONArray()
            contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT 35"
            )?.use { c ->
                val idxId = c.getColumnIndexOrThrow(CallLog.Calls._ID)
                val idxNum = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val idxName = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val idxType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                val idxDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val stateLabel = when (c.getInt(idxType)) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        else -> "CALL"
                    }
                    val num = c.getString(idxNum) ?: ""
                    val name = c.getString(idxName) ?: (if (num.isNotBlank()) lookupContactName(num) else "") ?: ""
                    items.put(
                        JSONObject()
                            .put("id", "sync-call-${c.getString(idxId)}")
                            .put("state", stateLabel)
                            .put("number", num)
                            .put("contactName", name)
                            .put("ts", c.getLong(idxDate))
                    )
                    if (items.length() >= 15) {
                        BridgeRuntime.sendToIphone(
                            JSONObject().put("type", "CALL_HISTORY").put("items", items).toString()
                        )
                        while (items.length() > 0) items.remove(0)
                        Thread.sleep(60)
                    }
                }
            }
            if (items.length() > 0) {
                BridgeRuntime.sendToIphone(
                    JSONObject().put("type", "CALL_HISTORY").put("items", items).toString()
                )
            }
        } catch (e: Exception) {
            BridgeStore.log("Call log sync error: ${e.message}")
        }
    }

    private fun syncContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val seen = HashSet<String>()
            val items = JSONArray()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { c ->
                val idxName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val idxNum = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && seen.size < 250) {
                    val name = (c.getString(idxName) ?: "").trim()
                    val num = (c.getString(idxNum) ?: "").trim()
                    if (num.isEmpty()) continue
                    val key = "${name.lowercase()}|${num.filter { it.isDigit() || it == '+' }}"
                    if (!seen.add(key)) continue
                    items.put(JSONObject().put("name", name.ifEmpty { num }).put("number", num))
                    if (items.length() >= 35) {
                        BridgeRuntime.sendToIphone(
                            JSONObject().put("type", "CONTACTS_SYNC").put("items", items).toString()
                        )
                        while (items.length() > 0) items.remove(0)
                        Thread.sleep(50)
                    }
                }
            }
            if (items.length() > 0) {
                BridgeRuntime.sendToIphone(
                    JSONObject().put("type", "CONTACTS_SYNC").put("items", items).toString()
                )
            }
            BridgeStore.log("Synced ${seen.size} Android contacts")
        } catch (e: Exception) {
            BridgeStore.log("Contacts sync error: ${e.message}")
        }
    }

    private fun lookupContactName(phone: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun handleSendSmsRequest(cmd: JSONObject) {
        val id = cmd.optString("id").ifBlank { UUID.randomUUID().toString() }
        val recipient = cmd.optString("recipient").trim()
        val body = cmd.optString("body")
        val subId = cmd.optInt("subId", -1).takeIf { it > 0 }

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
            val baseSmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val smsManager = if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                baseSmsManager.createForSubscriptionId(subId)
            } else if (subId != null) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                baseSmsManager
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
            BridgeStore.log("Dispatched SMS (${parts.size} part(s), subId=${subId ?: "default"}) to $recipient")
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
    private fun findPhoneAccountForSubId(telecom: TelecomManager, subId: Int): PhoneAccountHandle? {
        return try {
            val handles = telecom.callCapablePhoneAccounts
            val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subInfo = sm.activeSubscriptionInfoList.orEmpty().firstOrNull { it.subscriptionId == subId }
            val iccId = subInfo?.iccId
            handles.firstOrNull { h ->
                h.id == subId.toString() || (!iccId.isNullOrBlank() && h.id.contains(iccId, ignoreCase = true))
            } ?: subInfo?.let { info -> handles.getOrNull(info.simSlotIndex) }
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleDialRequest(number: String, subId: Int? = null) {
        if (number.isBlank()) return
        BridgeStore.log("Received DIAL_REQUEST: $number (subId=${subId ?: "default"})")
        BridgeRuntime.audioRelay?.startListening()
        pushDeviceStatus()

        val uri = Uri.fromParts("tel", number, null)
        val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val extras = Bundle()
        if (subId != null) {
            findPhoneAccountForSubId(telecom, subId)?.let { handle ->
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
        }

        try {
            telecom.placeCall(uri, extras)
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
