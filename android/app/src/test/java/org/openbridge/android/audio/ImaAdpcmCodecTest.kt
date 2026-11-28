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

        // 160 samples @ 8 kHz = 320 bytes PCM16
        val pcmIn = ByteArray(ImaAdpcmCodec.FRAME_SAMPLES_8K * 2)
        for (i in 0 until ImaAdpcmCodec.FRAME_SAMPLES_8K) {
            val sample = (sin(2.0 * Math.PI * 500.0 * i / 8000.0) * 12000.0).toInt().coerceIn(-32768, 32767)
            pcmIn[i * 2] = (sample and 0xFF).toByte()
            pcmIn[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }

        val packet = encoder.encodeFrame(pcmIn)
        assertEquals(ImaAdpcmCodec.PACKET_PLAINTEXT_BYTES, packet.size)

        val decoded = decoder.decodeFrame(packet)
        assertNotNull(decoded)
        assertEquals(0, decoded!!.first)
        assertEquals(pcmIn.size, decoded.second.size)

        var maxErr = 0
        for (i in 0 until ImaAdpcmCodec.FRAME_SAMPLES_8K) {
            val sIn = ((pcmIn[i * 2 + 1].toInt() shl 8) or (pcmIn[i * 2].toInt() and 0xFF)).toShort().toInt()
            val sOut = ((decoded.second[i * 2 + 1].toInt() shl 8) or (decoded.second[i * 2].toInt() and 0xFF)).toShort().toInt()
            maxErr = maxOf(maxErr, abs(sIn - sOut))
        }
        assertTrue("ADPCM distortion too high: maxErr=$maxErr", maxErr < 2500)
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
