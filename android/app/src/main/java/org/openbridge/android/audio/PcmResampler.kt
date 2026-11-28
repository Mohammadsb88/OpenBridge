package org.openbridge.android.audio

import kotlin.math.roundToInt

/**
 * Lightweight PCM-16 linear resampler and channel layout converter.
 * Bridges the 16 kHz Mono internal relay format with any vendor Telephony HAL
 * (e.g., 48 kHz Stereo on MediaTek, 16/48 kHz Mono on Qualcomm/Pixel).
 */
object PcmResampler {

    fun resampleMono(srcPcm: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        val evenLen = srcPcm.size and 0xFFFE
        if (evenLen <= 0 || srcRate <= 0 || dstRate <= 0) return ByteArray(0)
        if (srcRate == dstRate) {
            return if (evenLen == srcPcm.size) srcPcm else srcPcm.copyOf(evenLen)
        }

        val inSamples = evenLen / 2
        if (inSamples == 1) {
            val sLo = srcPcm[0]
            val sHi = srcPcm[1]
            val outSamples = (dstRate.toDouble() / srcRate).roundToInt().coerceAtLeast(1)
            val out = ByteArray(outSamples * 2)
            for (i in 0 until outSamples) {
                out[i * 2] = sLo
                out[i * 2 + 1] = sHi
            }
            return out
        }

        val outSamples = ((inSamples.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = ByteArray(outSamples * 2)
        val ratio = (inSamples - 1).toDouble() / (outSamples - 1).coerceAtLeast(1)

        for (i in 0 until outSamples) {
            val pos = i * ratio
            val idx0 = pos.toInt().coerceIn(0, inSamples - 1)
            val idx1 = (idx0 + 1).coerceAtMost(inSamples - 1)
            val frac = pos - idx0

            val s0 = readSample(srcPcm, idx0)
            val s1 = readSample(srcPcm, idx1)
            val interp = (s0 + (s1 - s0) * frac).roundToInt().coerceIn(-32768, 32767)
            writeSample(out, i, interp)
        }
        return out
    }

    fun monoToChannels(monoPcm: ByteArray, channels: Int): ByteArray {
        val evenLen = monoPcm.size and 0xFFFE
        if (evenLen <= 0) return ByteArray(0)
        if (channels <= 1) {
            return if (evenLen == monoPcm.size) monoPcm else monoPcm.copyOf(evenLen)
        }
        val samples = evenLen / 2
        val out = ByteArray(samples * channels * 2)
        var outIdx = 0
        for (i in 0 until samples) {
            val lo = monoPcm[i * 2]
            val hi = monoPcm[i * 2 + 1]
            for (c in 0 until channels) {
                out[outIdx++] = lo
                out[outIdx++] = hi
            }
        }
        return out
    }

    fun resampleAndExpand(mono16k: ByteArray, dstRate: Int, dstChannels: Int): ByteArray {
        val resampled = resampleMono(mono16k, 16000, dstRate)
        return monoToChannels(resampled, dstChannels)
    }

    /**
     * Converts 16 kHz mono PCM16 -> 8 kHz mono PCM16 using a 2-tap anti-alias average.
     */
    fun downsample16kTo8k(pcm16k: ByteArray): ByteArray {
        val sampleCount = (pcm16k.size and 0xFFFE) / 2
        val outCount = sampleCount / 2
        if (outCount <= 0) return ByteArray(0)
        val out = ByteArray(outCount * 2)
        for (i in 0 until outCount) {
            val s0 = readSample(pcm16k, i * 2)
            val s1 = readSample(pcm16k, i * 2 + 1)
            val avg = ((s0 + s1) / 2).coerceIn(-32768, 32767)
            writeSample(out, i, avg)
        }
        return out
    }

    /**
     * Converts 8 kHz mono PCM16 -> 16 kHz mono PCM16 via linear interpolation.
     */
    fun upsample8kTo16k(pcm8k: ByteArray): ByteArray {
        val sampleCount = (pcm8k.size and 0xFFFE) / 2
        if (sampleCount <= 0) return ByteArray(0)
        val out = ByteArray(sampleCount * 4)
        for (i in 0 until sampleCount) {
            val cur = readSample(pcm8k, i)
            val next = if (i + 1 < sampleCount) readSample(pcm8k, i + 1) else cur
            val mid = ((cur + next) / 2).coerceIn(-32768, 32767)
            writeSample(out, i * 2, cur)
            writeSample(out, i * 2 + 1, mid)
        }
        return out
    }

    private fun readSample(buf: ByteArray, index: Int): Int {
        val lo = buf[index * 2].toInt() and 0xFF
        val hi = buf[index * 2 + 1].toInt()
        return ((hi shl 8) or lo).toShort().toInt()
    }

    private fun writeSample(buf: ByteArray, index: Int, sample: Int) {
        buf[index * 2] = (sample and 0xFF).toByte()
        buf[index * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }
}
