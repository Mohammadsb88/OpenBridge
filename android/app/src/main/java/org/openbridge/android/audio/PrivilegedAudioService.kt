package org.openbridge.android.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged process (Root UID 0 or ADB Shell UID 2000).
 * Attempts direct VOICE_CALL / VOICE_DOWNLINK capture without using the phone speaker.
 */
@Keep
class PrivilegedAudioService : IPrivilegedAudioService.Stub() {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 640
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var statusText = "idle"

    @SuppressLint("MissingPermission")
    override fun startAudioCapture(): Int {
        stopAudioCapture()

        val minRecBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_BYTES * 6)

        val candidates = listOf(
            MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL (Direct)",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "VOICE_DOWNLINK (Direct)"
        )

        var selectedRec: AudioRecord? = null
        var selectedName = ""
        val errors = mutableListOf<String>()

        for ((src, label) in candidates) {
            try {
                val rec = AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minRecBuf
                )
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.startRecording()
                    if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        selectedRec = rec
                        selectedName = label
                        break
                    }
                    rec.stop()
                }
                rec.release()
                errors.add("$label: init failed")
            } catch (t: Throwable) {
                errors.add("$label: ${t.javaClass.simpleName}(${t.message})")
            }
        }

        if (selectedRec == null) {
            statusText = "blocked: ${errors.joinToString("; ")}"
            return -1
        }

        return try {
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running = true
            statusText = selectedName
            val boundPort = ss.localPort

            thread(start = true, isDaemon = true, name = "OB-PrivAudio") {
                streamLoop(ss, selectedRec)
            }
            boundPort
        } catch (t: Throwable) {
            runCatching { selectedRec.stop() }
            runCatching { selectedRec.release() }
            statusText = "socket error: ${t.message}"
            -1
        }
    }

    private fun streamLoop(ss: ServerSocket, rec: AudioRecord) {
        var client: Socket? = null
        var player: AudioTrack? = null
        try {
            ss.soTimeout = 10_000
            client = ss.accept()
            client.tcpNoDelay = true

            val minPlayBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_BYTES * 6)

            player = AudioTrack.Builder()
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
            if (player.state == AudioTrack.STATE_INITIALIZED) {
                player.play()
            }

            val out = client.getOutputStream()
            val inp = client.getInputStream()

            val rxThread = thread(start = true, isDaemon = true) {
                val netBuf = ByteArray(2048)
                val leftover = ByteArrayOutputStream()
                try {
                    while (running && !client.isClosed) {
                        val r = inp.read(netBuf)
                        if (r <= 0) break
                        leftover.write(netBuf, 0, r)
                        val all = leftover.toByteArray()
                        val evenLen = all.size and 0xFFFE
                        if (evenLen > 0) {
                            player.write(all, 0, evenLen)
                            leftover.reset()
                            if (all.size > evenLen) leftover.write(all, evenLen, all.size - evenLen)
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            val recBuf = ByteArray(CHUNK_BYTES)
            while (running && !client.isClosed) {
                val r = rec.read(recBuf, 0, recBuf.size)
                if (r > 1) {
                    out.write(recBuf, 0, r and 0xFFFE)
                    out.flush()
                }
            }
            rxThread.interrupt()
        } catch (_: Throwable) {
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
            runCatching { player?.stop() }
            runCatching { player?.release() }
            runCatching { client?.close() }
            runCatching { ss.close() }
        }
    }

    override fun stopAudioCapture() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        statusText = "stopped"
    }

    override fun getCaptureStatus(): String = statusText

    override fun destroy() {
        stopAudioCapture()
        exitProcess(0)
    }
}
