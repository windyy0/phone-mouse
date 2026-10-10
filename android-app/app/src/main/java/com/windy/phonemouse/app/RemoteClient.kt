package com.windy.phonemouse.app

import okhttp3.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class ConnectionState(val phase: String = "disconnected", val message: String = "手机和电脑连接同一局域网。", val rttMs: Long? = null) {
    val connected get() = phase == "connected"
}

private class UdpMotionSender(
    private val socket: DatagramSocket,
    private val address: InetAddress,
    private val port: Int,
    private val token: ByteArray,
) {
    fun send(sequence: Long, packet: MotionPacket) {
        val data = WireProtocol.motionPacket(token, sequence, packet)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    fun close() = socket.close()
}

/** Input batching is on its own worker, independent of UI rendering. */
class RemoteClient(
    private val onState: (ConnectionState) -> Unit,
    private val onNotice: (String) -> Unit,
    private val onTextResult: (Long, String, String?) -> Unit,
    private val onPresentation: (PresentationState, String?) -> Unit = { _, _ -> },
) {
    private companion object {
        // A larger queue is visible as cursor lag even when the connection
        // itself stays open. At the normal movement packet size this caps the
        // stale input to roughly a tenth of a second on the TCP fallback.
        const val MAX_SOCKET_QUEUE_BYTES = 1_024L
    }

    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val generation = AtomicLong()
    private val ids = AtomicLong()
    private val presentationSession = PresentationSession()
    @Volatile private var presentation = PresentationState()
    @Volatile private var inputBlocked = false
    @Volatile private var laserSettings = LaserSettings()
    private var laserSettingsTask: ScheduledFuture<*>? = null
    private var lastLaserSettingsSentAt: Long? = null
    private var lastLaserSettingsSent: LaserSettings? = null
    private var lastPongAt = 0L
    private val motionDelivery = MotionDeliveryQueue(
        sendMotion = { packet, reliable -> if (reliable) sendTcpMotion(packet) else sendUdpMotion(packet) },
        sendButton = { button, down ->
            sendControl(JSONObject().put("type", if (down) "button_down" else "button_up").put("button", button))
        },
    )
    private val motionFlush = MotionFlushScheduler(worker,
        pending = { ready && motionDelivery.pending() },
        flush = { flushMotion() },
    )
    private val touchGestureMotion = TouchGestureAccumulator()
    private val touchGestureFlush = MotionFlushScheduler(worker,
        pending = { ready && touchGestureMotion.pending() },
        flush = { flushTouchGestures() }, minimumIntervalNanos = 16_000_000L)
    private var inputGeneration = Long.MIN_VALUE
    private val pending = ConcurrentHashMap<Long, String>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var desired = false
    @Volatile private var foreground = true
    @Volatile private var url = ""
    @Volatile private var serverHost = ""
    @Volatile private var udp: UdpMotionSender? = null
    private var udpPort = 0
    @Volatile private var pingId = -1L
    @Volatile private var pingAt = 0L
    @Volatile private var attempts = 0
    @Volatile private var destroyed = false
    private val udpSequence = AtomicLong()

    init {
        // Touchpad history is coalesced in TouchpadView, while this worker keeps
        // network writes off the Android main thread.
        worker.scheduleWithFixedDelay({
            if (ready) {
                if (presentation.laser && !inputBlocked && System.nanoTime() - lastPongAt >= TimeUnit.SECONDS.toNanos(6)) {
                    inputBlocked = true
                    onNotice("激光连接心跳超时，正在重新连接。")
                    socket?.cancel()
                    return@scheduleWithFixedDelay
                }
                val id = ids.incrementAndGet()
                pingId = id; pingAt = System.nanoTime()
                send(JSONObject().put("type", "ping").put("id", id))
            }
        }, 1, 2, TimeUnit.SECONDS)
    }

    fun setReportRate(value: MouseReportRate) {
        if (!destroyed) worker.execute { motionFlush.setRate(value) }
    }

    private fun settingsJson(value: LaserSettings = effectiveLaserSettings()) = JSONObject().put("color", value.color.rgb)
        .put("trail", value.trail).put("fade_ms", value.fadeMs).apply {
            if (presentation.sizeSupported) put("size_percent", value.sizePercent)
            if (presentation.trailLengthSupported) put("trail_length_px", value.trailLengthPx)
        }

    fun setLaserSettings(value: LaserSettings) {
        laserSettings = value.sanitized()
        flushLaserSettings()
    }

    fun flushLaserSettings() {
        val token = generation.get()
        if (!destroyed) worker.execute {
            if (generation.get() == token) queueLaserSettings()
        }
    }

    private fun effectiveLaserSettings() = laserSettings.copy(
        sizePercent = if (presentation.sizeSupported) laserSettings.sizePercent.coerceAtMost(if (presentation.extendedRangeSupported) 300 else 200) else 100,
        trailLengthPx = if (presentation.trailLengthSupported) laserSettings.trailLengthPx.coerceAtMost(if (presentation.extendedRangeSupported) 300 else 200) else 120,
    )

    private fun queueLaserSettings() {
        if (!ready || !presentation.laser || presentation.switching || laserSettingsTask != null
            || effectiveLaserSettings() == lastLaserSettingsSent) return
        val remaining = lastLaserSettingsSentAt?.let { (100_000_000L - (System.nanoTime() - it)).coerceAtLeast(0L) } ?: 0L
        if (remaining == 0L) sendLaserSettings()
        else {
            val token = generation.get()
            laserSettingsTask = worker.schedule({
                laserSettingsTask = null
                if (generation.get() == token && ready && presentation.laser && !presentation.switching) sendLaserSettings()
            }, remaining, TimeUnit.NANOSECONDS)
        }
    }

    private fun sendLaserSettings() {
        val latest = effectiveLaserSettings()
        if (latest == lastLaserSettingsSent) return
        if (sendControl(JSONObject().put("type", "laser_settings").put("settings", settingsJson(latest)).put("id", ids.incrementAndGet()))) {
            lastLaserSettingsSent = latest
            lastLaserSettingsSentAt = System.nanoTime()
        }
    }

    fun togglePresentation() {
        if (destroyed || !ready || inputBlocked) return
        if (!presentation.supported) { onNotice("接收端不支持激光笔，请更新 Windows 接收端。"); return }
        // Block immediately, including tasks already waiting on the worker.
        inputBlocked = true
        val generationAtRequest = generation.get()
        worker.execute {
            if (generation.get() != generationAtRequest || !ready) return@execute
            val id = ids.incrementAndGet()
            val mode = if (presentation.laser) PresentationMode.Mouse else PresentationMode.Laser
            if (!presentationSession.begin(id)) { inputBlocked = false; return@execute }
            publishPresentation()
            if (!sendControl(JSONObject().put("type", "presentation_set").put("id", id)
                    .put("mode", mode.code).put("settings", settingsJson()))) socket?.cancel()
            worker.schedule({
                if (generation.get() == generationAtRequest && presentationSession.pendingId == id) {
                    onNotice("模式切换未确认，正在重新连接。")
                    socket?.cancel()
                }
            }, 5, TimeUnit.SECONDS)
        }
    }

    private fun publishPresentation(reason: String? = null) {
        laserSettingsTask?.cancel(false); laserSettingsTask = null
        lastLaserSettingsSentAt = null; lastLaserSettingsSent = null
        motionFlush.reset(); motionDelivery.reset()
        touchGestureFlush.reset(); touchGestureMotion.clear()
        presentation = presentationSession.state
        inputBlocked = presentation.switching
        onPresentation(presentation, reason)
    }

    private fun resetPresentation() {
        val token = generation.get()
        worker.execute { if (generation.get() == token) { presentationSession.reset(); publishPresentation() } }
    }

    fun connect(address: String) {
        url = serverUrl(address)
        val parts = address.trim().split(":")
        serverHost = parts[0]
        desired = true; attempts = 0
        open()
    }

    private fun open() {
        if (destroyed || !desired || !foreground) return
        val token = generation.incrementAndGet()
        ready = false; resetInput(); resetPresentation(); failPending(); closeUdp()
        socket?.cancel()
        onState(ConnectionState(if (attempts == 0) "connecting" else "reconnecting", "正在连接电脑…"))
        val request = Request.Builder().url(url).build()
        val current = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation.get() != token) { webSocket.cancel(); return }
                webSocket.send(JSONObject().put("type", "hello").put("protocol", WireProtocol.VERSION).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation.get() != token) return
                worker.execute message@{
                    if (generation.get() != token) return@message
                    val reply = runCatching { JSONObject(text) }.getOrNull() ?: return@message
                    when (reply.optString("type")) {
                        "ready" -> {
                            val port = reply.optInt("udp_port")
                            val udpToken = WireProtocol.decodeToken(reply.optString("udp_token"))
                            if (reply.optInt("protocol") != WireProtocol.VERSION || port !in 1..65535 || udpToken == null) {
                                disconnect(); webSocket.close(1000, "Protocol rejected")
                                onNotice("App 与接收端协议不匹配，请同时更新两端。")
                                return@message
                            }
                            openUdp(port, udpToken)
                            val features = reply.optJSONArray("features")
                            val laser = features != null && (0 until features.length()).any { features.optString(it) == "laser" }
                            val size = features != null && (0 until features.length()).any { features.optString(it) == "laser_size" }
                            val length = features != null && (0 until features.length()).any { features.optString(it) == "laser_trail_length" }
                            val extended = features != null && (0 until features.length()).any { features.optString(it) == "laser_range_300" }
                            presentationSession.ready(udpToken, laser, size, length, extended)
                            lastPongAt = System.nanoTime()
                            publishPresentation()
                            ready = true; attempts = 0
                            onState(ConnectionState("connected", "已连接，可以开始操作了。"))
                            if (!laser) onNotice("接收端不支持激光笔，请更新 Windows 接收端。")
                        }
                        "pong" -> if (reply.optLong("id") == pingId && ready) {
                            lastPongAt = System.nanoTime()
                            val rtt = ((System.nanoTime() - pingAt) / 1_000_000).coerceAtLeast(0)
                            onState(ConnectionState("connected", "已连接，可以开始操作了。", rtt))
                        }
                        "text_sent" -> finishText(reply.optLong("id"), null)
                        "presentation_state" -> {
                            val nextToken = WireProtocol.decodeToken(reply.optString("udp_token"))
                            val mode = PresentationMode.entries.firstOrNull { it.code == reply.optString("mode") }
                            val id = if (reply.has("id")) reply.optLong("id") else null
                            val reason = reply.optString("reason").takeIf { it.isNotBlank() }
                            if (ready && nextToken != null && mode != null && presentationSession.acknowledge(id, mode, nextToken, reason != null)) {
                                closeUdp(); openUdp(port = udpPort, token = nextToken)
                                udpSequence.set(0)
                                lastPongAt = System.nanoTime()
                                publishPresentation(reason)
                                if (presentation.laser) setLaserSettings(laserSettings)
                            }
                        }
                        "error" -> {
                            val message = reply.optString("message", "操作未完成。")
                            if (!ready || reply.optString("code") == "protocol") {
                                disconnect()
                                onState(ConnectionState(message = message)); webSocket.close(1000, "Protocol rejected")
                            } else {
                                if (reply.has("id") && presentationSession.reject(reply.optLong("id"))) publishPresentation()
                                if (reply.has("id")) finishText(reply.optLong("id"), message)
                                onNotice(message)
                            }
                        }
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost(token, null) }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) { lost(token, response?.code) }
        })
        socket = current
        worker.schedule({
            if (generation.get() == token && !ready && desired) current.cancel()
        }, 7, TimeUnit.SECONDS)
    }

    private fun lost(token: Long, status: Int?) {
        if (destroyed || !generation.compareAndSet(token, token + 1)) return
        val nextGeneration = token + 1
        ready = false; resetInput(); resetPresentation(); failPending(); closeUdp()
        if (status == 429) {
            desired = false
            onState(ConnectionState(message = "连接尝试过多，请稍后重新发现电脑。"))
            return
        }
        if (!desired || !foreground) return
        attempts++
        val wait = (1L shl attempts.coerceAtMost(3)).coerceAtMost(8)
        onState(ConnectionState("reconnecting", "连接中断，${wait}秒后重试。"))
        worker.schedule({ if (generation.get() == nextGeneration && desired && foreground) open() }, wait, TimeUnit.SECONDS)
    }

    fun disconnect() {
        desired = false; generation.incrementAndGet(); ready = false; resetInput(); resetPresentation(); closeUdp()
        socket?.close(1000, "Disconnected"); socket = null; failPending()
        onState(ConnectionState(message = "已断开连接，可以随时重新连接。"))
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) {
            generation.incrementAndGet(); ready = false; resetInput(); resetPresentation(); failPending(); closeUdp()
            socket?.close(1000, "Background"); socket = null
            onState(ConnectionState(message = if (desired) "返回应用后将恢复连接。" else "已暂停连接。"))
        } else if (desired) open()
    }

    // Queue accumulation alongside button events, rather than adding a drag's
    // first delta on the UI thread before the worker has sent button-down.
    private fun enqueueInput(action: () -> Unit) {
        if (destroyed || !ready || inputBlocked) return
        val token = generation.get()
        val epoch = presentation.inputEpoch
        worker.execute {
            if (generation.get() == token && presentation.inputEpoch == epoch && ready && !destroyed && !inputBlocked) {
                prepareInputGeneration()
                action()
            }
        }
    }

    private fun prepareInputGeneration() {
        val token = generation.get()
        if (inputGeneration != token) {
            motionFlush.reset()
            motionDelivery.reset()
            touchGestureFlush.reset(); touchGestureMotion.clear()
            inputGeneration = token
        }
    }

    private fun resetInput() {
        val token = generation.get()
        worker.execute {
            if (generation.get() == token) {
                motionFlush.reset()
                motionDelivery.reset()
                touchGestureFlush.reset(); touchGestureMotion.clear()
                inputGeneration = token
            }
        }
    }

    fun move(dx: Double, dy: Double) = enqueueInput { motionDelivery.move(dx, dy); motionFlush.request() }
    fun clearPointerMotion() = enqueueInput { motionDelivery.clearPointerMotion() }
    fun scroll(delta: Double) = enqueueInput { motionDelivery.scroll(delta); motionFlush.request() }
    fun touchGesture(horizontal: Double = 0.0, zoom: Double = 0.0) = enqueueInput {
        touchGestureMotion.add(horizontal, zoom); touchGestureFlush.request()
    }
    fun endTouchGesture(cancelled: Boolean) = enqueueInput {
        touchGestureFlush.reset()
        if (!cancelled) flushTouchGestures(all = true)
        touchGestureMotion.clear()
    }
    private fun flushTouchGestures(all: Boolean = false) {
        if (!ready || inputBlocked) return
        while (touchGestureMotion.pending()) {
            val packet = touchGestureMotion.drain()
            send(JSONObject().put("type", "touch_gesture").put("horizontal", packet.horizontal).put("zoom", packet.zoom))
            if (!all) break
        }
    }
    fun click(button: String) = enqueueInput {
        if (!motionDelivery.held(button)) {
            motionDelivery.flush(reliable = true, all = true)
            send(JSONObject().put("type", "click").put("button", button))
        }
    }
    fun buttonDown(button: String, source: String = "touchpad") = enqueueInput { motionDelivery.buttonDown(button, source) }
    fun buttonUp(button: String, source: String = "touchpad") = enqueueInput { motionDelivery.buttonUp(button, source) }
    fun key(key: String, onResult: ((Boolean) -> Unit)? = null) {
        val token = generation.get()
        worker.execute {
            val sent = generation.get() == token && sendControl(JSONObject().put("type", "key").put("key", key))
            onResult?.invoke(sent)
        }
    }

    private fun flushMotion() {
        prepareInputGeneration()
        if (ready && !inputBlocked) motionDelivery.flush()
    }

    private fun sendUdpMotion(packet: MotionPacket) {
        val currentUdp = udp
        if (currentUdp != null) {
            runCatching { currentUdp.send(udpSequence.incrementAndGet(), packet) }
                .onFailure { closeUdp(); sendTcpMotion(packet) }
        } else {
            sendTcpMotion(packet)
        }
    }

    private fun sendTcpMotion(packet: MotionPacket) {
        val current = socket ?: return
        if (current.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
            // Dropping stale relative motion is preferable to replaying it
            // after a congested Wi-Fi interval. Keep the ready socket
            // alive so the next finger movement can recover immediately.
            return
        }
        if (packet.dx != 0 || packet.dy != 0) send(JSONObject().put("type", "move_pixels").put("dx", packet.dx).put("dy", packet.dy))
        if (packet.wheel != 0) send(JSONObject().put("type", "scroll").put("delta", packet.wheel))
    }

    private fun openUdp(port: Int, token: ByteArray) {
        udpPort = port
        runCatching {
            UdpMotionSender(DatagramSocket(), InetAddress.getByName(serverHost), port, token)
        }.onSuccess { sender -> closeUdp(); udp = sender }
    }

    private fun closeUdp() {
        udp?.close()
        udp = null
    }

    private fun send(value: JSONObject): Boolean {
        if (!ready) return false
        val current = socket ?: return false
        if (current.queueSize() > MAX_SOCKET_QUEUE_BYTES) return false
        return current.send(value.toString())
    }

    /** Button release must not be dropped just because stale motion queued up. */
    private fun sendControl(value: JSONObject): Boolean {
        if (!ready) return false
        return socket?.send(value.toString()) == true
    }

    fun text(value: String): Long? {
        if (!ready) return null
        val id = ids.incrementAndGet()
        pending[id] = value
        val token = generation.get()
        worker.execute {
            if (!pending.containsKey(id)) return@execute
            if (generation.get() != token || !send(JSONObject().put("type", "text").put("text", value).put("id", id))) {
                finishText(id, "发送结果未确认，文字已保留；请先检查电脑再重试。")
            }
        }
        worker.schedule({ finishText(id, "发送结果未确认，文字已保留；请先检查电脑再重试。") }, 5, TimeUnit.SECONDS)
        return id
    }

    private fun finishText(id: Long, error: String?) { pending.remove(id)?.let { onTextResult(id, it, error) } }
    private fun failPending() { pending.keys.toList().forEach { finishText(it, "发送结果未确认，文字已保留；请先检查电脑再重试。") } }

    fun destroy() {
        destroyed = true; disconnect(); closeUdp(); worker.shutdownNow()
        http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
}
