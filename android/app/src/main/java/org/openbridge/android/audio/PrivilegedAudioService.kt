package org.openbridge.android.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged process (UID 2000 ADB shell / UID 0 Root).
 * - Dynamically probes VOICE_CALL / VOICE_DOWNLINK across supported rates
 * - Uses MODIFY_AUDIO_ROUTING to route uplink AudioTrack directly into AudioDeviceInfo.TYPE_TELEPHONY
 * - Resamples between 16 kHz mono relay PCM and the vendor HAL's native sample rate / channels
 */
@Keep
class PrivilegedAudioService() : IPrivilegedAudioService.Stub() {
    companion object {
        private const val RELAY_SAMPLE_RATE = 16000
        private const val RELAY_CHUNK_BYTES = 640 // 20ms at 16kHz mono
    }

    private var appContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        appContext = context
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var statusText = "idle"

    private data class DownlinkConfig(
        val recorder: AudioRecord,
        val sampleRate: Int,
        val label: String
    )

    private data class UplinkConfig(
        val track: AudioTrack?,
        val sampleRate: Int,
        val channels: Int,
        val routedToTelephony: Boolean,
        val label: String
    )

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentApp = atClass.getMethod("currentApplication").invoke(null) as? Context
            currentApp ?: (atClass.getMethod("systemMain").invoke(null)?.let {
                atClass.getMethod("getSystemContext").invoke(it) as? Context
            })
        } catch (_: Throwable) {
            null
        }
    }

    private fun findTelephonyOutputDevice(): AudioDeviceInfo? {
        val ctx = resolveContext() ?: return null
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun openBestDownlink(startNow: Boolean): Pair<DownlinkConfig?, List<String>> {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_CALL to "VOICE_CALL",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "VOICE_DOWNLINK"
        )
        val rates = listOf(16000, 48000, 44100, 8000)
        val errors = mutableListOf<String>()

        for ((src, name) in sources) {
            for (rate in rates) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf <= 0) continue
                    val bufSize = minBuf.coerceAtLeast((rate / 50) * 2 * 6)
                    val rec = AudioRecord(
                        src,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        if (!startNow) {
                            rec.release()
                            return DownlinkConfig(rec, rate, "$name@${rate}Hz") to errors
                        }
                        rec.startRecording()
                        if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            return DownlinkConfig(rec, rate, "$name@${rate}Hz") to errors
                        }
                        rec.stop()
                    }
                    rec.release()
                    errors.add("$name@$rate: init/record failed")
                } catch (t: Throwable) {
                    errors.add("$name@$rate: ${t.javaClass.simpleName}")
                }
            }
        }
        return null to errors
    }

    private fun buildBestUplinkTrack(telephonyDev: AudioDeviceInfo?): UplinkConfig {
        val candidateRates = buildList {
            if (telephonyDev != null && telephonyDev.sampleRates.isNotEmpty()) {
                val devRates = telephonyDev.sampleRates.toList()
                for (pref in listOf(16000, 48000, 44100, 8000)) {
                    if (pref in devRates) add(pref)
                }
                devRates.forEach { if (it !in this) add(it) }
            } else {
                addAll(listOf(48000, 44100, 16000))
            }
        }

        val candidateChannels = buildList {
            val masks = telephonyDev?.channelMasks?.toList().orEmpty()
            if (masks.isNotEmpty()) {
                if (AudioFormat.CHANNEL_OUT_MONO in masks || 1 in masks) add(1)
                if (AudioFormat.CHANNEL_OUT_STEREO in masks || 3 in masks) add(2)
            }
            if (isEmpty()) {
                add(2)
                add(1)
            }
        }

        val usages = listOf<Pair<Int, String>>(
            AudioAttributes.USAGE_VOICE_COMMUNICATION to "VOICE_COMM",
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING to "VOICE_SIGNAL",
            AudioAttributes.USAGE_MEDIA to "MEDIA_TELEPHONY"
        )

        for ((usage, usageName) in usages) {
            for (rate in candidateRates) {
                for (ch in candidateChannels) {
                    val chMask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                    try {
                        val minPlay = AudioTrack.getMinBufferSize(
                            rate,
                            chMask,
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (minPlay <= 0) continue
                        val bufSize = minPlay.coerceAtLeast((rate / 50) * ch * 2 * 6)
                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(usage)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(rate)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setChannelMask(chMask)
                                    .build()
                            )
                            .setBufferSizeInBytes(bufSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()

                        if (track.state == AudioTrack.STATE_INITIALIZED) {
                            var routedToTelephony = false
                            if (telephonyDev != null) {
                                val prefOk = runCatching { track.setPreferredDevice(telephonyDev) }.getOrDefault(false)
                                val routed = runCatching { track.routedDevice?.type == AudioDeviceInfo.TYPE_TELEPHONY }.getOrDefault(false)
                                routedToTelephony = prefOk || routed
                            }
                            return UplinkConfig(
                                track = track,
                                sampleRate = rate,
                                channels = ch,
                                routedToTelephony = routedToTelephony,
                                label = if (routedToTelephony) "TYPE_TELEPHONY($usageName@${rate}Hz/${ch}ch)" else "$usageName@${rate}Hz/${ch}ch"
                            )
                        }
                        track.release()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return UplinkConfig(null, 16000, 1, false, "uplink_unavailable")
    }

    override fun probeAudioCapabilities(): String {
        val telephonyDev = findTelephonyOutputDevice()
        val devRates = JSONArray()
        telephonyDev?.sampleRates?.forEach { devRates.put(it) }

        val (downlinkCfg, dlErrors) = openBestDownlink(startNow = false)
        val downlinkOk = downlinkCfg != null
        val downlinkSource = downlinkCfg?.label ?: dlErrors.firstOrNull() ?: "blocked"

        val uplinkCfg = buildBestUplinkTrack(telephonyDev)
        val uplinkOk = uplinkCfg.track != null && uplinkCfg.routedToTelephony
        val uplinkLabel = uplinkCfg.label
        runCatching { uplinkCfg.track?.release() }

        val verdict = when {
            downlinkOk && uplinkOk -> "two_way_direct"
            downlinkOk -> "downlink_only"
            else -> "fallback_only"
        }

        return JSONObject()
            .put("downlinkOk", downlinkOk)
            .put("downlinkSource", downlinkSource)
            .put("uplinkDeviceFound", telephonyDev != null)
            .put("uplinkOk", uplinkOk)
            .put("uplinkLabel", uplinkLabel)
            .put("telephonyRates", devRates)
            .put("verdict", verdict)
            .toString()
    }

    @SuppressLint("MissingPermission")
    override fun startAudioCapture(): Int {
        stopAudioCapture()

        val (downlink, errors) = openBestDownlink(startNow = true)
        if (downlink == null) {
            statusText = "blocked: ${errors.take(2).joinToString("; ")}"
            return -1
        }

        val telephonyDev = findTelephonyOutputDevice()
        val uplink = buildBestUplinkTrack(telephonyDev)
        if (uplink.track == null) {
            runCatching { downlink.recorder.stop() }
            runCatching { downlink.recorder.release() }
            statusText = "uplink init failed"
            return -1
        }

        return try {
            val ss = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running = true
            statusText = "${downlink.label} + ${uplink.label}"
            val boundPort = ss.localPort

            thread(start = true, isDaemon = true, name = "OB-PrivAudio") {
                streamLoop(ss, downlink, uplink)
            }
            boundPort
        } catch (t: Throwable) {
            runCatching { downlink.recorder.stop() }
            runCatching { downlink.recorder.release() }
            runCatching { uplink.track.release() }
            statusText = "socket error: ${t.message}"
            -1
        }
    }

    private fun streamLoop(ss: ServerSocket, downlink: DownlinkConfig, uplink: UplinkConfig) {
        var client: Socket? = null
        val rec = downlink.recorder
        val player = uplink.track
        try {
            ss.soTimeout = 10_000
            client = ss.accept()
            client.tcpNoDelay = true

            if (player != null && player.state == AudioTrack.STATE_INITIALIZED) {
                player.play()
            }

            val out = client.getOutputStream()
            val inp = client.getInputStream()

            val rxThread = thread(start = true, isDaemon = true, name = "OB-PrivUplink") {
                val netBuf = ByteArray(2048)
                val leftover = ByteArrayOutputStream()
                try {
                    while (running && !client.isClosed && player != null) {
                        val r = inp.read(netBuf)
                        if (r <= 0) break
                        leftover.write(netBuf, 0, r)
                        val all = leftover.toByteArray()
                        val evenLen = all.size and 0xFFFE
                        if (evenLen > 0) {
                            val mono16k = all.copyOfRange(0, evenLen)
                            val converted = PcmResampler.resampleAndExpand(
                                mono16k = mono16k,
                                dstRate = uplink.sampleRate,
                                dstChannels = uplink.channels
                            )
                            if (converted.isNotEmpty()) {
                                player.write(converted, 0, converted.size)
                            }
                            leftover.reset()
                            if (all.size > evenLen) leftover.write(all, evenLen, all.size - evenLen)
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            val chunkSize = ((downlink.sampleRate / 50) * 2).coerceAtLeast(RELAY_CHUNK_BYTES)
            val recBuf = ByteArray(chunkSize)
            while (running && !client.isClosed) {
                val r = rec.read(recBuf, 0, recBuf.size)
                if (r > 1) {
                    val mono = if (r == recBuf.size) recBuf else recBuf.copyOf(r and 0xFFFE)
                    val relayPcm = PcmResampler.resampleMono(mono, downlink.sampleRate, RELAY_SAMPLE_RATE)
                    if (relayPcm.isNotEmpty()) {
                        out.write(relayPcm, 0, relayPcm.size)
                        out.flush()
                    }
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
