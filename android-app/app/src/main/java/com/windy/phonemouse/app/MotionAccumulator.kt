package com.windy.phonemouse.app

import kotlin.math.abs

data class MotionPacket(val dx: Int, val dy: Int, val wheel: Int)

/** Fractional movement survives batching; clamping never throws away overflow. */
class MotionAccumulator {
    private var x = 0.0
    private var y = 0.0
    private var wheel = 0.0

    @Synchronized fun move(dx: Double, dy: Double) {
        if (dx.isFinite() && dy.isFinite()) { x += dx; y += dy }
    }
    @Synchronized fun scroll(delta: Double) { if (delta.isFinite()) wheel += delta }
    @Synchronized fun drain(): MotionPacket {
        val dx = x.coerceIn(-2000.0, 2000.0).toInt()
        val dy = y.coerceIn(-2000.0, 2000.0).toInt()
        val scroll = wheel.coerceIn(-2400.0, 2400.0).toInt()
        x -= dx; y -= dy; wheel -= scroll
        return MotionPacket(dx, dy, scroll)
    }
    @Synchronized fun clear() { x = 0.0; y = 0.0; wheel = 0.0 }
    @Synchronized fun clearPointerMotion() { x = 0.0; y = 0.0 }
    @Synchronized fun pending() = abs(x) >= 1 || abs(y) >= 1 || abs(wheel) >= 1
}

fun serverUrl(address: String): String {
    val parts = address.trim().split(":")
    require(parts.size in 1..2) { "请输入电脑 IPv4，可附加端口，例如 192.0.2.10:8765。" }
    val host = parts[0]
    val octets = host.split(".")
    require(octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: -1) in 0..255 }) {
        "请输入有效的电脑 IPv4 地址。"
    }
    require(octets[0].toInt() in 1..223 && octets[0].toInt() != 127) { "请输入可连接的电脑局域网地址。" }
    val port = if (parts.size == 2) parts[1].toIntOrNull() else 8765
    require(port != null && port in 1..65535) { "端口需在 1～65535 之间。" }
    return "ws://$host:$port/ws"
}
