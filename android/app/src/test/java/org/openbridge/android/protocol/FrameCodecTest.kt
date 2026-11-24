package org.openbridge.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class FrameCodecTest {
    @Test
    fun chunkedStreamDecodesMultipleFrames() {
        val p1 = "{\"type\":\"PING\"}".toByteArray(StandardCharsets.UTF_8)
        val p2 = ("{\"msg\":\"" + "A".repeat(400) + "\"}").toByteArray(StandardCharsets.UTF_8)

        val stream = FrameCodec.encode(p1) + FrameCodec.encode(p2)
        val chunks = FrameCodec.chunk(stream, 20)

        val decoder = FrameCodec.StreamDecoder()
        val decoded = mutableListOf<ByteArray>()
        for (chunk in chunks) {
            decoded.addAll(decoder.append(chunk))
        }

        assertEquals(2, decoded.size)
        assertArrayEquals(p1, decoded[0])
        assertArrayEquals(p2, decoded[1])
    }
}
