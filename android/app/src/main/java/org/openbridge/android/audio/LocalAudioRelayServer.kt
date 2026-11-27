package org.openbridge.android.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.openbridge.android.data.BridgeStore
import org.openbridge.android.protocol.BridgeConstants
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Full-duplex 16kHz 16-bit mono PCM audio relay over Local Hotspot / Wi-Fi (port 54810).
 * Supports Shizuku / Root audio sources when granted, with standard AudioRecord fallback.
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
                        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("wlan") -> 1
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

    @Synchronized
    fun startListening() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch {
            try {
                val server = ServerSocket(BridgeConstants.LOCAL_AUDIO_PORT)
                serverSocket = server
                BridgeStore.log("Audio relay listening on port ${BridgeConstants.LOCAL_AUDIO_PORT}")
                while (isActive) {
                    val client = server.accept()
                    activeClient?.close()
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
        try {
            activeClient?.close()
        } catch (_: Exception) {
        }
        activeClient = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
    }

    @SuppressLint("MissingPermission")
    private fun handleClient(socket: Socket) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val prevMode = audioManager.mode
        val prevSpeaker = audioManager.isSpeakerphoneOn

        try {
            socket.tcpNoDelay = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            val voiceMode = BridgeStore.voiceMode.value
            val usePrivilegedSource = (voiceMode == "shizuku" && isShizukuReady()) ||
                (voiceMode == "root" && isRootAvailable())

            val source = if (usePrivilegedSource) {
                MediaRecorder.AudioSource.VOICE_CALL
            } else {
                // Standard non-root fallback: turn on speakerphone cleanly if bridging acoustically
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }

            val minRecBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_BYTES * 4)

            val recorder = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minRecBuf
                )
            } catch (e: Exception) {
                BridgeStore.log("AudioRecord fallback to MIC (${e.message})")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minRecBuf
                )
            }

            val minPlayBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_BYTES * 4)

            val player = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.startRecording()
            }
            if (player.state == AudioTrack.STATE_INITIALIZED) {
                player.play()
            }

            val input: InputStream = socket.getInputStream()
            val output: OutputStream = socket.getOutputStream()

            val txJob = scope.launch {
                val buf = ByteArray(CHUNK_BYTES)
                while (isActive && !socket.isClosed) {
                    val read = recorder.read(buf, 0, buf.size)
                    if (read > 0) {
                        output.write(buf, 0, read)
                        output.flush()
                    }
                }
            }

            val rxJob = scope.launch {
                val buf = ByteArray(CHUNK_BYTES)
                while (isActive && !socket.isClosed) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    player.write(buf, 0, read)
                }
            }

            while (!socket.isClosed && (txJob.isActive || rxJob.isActive)) {
                Thread.sleep(150)
            }

            txJob.cancel()
            rxJob.cancel()
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { player.stop() }
            runCatching { player.release() }
        } catch (e: Exception) {
            BridgeStore.log("Audio stream ended: ${e.message}")
        } finally {
            runCatching {
                audioManager.mode = prevMode
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = prevSpeaker
            }
            runCatching { socket.close() }
        }
    }
}
