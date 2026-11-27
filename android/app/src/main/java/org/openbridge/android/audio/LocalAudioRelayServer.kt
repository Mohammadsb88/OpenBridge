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
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.protocol.BridgeConstants
import org.openbridge.android.protocol.BridgeModels
import org.openbridge.android.service.BridgeRuntime
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Full-duplex 16kHz 16-bit mono PCM audio relay over Local Hotspot / Wi-Fi (port 54810).
 * - Tries Shizuku UserService (privileged UID) when enabled for direct VOICE_CALL capture without speaker
 * - Probes direct VOICE_CALL / VOICE_DOWNLINK on rooted ROMs
 * - Preserves cellular MODE_IN_CALL, with optional speaker + software echo suppression fallback
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

    @Volatile
    private var activeClient: Socket? = null

    @Volatile
    private var isMuted: Boolean = false

    @Volatile
    private var lastDownlinkPeakMs: Long = 0L

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
            .version(4)
    }

    @Synchronized
    fun startListening() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            try {
                val server = ServerSocket(BridgeConstants.LOCAL_AUDIO_PORT).apply {
                    reuseAddress = true
                }
                serverSocket = server
                BridgeStore.log("Audio relay listening on port ${BridgeConstants.LOCAL_AUDIO_PORT}")
                while (isActive) {
                    val client = server.accept()
                    runCatching { activeClient?.close() }
                    activeClient = client
                    BridgeStore.log("iPhone audio stream connected from ${client.inetAddress.hostAddress}")
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (isActive) {
                    BridgeStore.log("Audio relay server stopped: ${e.message}")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        isMuted = false
        runCatching { privService?.stopAudioCapture() }
        runCatching { activeClient?.close() }
        activeClient = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
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
        if (!isShizukuReady()) return -1 to "Shizuku not ready"
        return try {
            if (privService == null) {
                val latch = CountDownLatch(1)
                privLatch = latch
                Shizuku.bindUserService(userServiceArgs, privConnection)
                latch.await(3, TimeUnit.SECONDS)
            }
            val svc = privService ?: return -1 to "Shizuku UserService timeout"
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

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun handleClient(socket: Socket) {
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
            socket.tcpNoDelay = true

            val voiceMode = BridgeStore.voiceMode.value

            // 1. Try Shizuku Privileged UserService first if enabled (zero speakerphone!)
            if (voiceMode == "shizuku" && isShizukuReady()) {
                val (privPort, privStatus) = tryStartShizukuCapturePort()
                BridgeStore.log("Shizuku audio probe: port=$privPort status=$privStatus")
                if (privPort > 0) {
                    BridgeRuntime.sendToIphone(BridgeModels.audioStatusJson(direct = true, source = privStatus))
                    val ps = Socket("127.0.0.1", privPort).apply { tcpNoDelay = true }
                    privSocket = ps
                    val netToPriv = scope.launch {
                        val buf = ByteArray(1024)
                        val inp = socket.getInputStream()
                        val out = ps.getOutputStream()
                        while (isActive && !socket.isClosed && !ps.isClosed) {
                            val r = inp.read(buf)
                            if (r <= 0) break
                            if (!isMuted) {
                                out.write(buf, 0, r)
                                out.flush()
                            }
                        }
                    }
                    val privToNet = scope.launch {
                        val buf = ByteArray(CHUNK_BYTES)
                        val inp = ps.getInputStream()
                        val out = socket.getOutputStream()
                        while (isActive && !socket.isClosed && !ps.isClosed) {
                            val r = inp.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            out.flush()
                        }
                    }
                    while (!socket.isClosed && !ps.isClosed && netToPriv.isActive && privToNet.isActive) {
                        Thread.sleep(150)
                    }
                    netToPriv.cancel()
                    privToNet.cancel()
                    return
                } else {
                    BridgeRuntime.sendToIphone(BridgeModels.audioStatusJson(direct = false, source = privStatus))
                }
            }

            // 2. Fallback to local AudioRecord / AudioTrack (preserving MODE_IN_CALL!)
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

            BridgeRuntime.sendToIphone(
                BridgeModels.audioStatusJson(
                    direct = isDirectVoiceCall,
                    source = if (isDirectVoiceCall) "VOICE_CALL (Direct)" else "Acoustic Mic Fallback (No Root)"
                )
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

            val input: InputStream = socket.getInputStream()
            val output: OutputStream = socket.getOutputStream()

            val txJob = scope.launch {
                val buf = ByteArray(CHUNK_BYTES)
                while (isActive && !socket.isClosed) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read > 1) {
                        val evenCount = read and 0xFFFE
                        processUplinkPcm(
                            buf = buf,
                            byteCount = evenCount,
                            suppressEcho = !isDirectVoiceCall && BridgeStore.autoSpeaker.value
                        )
                        output.write(buf, 0, evenCount)
                        output.flush()
                    }
                }
            }

            val rxJob = scope.launch {
                val netBuf = ByteArray(2048)
                val leftover = ByteArrayOutputStream()
                while (isActive && !socket.isClosed) {
                    val read = input.read(netBuf)
                    if (read <= 0) break
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
                }
            }

            while (!socket.isClosed && txJob.isActive && rxJob.isActive) {
                Thread.sleep(150)
            }

            txJob.cancel()
            rxJob.cancel()
        } catch (e: Exception) {
            BridgeStore.log("Audio stream ended: ${e.message}")
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
            runCatching { socket.close() }
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
