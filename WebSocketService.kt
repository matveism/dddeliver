package com.dasher.automate

import android.app.Service
import android.content.Intent
import android.os.IBinder
import okhttp3.*
import okio.ByteString

class WebSocketService : Service() {
    
    private lateinit var webSocket: WebSocket
    private val client = OkHttpClient()
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectWebSocket()
        return START_STICKY
    }
    
    private fun connectWebSocket() {
        val deviceId = getDeviceId()
        val request = Request.Builder()
            .url("wss://dasher-auto.web.app/ws/$deviceId")
            .build()
        
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                this@WebSocketService.webSocket = webSocket
                sendMessage("""{"type":"device_connected","status":"online"}""")
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Attempt reconnect
                Thread.sleep(5000)
                connectWebSocket()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Reconnect on failure
                Thread.sleep(5000)
                connectWebSocket()
            }
        }
        
        client.newWebSocket(request, listener)
    }
    
    private fun handleMessage(message: String) {
        when {
            message.contains("\"type\":\"update_rules\"") -> {
                // Update rules in AccessibilityService
                DoorDashAccessibilityService.currentRules = parseRules(message)
            }
            message.contains("\"type\":\"toggle_service\"") -> {
                val enabled = message.contains("\"enabled\":true")
                DoorDashAccessibilityService.isActive = enabled
            }
            message.contains("\"type\":\"ping\"") -> {
                sendMessage("""{"type":"pong","timestamp":${System.currentTimeMillis()}}""")
            }
        }
    }
    
    private fun sendMessage(message: String) {
        if (::webSocket.isInitialized) {
            webSocket.send(message)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
