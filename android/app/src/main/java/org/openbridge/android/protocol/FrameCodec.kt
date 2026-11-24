package org.openbridge.android.protocol

import java.io.ByteArrayOutputStream

/**
 * Stream framing codec: [0x4F, 0x42, len_hi, len_lo, payload...]
 */
object FrameCodec {
    fun encode(payload: ByteArray): ByteArray {
        require(payload.size <= BridgeConstants.MAX_FRAME_SIZE) {
            "Payload too large: ${payload.size}"
        }
        val out = ByteArray(BridgeConstants.HEADER_SIZE + payload.size)
        out[0] = BridgeConstants.MAGIC_0
        out[1] = BridgeConstants.MAGIC_1
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, out, BridgeConstants.HEADER_SIZE, payload.size)
        return out
    }

    fun chunk(frame: ByteArray, maxChunkSize: Int): List<ByteArray> {
        val safeChunk = maxChunkSize.coerceAtLeast(20)
        if (frame.size <= safeChunk) return listOf(frame)
        val chunks = ArrayList<ByteArray>((frame.size + safeChunk - 1) / safeChunk)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + safeChunk, frame.size)
            chunks.add(frame.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    class StreamDecoder {
        private val buffer = ByteArrayOutputStream()

        @Synchronized
        fun append(data: ByteArray): List<ByteArray> {
            if (data.isNotEmpty()) {
                buffer.write(data)
            }
            val frames = mutableListOf<ByteArray>()
            var bytes = buffer.toByteArray()
            var offset = 0

            while (bytes.size - offset >= BridgeConstants.HEADER_SIZE) {
                if (bytes[offset] != BridgeConstants.MAGIC_0 ||
                    bytes[offset + 1] != BridgeConstants.MAGIC_1
                ) {
                    offset++
                    continue
                }
                val len = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)

                if (bytes.size - offset < BridgeConstants.HEADER_SIZE + len) {
                    break
                }
                val start = offset + BridgeConstants.HEADER_SIZE
                frames.add(bytes.copyOfRange(start, start + len))
                offset = start + len
            }

            if (offset > 0) {
                val remaining = if (offset < bytes.size) {
                    bytes.copyOfRange(offset, bytes.size)
                } else {
                    ByteArray(0)
                }
                buffer.reset()
                if (remaining.isNotEmpty()) {
                    buffer.write(remaining)
                }
            }
            return frames
        }

        @Synchronized
        fun reset() {
            buffer.reset()
        }
    }
}
