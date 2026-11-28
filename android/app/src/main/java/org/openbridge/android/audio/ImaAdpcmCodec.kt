package org.openbridge.android.audio

/**
 * Standard Intel/DVI 4-bit IMA-ADPCM codec operating on 8 kHz mono 20ms frames (160 samples -> 80 bytes).
 * Wire frame plaintext layout (86 bytes):
 *   [0..1] seq (uint16 LE)
 *   [2..3] predictor before sample 0 (int16 LE)
 *   [4]    stepIndex before sample 0 (uint8, 0..88)
 *   [5]    flags (uint8, 0)
 *   [6..85] 160 ADPCM nibbles (even sample = low nibble, odd sample = high nibble)
 */
class ImaAdpcmCodec {
    companion object {
        const val FRAME_SAMPLES_8K = 160
        const val HEADER_BYTES = 6
        const val ADPCM_DATA_BYTES = 80
        const val PACKET_PLAINTEXT_BYTES = HEADER_BYTES + ADPCM_DATA_BYTES

        private val INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )

        private val STEP_SIZE_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
    }

    private var encPredictor: Int = 0
    private var encStepIndex: Int = 0
    private var encSeq: Int = 0

    fun reset() {
        encPredictor = 0
        encStepIndex = 0
        encSeq = 0
    }

    /**
     * Encodes 160 mono 16-bit little-endian PCM samples (320 bytes @ 8kHz) into an 86-byte packet.
     */
    fun encodeFrame(pcm8kBytes: ByteArray): ByteArray {
        val out = ByteArray(PACKET_PLAINTEXT_BYTES)
        val seq = encSeq and 0xFFFF
        encSeq = (encSeq + 1) and 0xFFFF

        // Write pre-frame predictor & stepIndex so every packet is self-contained across packet drops.
        out[0] = (seq and 0xFF).toByte()
        out[1] = ((seq shr 8) and 0xFF).toByte()
        out[2] = (encPredictor and 0xFF).toByte()
        out[3] = ((encPredictor shr 8) and 0xFF).toByte()
        out[4] = (encStepIndex.coerceIn(0, 88) and 0xFF).toByte()
        out[5] = 0

        val availSamples = (pcm8kBytes.size and 0xFFFE) / 2
        for (i in 0 until FRAME_SAMPLES_8K) {
            val sample = if (i < availSamples) {
                val lo = pcm8kBytes[i * 2].toInt() and 0xFF
                val hi = pcm8kBytes[i * 2 + 1].toInt()
                ((hi shl 8) or lo).toShort().toInt()
            } else {
                0
            }

            val step = STEP_SIZE_TABLE[encStepIndex]
            var diff = sample - encPredictor
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var vpdiff = step shr 3
            if (diff >= step) {
                code = code or 4
                diff -= step
                vpdiff += step
            }
            val step2 = step shr 1
            if (diff >= step2) {
                code = code or 2
                diff -= step2
                vpdiff += step2
            }
            val step4 = step shr 2
            if (diff >= step4) {
                code = code or 1
                vpdiff += step4
            }

            if ((code and 8) != 0) {
                encPredictor -= vpdiff
            } else {
                encPredictor += vpdiff
            }
            encPredictor = encPredictor.coerceIn(-32768, 32767)
            encStepIndex = (encStepIndex + INDEX_TABLE[code]).coerceIn(0, 88)

            val outByteIdx = HEADER_BYTES + (i / 2)
            if ((i and 1) == 0) {
                out[outByteIdx] = (code and 0x0F).toByte()
            } else {
                out[outByteIdx] = ((out[outByteIdx].toInt() and 0x0F) or ((code and 0x0F) shl 4)).toByte()
            }
        }
        return out
    }

    /**
     * Decodes an 86-byte packet back into 320 bytes (160 samples) of 8 kHz 16-bit LE mono PCM.
     */
    fun decodeFrame(packet: ByteArray): Pair<Int, ByteArray>? {
        if (packet.size < PACKET_PLAINTEXT_BYTES) return null

        val seq = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
        var predictor = ((packet[3].toInt() shl 8) or (packet[2].toInt() and 0xFF)).toShort().toInt()
        var stepIndex = (packet[4].toInt() and 0xFF).coerceIn(0, 88)

        val pcmOut = ByteArray(FRAME_SAMPLES_8K * 2)
        for (i in 0 until FRAME_SAMPLES_8K) {
            val packed = packet[HEADER_BYTES + (i / 2)].toInt() and 0xFF
            val nibble = if ((i and 1) == 0) (packed and 0x0F) else ((packed shr 4) and 0x0F)

            val step = STEP_SIZE_TABLE[stepIndex]
            var vpdiff = step shr 3
            if ((nibble and 4) != 0) vpdiff += step
            if ((nibble and 2) != 0) vpdiff += (step shr 1)
            if ((nibble and 1) != 0) vpdiff += (step shr 2)

            if ((nibble and 8) != 0) {
                predictor -= vpdiff
            } else {
                predictor += vpdiff
            }
            predictor = predictor.coerceIn(-32768, 32767)
            stepIndex = (stepIndex + INDEX_TABLE[nibble]).coerceIn(0, 88)

            pcmOut[i * 2] = (predictor and 0xFF).toByte()
            pcmOut[i * 2 + 1] = ((predictor shr 8) and 0xFF).toByte()
        }
        return seq to pcmOut
    }
}
