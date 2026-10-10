package com.windy.phonemouse.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Exercises the real client worker and WebSocket framing without injecting OS input. */
class RemoteClientPresentationTest {
    @Test fun modeBarrierWaitsForMatchingAckPreservesKeyboardAndClearsOnBackgroundAndReconnect() {
        Receiver(laser = true).use { receiver ->
            val connection = AtomicReference(ConnectionState())
            val state = AtomicReference(PresentationState())
            val client = RemoteClient(connection::set, {}, { _, _, _ -> }, { next, _ -> state.set(next) })
            try {
                client.connect(receiver.address)
                await { connection.get().connected && state.get().supported }
                client.togglePresentation()
                val request = receiver.next("presentation_set")
                await { state.get().switching }
                client.move(10.0, 10.0)
                client.buttonDown("left")
                client.scroll(120.0)
                client.touchGesture(120.0, 120.0)
                client.key("enter")
                assertEquals("enter", receiver.next("key").getString("key"))
                receiver.reply(JSONObject().put("type", "presentation_state").put("id", request.getLong("id") + 999)
                    .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                Thread.sleep(100)
                assertTrue(state.get().switching)
                assertNull(receiver.commands.poll(150, TimeUnit.MILLISECONDS))
                assertThrows(SocketTimeoutException::class.java) { receiver.motion() }
                receiver.reply(JSONObject().put("type", "presentation_state").put("id", request.getLong("id"))
                    .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                await { state.get().laser && !state.get().switching }
                // The client synchronizes current settings after the mode acknowledgement.
                receiver.next("laser_settings")
                client.move(20.0, 10.0)
                val motion = receiver.motion()
                assertEquals(40, motion.size)
                assertArrayEquals(WireProtocol.decodeToken(NEXT_TOKEN), motion.copyOfRange(4, 20))
                client.buttonDown("left")
                assertEquals("left", receiver.next("button_down").getString("button"))
                client.buttonUp("left")
                receiver.next("button_up")
                client.touchGesture(120.0, 120.0)
                assertEquals(120, receiver.next("touch_gesture").getInt("zoom"))
                client.setLaserSettings(LaserSettings(LaserColor.Blue, false, 1700))
                val settings = receiver.next("laser_settings").getJSONObject("settings")
                assertEquals(LaserColor.Blue.rgb, settings.getInt("color"))
                assertFalse(settings.getBoolean("trail"))
                assertEquals(1700, settings.getInt("fade_ms"))
                client.setForeground(false)
                await { !state.get().laser && !state.get().supported && !connection.get().connected }
                Receiver(laser = true).use { fresh ->
                    client.connect(fresh.address)
                    client.setForeground(true)
                    await { state.get().supported && connection.get().connected }
                    assertFalse(state.get().laser)
                    assertFalse(state.get().switching)
                }
            } finally { client.destroy() }
        }
    }

    @Test fun oldReceiverKeepsOrdinaryMouseAvailableWithoutSendingLaserCommands() {
        Receiver(laser = false).use { receiver ->
            val connection = AtomicReference(ConnectionState())
            val state = AtomicReference(PresentationState())
            val notices = LinkedBlockingQueue<String>()
            val client = RemoteClient(connection::set, { notices.offer(it) }, { _, _, _ -> }, { next, _ -> state.set(next) })
            try {
                client.connect(receiver.address)
                await { connection.get().connected }
                assertFalse(state.get().supported)
                assertTrue(notices.poll(2, TimeUnit.SECONDS).contains("更新 Windows"))
                client.togglePresentation()
                client.click("middle")
                assertEquals("middle", receiver.next("click").getString("button"))
                assertNull(receiver.commands.poll(150, TimeUnit.MILLISECONDS))
            } finally { client.destroy() }
        }
    }

    @Test fun sizeUpdatesAreCoalescedAndFinalValueArrivesWithoutResettingInput() {
        Receiver(laser = true, laserSize = true, extendedRange = true).use { receiver ->
            val connection = AtomicReference(ConnectionState())
            val state = AtomicReference(PresentationState())
            val client = RemoteClient(connection::set, {}, { _, _, _ -> }, { next, _ -> state.set(next) })
            try {
                client.setLaserSettings(LaserSettings(sizePercent = 50))
                client.connect(receiver.address)
                await { connection.get().connected && state.get().sizeSupported }
                client.togglePresentation()
                val mode = receiver.next("presentation_set")
                assertEquals(50, mode.getJSONObject("settings").getInt("size_percent"))
                receiver.reply(JSONObject().put("type", "presentation_state").put("id", mode.getLong("id"))
                    .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                await { state.get().laser && !state.get().switching }
                assertEquals(50, receiver.next("laser_settings").getJSONObject("settings").getInt("size_percent"))
                val epoch = state.get().inputEpoch
                // A fast drag generates 50 values; the peer should receive a
                // small number of updates, ending with the exact last value.
                for (size in 55..300 step 5) {
                    client.setLaserSettings(LaserSettings(sizePercent = size))
                    Thread.sleep(2)
                }
                client.flushLaserSettings()
                val received = mutableListOf<JSONObject>()
                do { received += receiver.next("laser_settings") }
                while (received.last().getJSONObject("settings").getInt("size_percent") != 300)
                assertTrue("Slider writes were not coalesced", received.size <= 3)
                receiver.reply(JSONObject().put("type", "presentation_state").put("id", received.last().getLong("id"))
                    .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                client.flushLaserSettings()
                assertNull(receiver.commands.poll(150, TimeUnit.MILLISECONDS))
                assertEquals(epoch, state.get().inputEpoch)
                client.move(12.0, 4.0)
                assertArrayEquals(WireProtocol.decodeToken(NEXT_TOKEN), receiver.motion().copyOfRange(4, 20))
                // Pending writes and capability flags must not leak into the
                // next connection, which supports laser but predates sizing.
                client.setLaserSettings(LaserSettings(sizePercent = 195))
                client.setLaserSettings(LaserSettings(sizePercent = 190))
                client.disconnect()
                Receiver(laser = true).use { legacy ->
                    client.connect(legacy.address)
                    await { connection.get().connected && state.get().supported && !state.get().sizeSupported }
                    assertFalse(state.get().laser)
                    assertNull(legacy.commands.poll(150, TimeUnit.MILLISECONDS))
                    client.togglePresentation()
                    assertFalse(legacy.next("presentation_set").getJSONObject("settings").has("size_percent"))
                }
            } finally { client.destroy() }
        }
    }

    @Test fun legacyLaserReceiverKeepsLaserSettingsWorkingAndOmitsSize() {
        Receiver(laser = true).use { receiver ->
            val connection = AtomicReference(ConnectionState())
            val state = AtomicReference(PresentationState())
            val client = RemoteClient(connection::set, {}, { _, _, _ -> }, { next, _ -> state.set(next) })
            try {
                client.setLaserSettings(LaserSettings(sizePercent = 200))
                client.connect(receiver.address)
                await { connection.get().connected && state.get().supported }
                assertFalse(state.get().sizeSupported)
                client.togglePresentation()
                val mode = receiver.next("presentation_set")
                assertFalse(mode.getJSONObject("settings").has("size_percent"))
                receiver.reply(JSONObject().put("type", "presentation_state").put("id", mode.getLong("id"))
                    .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                await { state.get().laser && !state.get().switching }
                assertFalse(receiver.next("laser_settings").getJSONObject("settings").has("size_percent"))
                client.setLaserSettings(LaserSettings(sizePercent = 175))
                client.flushLaserSettings()
                assertNull(receiver.commands.poll(150, TimeUnit.MILLISECONDS))
                client.setLaserSettings(LaserSettings(LaserColor.Blue, sizePercent = 150))
                val changed = receiver.next("laser_settings").getJSONObject("settings")
                assertEquals(LaserColor.Blue.rgb, changed.getInt("color"))
                assertFalse(changed.has("size_percent"))
                client.click("right")
                assertEquals("right", receiver.next("click").getString("button"))
            } finally { client.destroy() }
        }
    }

    @Test fun trailLengthUpdatesAreCoalescedAndFinalValueArrivesWithoutResettingInput() {
        Receiver(laser = true, laserSize = true, laserTrailLength = true, extendedRange = true).use { receiver ->
            val connection = AtomicReference(ConnectionState())
            val state = AtomicReference(PresentationState())
            val client = RemoteClient(connection::set, {}, { _, _, _ -> }, { next, _ -> state.set(next) })
            try {
                client.setLaserSettings(LaserSettings(sizePercent = 175, trailLengthPx = 20))
                client.connect(receiver.address)
                await { connection.get().connected && state.get().trailLengthSupported }
                client.togglePresentation()
                val mode = receiver.next("presentation_set")
                assertEquals(20, mode.getJSONObject("settings").getInt("trail_length_px"))
                receiver.reply(JSONObject().put("type", "presentation_state").put("id", mode.getLong("id"))
                    .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                await { state.get().laser && !state.get().switching }
                assertEquals(20, receiver.next("laser_settings").getJSONObject("settings").getInt("trail_length_px"))
                val epoch = state.get().inputEpoch
                for (length in 30..300 step 10) {
                    client.setLaserSettings(LaserSettings(sizePercent = 175, trailLengthPx = length))
                    Thread.sleep(2)
                }
                client.flushLaserSettings()
                val received = mutableListOf<JSONObject>()
                do { received += receiver.next("laser_settings") }
                while (received.last().getJSONObject("settings").getInt("trail_length_px") != 300)
                assertTrue("Trail length slider writes were not coalesced", received.size <= 3)
                assertEquals(175, received.last().getJSONObject("settings").getInt("size_percent"))
                receiver.reply(JSONObject().put("type", "presentation_state").put("id", received.last().getLong("id"))
                    .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                client.flushLaserSettings()
                assertNull(receiver.commands.poll(150, TimeUnit.MILLISECONDS))
                assertEquals(epoch, state.get().inputEpoch)
                client.move(12.0, 4.0)
                assertArrayEquals(WireProtocol.decodeToken(NEXT_TOKEN), receiver.motion().copyOfRange(4, 20))
                client.setLaserSettings(LaserSettings(trailLengthPx = 190))
                client.setLaserSettings(LaserSettings(trailLengthPx = 180))
                client.disconnect()
                Receiver(laser = true, laserSize = true).use { legacy ->
                    client.connect(legacy.address)
                    await { connection.get().connected && state.get().supported && !state.get().trailLengthSupported }
                    assertFalse(state.get().laser)
                    client.togglePresentation()
                    assertFalse(legacy.next("presentation_set").getJSONObject("settings").has("trail_length_px"))
                }
            } finally { client.destroy() }
        }
    }

    @Test fun trailLengthCapabilityIsIndependentAndLegacyLengthChangesAreOmitted() {
        for (supportsLength in listOf(false, true)) {
            Receiver(laser = true, laserSize = !supportsLength, laserTrailLength = supportsLength).use { receiver ->
                val connection = AtomicReference(ConnectionState())
                val state = AtomicReference(PresentationState())
                val client = RemoteClient(connection::set, {}, { _, _, _ -> }, { next, _ -> state.set(next) })
                try {
                    client.setLaserSettings(LaserSettings(sizePercent = 175, trailLengthPx = 200))
                    client.connect(receiver.address)
                    await { connection.get().connected && state.get().supported }
                    assertEquals(supportsLength, state.get().trailLengthSupported)
                    assertEquals(!supportsLength, state.get().sizeSupported)
                    client.togglePresentation()
                    val mode = receiver.next("presentation_set")
                    assertEquals(supportsLength, mode.getJSONObject("settings").has("trail_length_px"))
                    assertEquals(!supportsLength, mode.getJSONObject("settings").has("size_percent"))
                    receiver.reply(JSONObject().put("type", "presentation_state").put("id", mode.getLong("id"))
                        .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                    await { state.get().laser && !state.get().switching }
                    assertEquals(supportsLength, receiver.next("laser_settings").getJSONObject("settings").has("trail_length_px"))
                    client.setLaserSettings(LaserSettings(sizePercent = 175, trailLengthPx = 20))
                    client.flushLaserSettings()
                    if (supportsLength) {
                        assertEquals(20, receiver.next("laser_settings").getJSONObject("settings").getInt("trail_length_px"))
                    } else {
                        assertNull(receiver.commands.poll(150, TimeUnit.MILLISECONDS))
                    }
                    client.setLaserSettings(LaserSettings(LaserColor.Blue, sizePercent = 175, trailLengthPx = 20))
                    val changed = receiver.next("laser_settings").getJSONObject("settings")
                    assertEquals(LaserColor.Blue.rgb, changed.getInt("color"))
                    assertEquals(supportsLength, changed.has("trail_length_px"))
                    client.click("right")
                    assertEquals("right", receiver.next("click").getString("button"))
                } finally { client.destroy() }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for client state", condition())
    }

    private companion object {
        const val INITIAL_TOKEN = "11111111111111111111111111111111"
        const val NEXT_TOKEN = "22222222222222222222222222222222"
    }

    /** Minimal peer: application commands are queued, heartbeat pings are answered. */
    @Test fun legacyRangeClampsWireValuesAndReconnectRestoresThePreferredMaximum() {
        val connection = AtomicReference(ConnectionState())
        val state = AtomicReference(PresentationState())
        val client = RemoteClient(connection::set, {}, { _, _, _ -> }, { next, _ -> state.set(next) })
        try {
            client.setLaserSettings(LaserSettings(sizePercent = 300, trailLengthPx = 300))
            for (extended in listOf(false, true, false)) {
                Receiver(laser = true, laserSize = true, laserTrailLength = true, extendedRange = extended).use { receiver ->
                    client.connect(receiver.address)
                    await { connection.get().connected && state.get().trailLengthSupported &&
                        state.get().extendedRangeSupported == extended && !state.get().laser }
                    assertEquals(extended, state.get().extendedRangeSupported)
                    client.togglePresentation()
                    val mode = receiver.next("presentation_set")
                    val expected = if (extended) 300 else 200
                    assertEquals(expected, mode.getJSONObject("settings").getInt("size_percent"))
                    assertEquals(expected, mode.getJSONObject("settings").getInt("trail_length_px"))
                    receiver.reply(JSONObject().put("type", "presentation_state").put("id", mode.getLong("id"))
                        .put("mode", "laser").put("udp_token", NEXT_TOKEN))
                    await { state.get().laser && !state.get().switching }
                    val applied = receiver.next("laser_settings").getJSONObject("settings")
                    assertEquals(expected, applied.getInt("size_percent"))
                    assertEquals(expected, applied.getInt("trail_length_px"))
                    client.disconnect()
                }
            }
        } finally { client.destroy() }
    }

    private class Receiver(private val laser: Boolean, private val laserSize: Boolean = false,
        private val laserTrailLength: Boolean = false, private val extendedRange: Boolean = false) : Closeable {
        // Production rejects loopback as a computer address. Bind to this
        // machine's own IPv4; no external receiver or internet is required.
        private val localAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .also { assumeNotNull(it) }
        private val listener = ServerSocket(0, 8, checkNotNull(localAddress))
        private val udp = DatagramSocket(0, localAddress).apply { soTimeout = 500 }
        @Volatile private var socket: Socket? = null
        val address = "${localAddress?.hostAddress}:${listener.localPort}"
        val commands = LinkedBlockingQueue<JSONObject>()
        private val task = thread(isDaemon = true, name = "presentation-test-peer") {
            runCatching {
                val accepted = listener.accept()
                socket = accepted
                val input = accepted.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) {
                    val value = input.read()
                    check(value >= 0)
                    header.append(value.toChar())
                }
                val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    .substringAfter(':').trim()
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                accepted.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                while (true) {
                    val opcode = input.read()
                    if (opcode < 0 || opcode and 15 == 8) break
                    val flags = input.read()
                    var length = flags and 127
                    if (length == 126) length = (input.read() shl 8) or input.read()
                    check(length < 65536)
                    val mask = if (flags and 128 != 0) input.readNBytes(4) else ByteArray(4)
                    val bytes = input.readNBytes(length)
                    check(bytes.size == length)
                    for (index in bytes.indices) bytes[index] = (bytes[index].toInt() xor mask[index % 4].toInt()).toByte()
                    if (opcode and 15 != 1) continue
                    val value = JSONObject(bytes.toString(Charsets.UTF_8))
                    when (value.getString("type")) {
                        "hello" -> {
                            val ready = JSONObject().put("type", "ready").put("protocol", 1).put("udp_port", udp.localPort)
                                .put("udp_token", INITIAL_TOKEN)
                            if (laser) ready.put("features", buildList {
                                add("laser")
                                if (laserSize) add("laser_size")
                                if (laserTrailLength) add("laser_trail_length")
                                if (extendedRange) add("laser_range_300")
                            })
                            reply(ready)
                        }
                        "ping" -> reply(JSONObject().put("type", "pong").put("id", value.getLong("id")))
                        else -> commands.put(value)
                    }
                }
            }
        }
        @Synchronized fun reply(value: JSONObject) {
            val bytes = value.toString().toByteArray()
            val output = checkNotNull(socket).getOutputStream()
            output.write(0x81)
            if (bytes.size < 126) output.write(bytes.size)
            else { output.write(126); output.write(bytes.size shr 8); output.write(bytes.size and 255) }
            output.write(bytes)
            output.flush()
        }
        fun next(type: String): JSONObject {
            val value = checkNotNull(commands.poll(3, TimeUnit.SECONDS)) { "No $type command received" }
            assertEquals(type, value.getString("type"))
            return value
        }
        fun motion(): ByteArray {
            val packet = DatagramPacket(ByteArray(256), 256)
            udp.receive(packet)
            return packet.data.copyOf(packet.length)
        }
        override fun close() {
            socket?.close()
            listener.close()
            udp.close()
            task.join(1000)
        }
    }
}
