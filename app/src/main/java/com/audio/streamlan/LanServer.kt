package com.audio.streamlan

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArraySet

/** Local-only HTTP + WebSocket server. Audio is never sent to the internet. */
class LanServer(private val context: Context, private val port: Int = 8080) : NanoWSD(port) {
    companion object { private const val TAG = "AudioStreamLAN" }
    private val clients = CopyOnWriteArraySet<StreamSocket>()
    @Volatile private var running = false

    fun startServer() {
        if (running) return
        start()
        running = true
        Log.i(TAG, "LAN server started on $url")
    }

    fun stopServer() {
        if (!running) return
        clients.forEach { runCatching { it.close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "", false) } }
        clients.clear()
        stop()
        running = false
    }

    val clientCount: Int get() = clients.size
    val url: String get() = "http://${localIpAddress()}:$port/"

    fun broadcastPcm(bytes: ByteArray, length: Int) {
        if (!running || clients.isEmpty() || length <= 0) return
        val packet = if (length == bytes.size) bytes else bytes.copyOf(length)
        clients.forEach { socket ->
            try {
                if (socket.isOpen) socket.send(packet)
            } catch (e: IOException) {
                Log.w(TAG, "Removing failed WebSocket client", e)
                clients.remove(socket)
                runCatching { socket.close(NanoWSD.WebSocketFrame.CloseCode.NormalClosure, "", false) }
            }
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = StreamSocket(handshake)

    override fun serveHttp(session: IHTTPSession): Response = when (session.uri) {
        "/", "/index.html" -> assetResponse("index.html", "text/html; charset=utf-8")
        "/health" -> NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", "{\"ok\":true,\"clients\":$clientCount}")
        else -> NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "Not found")
    }

    private fun assetResponse(name: String, mime: String): Response = try {
        NanoHTTPD.newChunkedResponse(Response.Status.OK, mime, context.assets.open(name))
    } catch (e: IOException) {
        Log.e(TAG, "Missing asset $name", e)
        NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Asset unavailable")
    }

    private inner class StreamSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            clients.add(this)
            Log.i(TAG, "Browser connected; clients=${clients.size}")
            runCatching { send("{\"type\":\"hello\",\"sampleRate\":48000,\"channels\":2}") }
        }
        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
            clients.remove(this)
            Log.i(TAG, "Browser disconnected; clients=${clients.size}")
        }
        override fun onMessage(message: WebSocketFrame) = Unit
        override fun onPong(pong: WebSocketFrame) = Unit
        override fun onException(exception: IOException) {
            Log.w(TAG, "WebSocket exception", exception)
            clients.remove(this)
        }
    }

    private fun localIpAddress(): String = try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        val addresses = interfaces.flatMap { Collections.list(it.inetAddresses) }
            .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            .mapNotNull { it.hostAddress }
        addresses.firstOrNull { ip ->
            val parts = ip.split('.')
            ip.startsWith("192.168.") || ip.startsWith("10.") || (parts.size == 4 && parts[0] == "172" && parts[1].toIntOrNull() in 16..31)
        } ?: addresses.firstOrNull() ?: "127.0.0.1"
    } catch (e: Exception) {
        Log.w(TAG, "Could not determine local IP", e)
        "127.0.0.1"
    }
}
