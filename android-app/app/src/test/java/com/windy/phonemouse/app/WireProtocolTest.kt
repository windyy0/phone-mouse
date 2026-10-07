package com.windy.phonemouse.app

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class WireProtocolTest {
    @Test fun encodesPixelMotionWithBigEndianFields() {
        val token = ByteArray(16) { it.toByte() }
        val data = WireProtocol.motionPacket(token, 0x0102030405060708L, MotionPacket(-12, 5, -120))
        assertEquals(40, data.size)
        assertArrayEquals("PM01".toByteArray(Charsets.US_ASCII), data.copyOfRange(0, 4))
        assertArrayEquals(token, data.copyOfRange(4, 20))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), data.copyOfRange(20, 28))
        val fields = ByteBuffer.wrap(data, 28, 12)
        assertEquals(-12, fields.int)
        assertEquals(5, fields.int)
        assertEquals(-120, fields.int)
    }

    @Test fun decodesOnlySixteenByteHexTokens() {
        val expected = ByteArray(16) { (it * 17).toByte() }
        assertArrayEquals(expected, WireProtocol.decodeToken("00112233445566778899aabbccddeeff"))
        assertArrayEquals(expected, WireProtocol.decodeToken("00112233445566778899AABBCCDDEEFF"))
        for (invalid in listOf("", "00".repeat(15), "00".repeat(17), "+a".repeat(16), "gg".repeat(16), "０".repeat(32))) {
            assertNull(WireProtocol.decodeToken(invalid))
        }
    }

    @Test fun rejectsInvalidTokenSizesAndSequenceNumbers() {
        for ((token, sequence) in listOf(ByteArray(15) to 1L, ByteArray(17) to 1L, ByteArray(16) to 0L, ByteArray(16) to -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                WireProtocol.motionPacket(token, sequence, MotionPacket(1, 2, 3))
            }
        }
    }
}
