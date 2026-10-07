package com.windy.phonemouse.app

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class DiscoveredServer(
    val name: String,
    val address: String,
    val port: Int,
) {
    val endpoint get() = "$address:$port"
}

/** UDP broadcast discovery for the local Wi-Fi network. */
class DiscoveryClient(private val onServers: (List<DiscoveredServer>) -> Unit) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var stopped = false

    fun start() {
        executor.scheduleWithFixedDelay({ discover() }, 0, 3, TimeUnit.SECONDS)
    }

    private fun discover() {
        if (stopped) return
        val found = LinkedHashMap<String, DiscoveredServer>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 350
                val request = DatagramPacket(
                    "PHONE_MOUSE_DISCOVER".toByteArray(),
                    "PHONE_MOUSE_DISCOVER".length,
                    InetAddress.getByName("255.255.255.255"),
                    37655,
                )
                socket.send(request)
                val bytes = ByteArray(512)
                val deadline = System.currentTimeMillis() + 850
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(bytes, bytes.size)
                    try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { break }
                    val value = runCatching { JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    if (value.optString("kind") != "phone_mouse_server" || value.optInt("protocol") != WireProtocol.VERSION) continue
                    val port = value.optInt("port", 8765)
                    if (port !in 1..65535) continue
                    val address = packet.address.hostAddress ?: continue
                    found["$address:$port"] = DiscoveredServer(value.optString("name", "Windows 电脑"), address, port)
                }
            }
        }
        onServers(found.values.sortedBy { it.name.lowercase() })
    }

    fun stop() { stopped = true; executor.shutdownNow() }
}
