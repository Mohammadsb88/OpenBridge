package org.openbridge.android.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openbridge.android.protocol.BridgeCrypto
import org.openbridge.android.protocol.BridgeModels
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class BleConnectionState {
    STOPPED,
    ADVERTISING,
    CONNECTED
}

data class RelayedEvent(
    val id: String = UUID.randomUUID().toString(),
    val kind: String, // "SMS" or "CALL"
    val title: String,
    val subtitle: String,
    val otp: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

object BridgeStore {
    private const val PREFS_NAME = "openbridge_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PRIV_B64 = "ec_priv_pkcs8"
    private const val KEY_PUB_X963_B64 = "ec_pub_x963"
    private const val KEY_PEER_ID = "peer_id"
    private const val KEY_PEER_NAME = "peer_name"
    private const val KEY_SHARED_KEY_B64 = "shared_aes_key"
    private const val KEY_LANGUAGE = "app_language" // "system", "en", "fa"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _connectionState = MutableStateFlow(BleConnectionState.STOPPED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _pairedPeerName = MutableStateFlow<String?>(null)
    val pairedPeerName: StateFlow<String?> = _pairedPeerName.asStateFlow()

    private val _events = MutableStateFlow<List<RelayedEvent>>(emptyList())
    val events: StateFlow<List<RelayedEvent>> = _events.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _language = MutableStateFlow("system")
    val language: StateFlow<String> = _language.asStateFlow()

    private val _qrPayload = MutableStateFlow("")
    val qrPayload: StateFlow<String> = _qrPayload.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val appCtx = context.applicationContext
            val sp = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            ensureIdentityKeys(sp)
            _pairedPeerName.value = sp.getString(KEY_PEER_NAME, null)
            _language.value = sp.getString(KEY_LANGUAGE, "system") ?: "system"
            refreshQrPayload(sp)
        }
    }

    private fun ensureIdentityKeys(sp: SharedPreferences) {
        if (!sp.contains(KEY_DEVICE_ID)) {
            sp.edit().putString(KEY_DEVICE_ID, UUID.randomUUID().toString()).apply()
        }
        if (!sp.contains(KEY_PRIV_B64) || !sp.contains(KEY_PUB_X963_B64)) {
            val kp = BridgeCrypto.generateKeyPair()
            val privB64 = BridgeCrypto.exportPrivateKeyPkcs8Base64(kp.private as ECPrivateKey)
            val pubX963 = BridgeCrypto.exportPublicKeyX963(kp.public as ECPublicKey)
            sp.edit()
                .putString(KEY_PRIV_B64, privB64)
                .putString(KEY_PUB_X963_B64, BridgeCrypto.toBase64(pubX963))
                .apply()
        }
    }

    private fun refreshQrPayload(sp: SharedPreferences) {
        val deviceId = sp.getString(KEY_DEVICE_ID, "") ?: ""
        val pubB64 = sp.getString(KEY_PUB_X963_B64, "") ?: ""
        val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        _qrPayload.value = BridgeModels.buildQrPayload(deviceId, model, pubB64)
    }

    fun getDeviceName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun getPrivateKey(): ECPrivateKey? {
        val b64 = prefs?.getString(KEY_PRIV_B64, null) ?: return null
        return runCatching { BridgeCrypto.importPrivateKeyPkcs8Base64(b64) }.getOrNull()
    }

    fun getSharedKey(): ByteArray? {
        val b64 = prefs?.getString(KEY_SHARED_KEY_B64, null) ?: return null
        return runCatching { BridgeCrypto.fromBase64(b64) }.getOrNull()
    }

    fun savePairing(peerId: String, peerName: String, sharedKey: ByteArray) {
        val sp = prefs ?: return
        sp.edit()
            .putString(KEY_PEER_ID, peerId)
            .putString(KEY_PEER_NAME, peerName)
            .putString(KEY_SHARED_KEY_B64, BridgeCrypto.toBase64(sharedKey))
            .apply()
        _pairedPeerName.value = peerName
        log("Paired with iOS device: $peerName ($peerId)")
    }

    fun unpairAndRotateKeys() {
        val sp = prefs ?: return
        val kp = BridgeCrypto.generateKeyPair()
        val privB64 = BridgeCrypto.exportPrivateKeyPkcs8Base64(kp.private as ECPrivateKey)
        val pubX963 = BridgeCrypto.exportPublicKeyX963(kp.public as ECPublicKey)
        sp.edit()
            .remove(KEY_PEER_ID)
            .remove(KEY_PEER_NAME)
            .remove(KEY_SHARED_KEY_B64)
            .putString(KEY_PRIV_B64, privB64)
            .putString(KEY_PUB_X963_B64, BridgeCrypto.toBase64(pubX963))
            .apply()
        _pairedPeerName.value = null
        refreshQrPayload(sp)
        log("Unpaired and rotated P-256 identity keys.")
    }

    fun setLanguage(lang: String) {
        prefs?.edit()?.putString(KEY_LANGUAGE, lang)?.apply()
        _language.value = lang
    }

    fun getSavedLanguage(context: Context): String {
        init(context)
        return _language.value
    }

    fun setConnectionState(state: BleConnectionState) {
        _connectionState.value = state
    }

    fun addEvent(event: RelayedEvent) {
        val updated = (listOf(event) + _events.value).take(100)
        _events.value = updated
    }

    fun log(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        _logs.value = (listOf(line) + _logs.value).take(250)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
