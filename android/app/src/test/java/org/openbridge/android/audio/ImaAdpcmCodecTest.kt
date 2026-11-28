package org.openbridge.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class ImaAdpcmCodecTest {

    @Test
    fun roundTripSineWaveMaintainsShapeAndLength() {
        val encoder = ImaAdpcmCodec()
        val decoder = ImaAdpcmCodec()

        // Warm up 1 frame so stepIndex adapts from initial 7 to signal amplitude,
        // then verify frame 1 steady-state quantization error stays tight.
        var lastPcmIn = ByteArray(ImaAdpcmCodec.FRAME_SAMPLES_8K * 2)
        var lastPacket = ByteArray(0)
        for (frame in 0..1) {
            val pcmIn = ByteArray(ImaAdpcmCodec.FRAME_SAMPLES_8K * 2)
            for (i in 0 until ImaAdpcmCodec.FRAME_SAMPLES_8K) {
                val sampleIndex = frame * ImaAdpcmCodec.FRAME_SAMPLES_8K + i
                val sample = (sin(2.0 * Math.PI * 500.0 * sampleIndex / 8000.0) * 12000.0)
                    .toInt()
                    .coerceIn(-32768, 32767)
                pcmIn[i * 2] = (sample and 0xFF).toByte()
                pcmIn[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }
            lastPcmIn = pcmIn
            lastPacket = encoder.encodeFrame(pcmIn)
        }

        assertEquals(ImaAdpcmCodec.PACKET_PLAINTEXT_BYTES, lastPacket.size)

        val decoded = decoder.decodeFrame(lastPacket)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.first)
        assertEquals(lastPcmIn.size, decoded.second.size)

        var maxErr = 0
        for (i in 0 until ImaAdpcmCodec.FRAME_SAMPLES_8K) {
            val sIn = ((lastPcmIn[i * 2 + 1].toInt() shl 8) or (lastPcmIn[i * 2].toInt() and 0xFF)).toShort().toInt()
            val sOut = ((decoded.second[i * 2 + 1].toInt() shl 8) or (decoded.second[i * 2].toInt() and 0xFF)).toShort().toInt()
            maxErr = maxOf(maxErr, abs(sIn - sOut))
        }
        assertTrue("ADPCM steady-state distortion too high: maxErr=$maxErr", maxErr < 1000)
    }

    @Test
    fun packetLossSelfRecoversOnNextFrame() {
        val encoder = ImaAdpcmCodec()
        val decoder = ImaAdpcmCodec()

        val frame0 = ByteArray(320)
        val frame1 = ByteArray(320) { idx -> if (idx % 2 == 0) 0x00 else 0x10 }
        encoder.encodeFrame(frame0) // dropped
        val pkt1 = encoder.encodeFrame(frame1)

        val decoded1 = decoder.decodeFrame(pkt1)
        assertNotNull(decoded1)
        assertEquals(1, decoded1!!.first)
        assertEquals(320, decoded1.second.size)
    }
}
