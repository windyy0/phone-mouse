package com.windy.phonemouse.app

import java.nio.ByteBuffer

internal object WireProtocol {
    const val VERSION = 1
    const val UDP_PACKET_SIZE = 40

    fun decodeToken(value: String): ByteArray? {
        if (value.length != 32 || value.any { it !in "0123456789abcdefABCDEF" }) return null
        val token = ByteArray(16)
        for (index in token.indices) {
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            token[index] = ((high shl 4) or low).toByte()
        }
        return token
    }

    fun motionPacket(token: ByteArray, sequence: Long, packet: MotionPacket): ByteArray {
        require(token.size == 16 && sequence > 0)
        return ByteBuffer.allocate(UDP_PACKET_SIZE)
            .put(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), '0'.code.toByte(), '1'.code.toByte()))
            .put(token)
            .putLong(sequence)
            .putInt(packet.dx)
            .putInt(packet.dy)
            .putInt(packet.wheel)
            .array()
    }
}
