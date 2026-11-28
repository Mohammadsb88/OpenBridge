package org.openbridge.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmResamplerTest {

    @Test
    fun downsampleAndUpsamplePreserveDurationAndDc() {
        val pcm16k = ByteArray(640) // 20ms @ 16kHz = 320 samples
        for (i in 0 until 320) {
            val sample = 2000
            pcm16k[i * 2] = (sample and 0xFF).toByte()
            pcm16k[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        val pcm8k = PcmResampler.downsample16kTo8k(pcm16k)
        assertEquals(320, pcm8k.size)

        val restored16k = PcmResampler.upsample8kTo16k(pcm8k)
        assertEquals(640, restored16k.size)

        val firstSample = ((restored16k[1].toInt() shl 8) or (restored16k[0].toInt() and 0xFF)).toShort().toInt()
        assertEquals(2000, firstSample)
    }

    @Test
    fun resampleAndExpandConverts16kMonoTo48kStereo() {
        val pcm16kMono = ByteArray(640) // 20ms @ 16kHz mono
        for (i in 0 until 320) {
            val sample = (i * 20)
            pcm16kMono[i * 2] = (sample and 0xFF).toByte()
            pcm16kMono[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        val stereo48k = PcmResampler.resampleAndExpand(pcm16kMono, dstRate = 48000, dstChannels = 2)
        // 20ms @ 48kHz * 2 channels * 2 bytes = 3840 bytes
        assertEquals(3840, stereo48k.size)

        // Left and right channels must match
        val left = ((stereo48k[101].toInt() shl 8) or (stereo48k[100].toInt() and 0xFF)).toShort().toInt()
        val right = ((stereo48k[103].toInt() shl 8) or (stereo48k[102].toInt() and 0xFF)).toShort().toInt()
        assertEquals(left, right)
        assertTrue(left > 0)
    }
}
