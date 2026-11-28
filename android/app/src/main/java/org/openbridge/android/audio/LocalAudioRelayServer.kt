package org.openbridge.android.audio

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.data.VoiceCapabilityState
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeModels
import org.openbridge.android.service.BridgeRuntime
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dual-transport 16kHz mono PCM audio bridge:
 * - Supports automatic Wi-Fi / Hotspot HD TCP streaming (port 54810) when reachable
 * - Falls back seamlessly to Pure-BLE E2EE 8kHz IMA-ADPCM (`AUDIO_TX`/`AUDIO_RX`) with zero Wi-Fi
 * - Routes call audio via Shizuku PrivilegedAudioService (dynamic OEM sample-rate/stereo adaptation),
 *   Root VOICE_CALL, or Acoustic Fallback with Echo Suppression.
 */
class LocalAudioRelayServer(private val context: Context) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 640 // 20ms at 16kHz 16-bit mono

        fun getLocalWifiOrHotspotIp(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                val candidates = mutableListOf<Pair<Int, String>>()
                for (intf in interfaces) {
                    if (!intf.isUp || intf.isLoopback) continue
                    val name = intf.name.lowercase()
                    val priority = when {
                        name.startsWith("ap") || name.startsWith("swlan") -> 0
                        name.startsWith("wlan") -> 1
                        name.startsWith("rndis") || name.startsWith("eth") -> 2
                        else -> 3
                    }
                    for (addr in intf.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("172.") || ip.startsWith("10.")) {
                                candidates.add(priority to ip)
                            }
                        }
                    }
                }
                candidates.minByOrNull { it.first }?.second ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        fun isShizukuReady(): Boolean {
            return try {
                Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
        }

        fun isRootAvailable(): Boolean {
            return try {
                val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
                p.waitFor() == 0
            } catch (_: Throwable) {
                false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var callSessionJob: Job? = null

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var isMuted: Boolean = false

    @Volatile
    private var lastDownlinkPeakMs: Long = 0L

    @Volatile
    private var preferredTransport: String = "" // "" | "ble" | "wifi"

    @Volatile
    private var callBridgeActive: Boolean = false

    private val bleUplinkQueue = ArrayBlockingQueue<ByteArray>(24)

    @Volatile
    private var privService: IPrivilegedAudioService? = null

    private var privLatch: CountDownLatch? = null

    private val privConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            privService = IPrivilegedAudioService.Stub.asInterface(service)
            privLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            privService = null
        }
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PrivilegedAudioService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("audio_priv")
            .debuggable(false)
            .version(5)
    }

    private fun ensurePrivilegedServiceBound(): IPrivilegedAudioService? {
        if (!isShizukuReady()) return null
        privService?.let { return it }
        return try {
            val latch = CountDownLatch(1)
            privLatch = latch
            Shizuku.bindUserService(userServiceArgs, privConnection)
            latch.await(3, TimeUnit.SECONDS)
            privService
        } catch (t: Throwable) {
            BridgeStore.log("Shizuku bind error: ${t.message}")
            null
        }
    }

    /**
     * Probes telephony downlink capture + TYPE_TELEPHONY uplink routing once on demand.
     */
    fun probeCompatibility(): VoiceCapabilityState {
        val voiceMode = BridgeStore.voiceMode.value
        val result = if (isShizukuReady()) {
            val svc = ensurePrivilegedServiceBound()
            val rawJson = runCatching { svc?.probeAudioCapabilities() }.getOrNull()
            if (!rawJson.isNullOrBlank()) {
                val obj = runCatching { JSONObject(rawJson) }.getOrNull()
                if (obj != null) {
                    val verdict = obj.optString("verdict", "fallback_only")
                    val dlOk = obj.optBoolean("downlinkOk", false)
                    val ulOk = obj.optBoolean("uplinkOk", false)
                    val dlSrc = obj.optString("downlinkSource", "")
                    val ulLbl = obj.optString("uplinkLabel", "")
                    val summary = when (verdict) {
                        "two_way_direct" -> "Direct Shizuku 2-way ready ($dlSrc + $ulLbl)"
                        "downlink_only" -> "Direct listen ready ($dlSrc); uplink needs local mic/speaker fallback"
                        else -> "Telephony HAL restricted ($dlSrc); acoustic speaker fallback available"
                    }
                    VoiceCapabilityState(
                        verdict = verdict,
                        mode = "shizuku",
                        downlinkOk = dlOk,
                        uplinkOk = ulOk,
                        downlinkSource = dlSrc,
                        uplinkLabel = ulLbl,
                        summary = summary
                    )
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        } ?: run {
            val rootOk = isRootAvailable()
            VoiceCapabilityState(
                verdict = if (rootOk && voiceMode == "root") "downlink_only" else "fallback_only",
                mode = voiceMode,
                downlinkOk = false,
                uplinkOk = false,
                downlinkSource = if (Shizuku.pingBinder()) "Grant Shizuku permission" else "Shizuku inactive",
                uplinkLabel = "Acoustic speaker/mic fallback",
                summary = if (Shizuku.pingBinder()) {
                    "Authorize Shizuku for bag-mode direct call audio."
                } else {
                    "Start Shizuku via USB/Wireless ADB for direct call audio; acoustic fallback works anytime."
                }
            )
        }

        BridgeStore.setVoiceCapability(result)
        BridgeStore.log("Voice compatibility check: ${result.verdict} (${result.summary})")
        BridgeRuntime.sendToIphone(
            BridgeModels.voiceCapabilityJson(
                verdict = result.verdict,
                mode = result.mode,
                downlinkOk = result.downlinkOk,
                uplinkOk = result.uplinkOk,
                downlinkSource = result.downlinkSource,
                uplinkLabel = result.uplinkLabel,
                summary = result.summary
            )
        )
        return result
    }

    @Synchronized
    fun startListening() {
        preferredTransport = ""
        bleUplinkQueue.clear()
        BridgeRuntime.gattManager?.resetAudioCodecState()

        if (acceptJob?.isActive != true) {
            acceptJob = scope.launch {
                try {
                    val server = ServerSocket(BridgeConstants.LOCAL_AUDIO_PORT).apply {
                        reuseAddress = true
                    }
                    serverSocket = server
                    BridgeStore.log("Audio relay listening on port ${BridgeConstants.LOCAL_AUDIO_PORT} (+BLE ADPCM fallback)")
                    while (isActive) {
                        val client = server.accept()
                        runCatching { activeClient?.close() }
                        client.tcpNoDelay = true
                        activeClient = client
                        preferredTransport = "wifi"
                        BridgeStore.log("iPhone HD Wi-Fi audio connected from ${client.inetAddress.hostAddress}")
                        BridgeStore.setActiveAudioTransport("wifi_pcm")
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        BridgeStore.log("Audio relay server stopped: ${e.message}")
                    }
                }
            }
        }

        if (callSessionJob?.isActive != true) {
            callBridgeActive = true
            callSessionJob = scope.launch {
                runCallAudioSession()
            }
        }
    }

    @Synchronized
    fun stop() {
        callBridgeActive = false
        isMuted = false
        preferredTransport = ""
        bleUplinkQueue.clear()
        BridgeRuntime.gattManager?.resetAudioCodecState()
        BridgeStore.setActiveAudioTransport("")
        runCatching { privService?.stopAudioCapture() }
        runCatching { activeClient?.close() }
        activeClient = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        callSessionJob?.cancel()
        callSessionJob = null
        acceptJob?.cancel()
        acceptJob = null
    }

    fun setPreferredTransport(transport: String) {
        preferredTransport = transport.lowercase()
        BridgeStore.log("iOS audio transport preference: $preferredTransport")
    }

    fun onIncomingBleAudioPcm(pcm16k: ByteArray) {
        if (!callBridgeActive || isMuted || pcm16k.isEmpty()) return
        if (preferredTransport != "wifi" && activeClient == null) {
            preferredTransport = "ble"
        }
        while (!bleUplinkQueue.offer(pcm16k)) {
            bleUplinkQueue.poll()
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = muted
        }
        BridgeStore.log("Audio mute=$muted")
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        applySpeakerRouting(am, enabled)
        BridgeStore.log("Speakerphone=$enabled")
    }

    private fun tryStartShizukuCapturePort(): Pair<Int, String> {
        val svc = ensurePrivilegedServiceBound() ?: return -1 to "Shizuku not ready"
        return try {
            val port = svc.startAudioCapture()
            val status = runCatching { svc.captureStatus }.getOrDefault("unknown")
            port to status
        } catch (t: Throwable) {
            -1 to "Shizuku bind error: ${t.message}"
        }
    }

    private fun applySpeakerRouting(am: AudioManager, enable: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enable) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        am.setCommunicationDevice(speaker)
                    }
                } else {
                    am.clearCommunicationDevice()
                }
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = enable
        } catch (e: Exception) {
            BridgeStore.log("Speaker routing note: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun createBestLocalRecorder(minBuf: Int, tryPrivileged: Boolean): Pair<AudioRecord, Boolean> {
        val candidates = if (tryPrivileged) {
            listOf(
                MediaRecorder.AudioSource.VOICE_CALL to true,
                MediaRecorder.AudioSource.VOICE_DOWNLINK to true,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to false,
                MediaRecorder.AudioSource.MIC to false
            )
        } else {
            listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION to false,
                MediaRecorder.AudioSource.MIC to false
            )
        }

        for ((source, isDirect) in candidates) {
            try {
                val rec = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording()
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        BridgeStore.log("AudioRecord active: source=$source directCall=$isDirect")
                        return rec to isDirect
                    }
                    rec.stop()
                }
                rec.release()
            } catch (e: Throwable) {
                BridgeStore.log("Audio source $source unavailable: ${e.message}")
            }
        }
        throw IllegalStateException("No initialized AudioRecord source available")
    }

    private fun emitTransportStatus(direct: Boolean, source: String, transport: String) {
        BridgeStore.setActiveAudioTransport(transport)
        BridgeRuntime.sendToIphone(
            BridgeModels.audioStatusJson(direct = direct, source = source, transport = transport)
        )
    }

    private fun forwardDownlinkChunk(
        pcm16k: ByteArray,
        byteCount: Int,
        sessionStartMs: Long,
        direct: Boolean,
        source: String,
        lastReportedTransport: Array<String>
    ) {
        val chunk = if (byteCount == pcm16k.size) pcm16k else pcm16k.copyOf(byteCount)
        val wifiSock = activeClient
        if (wifiSock != null && !wifiSock.isClosed) {
            try {
                wifiSock.getOutputStream().write(chunk)
                if (lastReportedTransport[0] != "wifi_pcm") {
                    lastReportedTransport[0] = "wifi_pcm"
                    emitTransportStatus(direct, source, "wifi_pcm")
                }
                return
            } catch (_: Exception) {
                runCatching { wifiSock.close() }
                if (activeClient === wifiSock) activeClient = null
            }
        }

        val wifiIp = getLocalWifiOrHotspotIp()
        val graceElapsed = wifiIp.isBlank() ||
            preferredTransport == "ble" ||
            (System.currentTimeMillis() - sessionStartMs > 1200L)

        val gatt = BridgeRuntime.gattManager
        if (graceElapsed && gatt != null && gatt.isAudioReady()) {
            gatt.sendAudioPcm16k(chunk)
            if (lastReportedTransport[0] != "ble_adpcm") {
                lastReportedTransport[0] = "ble_adpcm"
                emitTransportStatus(direct, source, "ble_adpcm")
            }
        }
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun runCallAudioSession() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prevMode = am.mode
        @Suppress("DEPRECATION")
        val prevSpeaker = am.isSpeakerphoneOn
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenBridge:AudioRelay").apply {
            setReferenceCounted(false)
        }

        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        var privSocket: Socket? = null

        try {
            wakeLock.acquire(60 * 60 * 1000L)
            val sessionStartMs = System.currentTimeMillis()
            val lastTransport = arrayOf("")
            val voiceMode = BridgeStore.voiceMode.value

            // 1. Try Shizuku PrivilegedAudioService first (adaptive rates & direct HAL routing)
            if (voiceMode == "shizuku" && isShizukuReady()) {
                val (privPort, privStatus) = tryStartShizukuCapturePort()
                BridgeStore.log("Shizuku audio probe: port=$privPort status=$privStatus")
                if (privPort > 0) {
                    val ps = Socket("127.0.0.1", privPort).apply { tcpNoDelay = true }
                    privSocket = ps

                    val initialTransport = if (getLocalWifiOrHotspotIp().isBlank() || preferredTransport == "ble") "ble_adpcm" else "wifi_pcm"
                    lastTransport[0] = initialTransport
                    emitTransportStatus(direct = true, source = privStatus, transport = initialTransport)

                    val wifiReadJob = scope.launch {
                        val buf = ByteArray(1024)
                        val leftover = ByteArrayOutputStream()
                        val out = ps.getOutputStream()
                        while (callBridgeActive && !ps.isClosed) {
                            val wifi = activeClient
                            if (wifi != null && !wifi.isClosed) {
                                try {
                                    val r = wifi.getInputStream().read(buf)
                                    if (r <= 0) {
                                        runCatching { wifi.close() }
                                        if (activeClient === wifi) activeClient = null
                                        continue
                                    }
                                    leftover.write(buf, 0, r)
                                    val all = leftover.toByteArray()
                                    val evenLen = all.size and 0xFFFE
                                    if (evenLen > 0) {
                                        if (!isMuted) {
                                            out.write(all, 0, evenLen)
                                            out.flush()
                                        }
                                        leftover.reset()
                                        if (all.size > evenLen) leftover.write(all, evenLen, all.size - evenLen)
                                    }
                                } catch (_: Exception) {
                                    runCatching { wifi.close() }
                                    if (activeClient === wifi) activeClient = null
                                }
                            } else {
                                val bleChunk = bleUplinkQueue.poll(150, TimeUnit.MILLISECONDS)
                                if (bleChunk != null && !isMuted) {
                                    out.write(bleChunk)
                                    out.flush()
                                }
                            }
                        }
                    }

                    val dlBuf = ByteArray(CHUNK_BYTES)
                    val dlIn = ps.getInputStream()
                    while (callBridgeActive && !ps.isClosed) {
                        val r = dlIn.read(dlBuf)
                        if (r <= 0) break
                        val even = r and 0xFFFE
                        if (even > 0) {
                            forwardDownlinkChunk(dlBuf, even, sessionStartMs, true, privStatus, lastTransport)
                        }
                    }
                    wifiReadJob.cancel()
                    return
                } else {
                    emitTransportStatus(direct = false, source = privStatus, transport = "ble_adpcm")
                }
            }

            // 2. Fallback to local AudioRecord / AudioTrack (preserving MODE_IN_CALL)
            if (prevMode == AudioManager.MODE_NORMAL) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }

            val tryPrivileged = (voiceMode == "root" && isRootAvailable())
            val minRecBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_BYTES * 6)

            val (rec, isDirectVoiceCall) = createBestLocalRecorder(minRecBuf, tryPrivileged)
            recorder = rec
            val sourceLabel = if (isDirectVoiceCall) "VOICE_CALL (Direct)" else "Acoustic Mic Fallback"
            emitTransportStatus(
                direct = isDirectVoiceCall,
                source = sourceLabel,
                transport = if (getLocalWifiOrHotspotIp().isBlank() || preferredTransport == "ble") "ble_adpcm" else "wifi_pcm"
            )

            if (!isDirectVoiceCall && BridgeStore.autoSpeaker.value) {
                applySpeakerRouting(am, true)
            }

            val minPlayBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_BYTES * 6)

            val usage = if (am.mode == AudioManager.MODE_IN_CALL) {
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            } else {
                AudioAttributes.USAGE_MEDIA
            }

            player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (player.state == AudioTrack.STATE_INITIALIZED) {
                player.play()
            }

            val rxJob = scope.launch {
                val netBuf = ByteArray(2048)
                val leftover = ByteArrayOutputStream()
                while (callBridgeActive) {
                    val wifi = activeClient
                    if (wifi != null && !wifi.isClosed) {
                        try {
                            val read = wifi.getInputStream().read(netBuf)
                            if (read <= 0) {
                                runCatching { wifi.close() }
                                if (activeClient === wifi) activeClient = null
                                continue
                            }
                            leftover.write(netBuf, 0, read)
                            val all = leftover.toByteArray()
                            val evenLen = all.size and 0xFFFE
                            if (evenLen > 0) {
                                val toPlay = all.copyOfRange(0, evenLen)
                                updateDownlinkEnergyAndGain(toPlay, evenLen)
                                player?.write(toPlay, 0, evenLen)
                                leftover.reset()
                                if (all.size > evenLen) {
                                    leftover.write(all, evenLen, all.size - evenLen)
                                }
                            }
                        } catch (_: Exception) {
                            runCatching { wifi.close() }
                            if (activeClient === wifi) activeClient = null
                        }
                    } else {
                        val bleChunk = bleUplinkQueue.poll(150, TimeUnit.MILLISECONDS)
                        if (bleChunk != null) {
                            val copy = bleChunk.copyOf()
                            updateDownlinkEnergyAndGain(copy, copy.size)
                            player?.write(copy, 0, copy.size)
                        }
                    }
                }
            }

            val recBuf = ByteArray(CHUNK_BYTES)
            while (callBridgeActive) {
                val read = rec.read(recBuf, 0, recBuf.size)
                if (read > 1) {
                    val evenCount = read and 0xFFFE
                    processUplinkPcm(
                        buf = recBuf,
                        byteCount = evenCount,
                        suppressEcho = !isDirectVoiceCall && BridgeStore.autoSpeaker.value
                    )
                    forwardDownlinkChunk(recBuf, evenCount, sessionStartMs, isDirectVoiceCall, sourceLabel, lastTransport)
                }
            }
            rxJob.cancel()
        } catch (e: Exception) {
            if (callBridgeActive) {
                BridgeStore.log("Audio bridge session ended: ${e.message}")
            }
        } finally {
            runCatching { privSocket?.close() }
            runCatching { privService?.stopAudioCapture() }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            runCatching { player?.stop() }
            runCatching { player?.release() }
            runCatching {
                am.isMicrophoneMute = false
                if (am.mode != prevMode && prevMode == AudioManager.MODE_NORMAL) {
                    am.mode = prevMode
                }
                applySpeakerRouting(am, prevSpeaker)
            }
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
            BridgeStore.setActiveAudioTransport("")
        }
    }

    private fun updateDownlinkEnergyAndGain(buf: ByteArray, byteCount: Int) {
        var sumSq = 0.0
        var idx = 0
        val count = byteCount / 2
        while (idx + 1 < byteCount) {
            val lo = buf[idx].toInt() and 0xFF
            val hi = buf[idx + 1].toInt()
            var sample = ((hi shl 8) or lo).toShort().toInt()
            sample = (sample * 1.45).toInt().coerceIn(-32768, 32767)
            buf[idx] = (sample and 0xFF).toByte()
            buf[idx + 1] = ((sample shr 8) and 0xFF).toByte()
            sumSq += sample.toDouble() * sample.toDouble()
            idx += 2
        }
        if (count > 0) {
            val rms = sqrt(sumSq / count)
            if (rms > 650.0) {
                lastDownlinkPeakMs = System.currentTimeMillis()
            }
        }
    }

    private fun processUplinkPcm(buf: ByteArray, byteCount: Int, suppressEcho: Boolean) {
        val now = System.currentTimeMillis()
        val activelyPlayingRemote = suppressEcho && (now - lastDownlinkPeakMs < 240L)
        val gain = when {
            isMuted -> 0.0
            activelyPlayingRemote -> 0.16
            else -> 1.55
        }

        var idx = 0
        while (idx + 1 < byteCount) {
            val lo = buf[idx].toInt() and 0xFF
            val hi = buf[idx + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val scaled = if (isMuted) 0 else (sample * gain).toInt().coerceIn(-32768, 32767)
            val out = if (activelyPlayingRemote && abs(scaled) < 120) 0 else scaled
            buf[idx] = (out and 0xFF).toByte()
            buf[idx + 1] = ((out shr 8) and 0xFF).toByte()
            idx += 2
        }
    }
}
