package com.dasher.automate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class DoorDashAccessibilityService : AccessibilityService() {
    
    companion object {
        const val TAG = "DoorDashAuto"
        var isActive = false
        var currentRules: Rules? = null
        var webSocketClient: WebSocketManager? = null
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        
        // Initialize WebSocket connection
        connectToWebSocket()
        
        // Send ready status
        sendStatusUpdate("ACTIVE")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isActive || currentRules == null) return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChange(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChange(event)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleNotification(event)
            }
        }
    }
    
    private fun handleContentChange(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        val packageName = event.packageName?.toString()
        
        if (packageName != "com.doordash.driverapp") return
        
        // Check for offer screen
        val offerScreen = rootNode.findAccessibilityNodeInfosByViewId(
            "com.doordash.driverapp:id/offer_container"
        ).firstOrNull()
        
        if (offerScreen != null) {
            processOfferScreen(rootNode)
        }
    }
    
    private fun processOfferScreen(rootNode: AccessibilityNodeInfo) {
        CoroutineScope(Dispatchers.IO).launch {
            // Extract offer details
            val offerDetails = extractOfferDetails(rootNode)
            
            // Send to web app for logging
            sendOfferToWeb(offerDetails)
            
            // Check against rules
            val shouldAccept = shouldAcceptOffer(offerDetails)
            
            // Perform action
            if (shouldAccept) {
                clickAcceptButton(rootNode)
                sendActionToWeb("ACCEPTED", offerDetails)
            } else {
                clickDeclineButton(rootNode)
                sendActionToWeb("DECLINED", offerDetails)
            }
        }
    }
    
    private fun extractOfferDetails(rootNode: AccessibilityNodeInfo): OfferDetails {
        val details = OfferDetails()
        
        // Extract pay amount
        val payNode = rootNode.findAccessibilityNodeInfosByViewId(
            "com.doordash.driverapp:id/pay_amount"
        ).firstOrNull()
        details.pay = payNode?.text?.toString()?.filter { it.isDigit() || it == '.' } ?: "0"
        
        // Extract distance
        val distanceNode = rootNode.findAccessibilityNodeInfosByViewId(
            "com.doordash.driverapp:id/distance_text"
        ).firstOrNull()
        details.distance = distanceNode?.text?.toString() ?: "0 mi"
        
        // Extract time estimate
        val timeNode = rootNode.findAccessibilityNodeInfosByViewId(
            "com.doordash.driverapp:id/time_estimate"
        ).firstOrNull()
        details.timeEstimate = timeNode?.text?.toString() ?: "0 min"
        
        // Extract store name
        val storeNode = rootNode.findAccessibilityNodeInfosByViewId(
            "com.doordash.driverapp:id/store_name"
        ).firstOrNull()
        details.storeName = storeNode?.text?.toString() ?: ""
        
        return details
    }
    
    private fun shouldAcceptOffer(details: OfferDetails): Boolean {
        val rules = currentRules ?: return false
        
        val pay = details.pay.toDoubleOrNull() ?: 0.0
        val distance = extractMiles(details.distance)
        
        // Apply rules
        if (pay < rules.minPay) return false
        if (distance > rules.maxDistance) return false
        if (pay / distance < rules.minPayPerMile) return false
        
        // Check store blacklist
        if (rules.blacklistedStores.any { 
            details.storeName.contains(it, ignoreCase = true) 
        }) return false
        
        return true
    }
    
    private fun extractMiles(distanceText: String): Double {
        val regex = """(\d+\.?\d*)""".toRegex()
        return regex.find(distanceText)?.value?.toDoubleOrNull() ?: 0.0
    }
    
    private fun clickAcceptButton(rootNode: AccessibilityNodeInfo) {
        val acceptButton = rootNode.findAccessibilityNodeInfosByViewId(
            "com.doordash.driverapp:id/accept_button"
        ).firstOrNull() ?: rootNode.findAccessibilityNodeInfosByText("Accept")
            .firstOrNull { it.isClickable }
        
        acceptButton?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(500) // Wait for action
        }
    }
    
    private fun clickDeclineButton(rootNode: AccessibilityNodeInfo) {
        val declineButton = rootNode.findAccessibilityNodeInfosByViewId(
            "com.doordash.driverapp:id/decline_button"
        ).firstOrNull() ?: rootNode.findAccessibilityNodeInfosByText("Decline")
            .firstOrNull { it.isClickable }
        
        declineButton?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(500)
        }
    }
    
    private fun sendOfferToWeb(offer: OfferDetails) {
        try {
            val json = gson.toJson(offer)
            val body = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("${Config.WEB_APP_URL}/api/log-offer")
                .post(body)
                .build()
            
            client.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending offer to web", e)
        }
    }
    
    private fun sendActionToWeb(action: String, offer: OfferDetails) {
        val actionLog = mapOf(
            "action" to action,
            "offer" to offer,
            "timestamp" to System.currentTimeMillis()
        )
        
        webSocketClient?.send(gson.toJson(actionLog))
    }
    
    private fun connectToWebSocket() {
        val deviceId = getDeviceId()
        webSocketClient = WebSocketManager()
        webSocketClient?.connect("wss://dasher-auto.web.app/ws/$deviceId")
    }
    
    private fun getDeviceId(): String {
        // Get unique device ID
        return "DEVICE_${Build.SERIAL.hashCode()}"
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
        sendStatusUpdate("INACTIVE")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webSocketClient?.disconnect()
        sendStatusUpdate("STOPPED")
    }
}

data class Rules(
    val minPay: Double = 6.50,
    val maxDistance: Double = 10.0,
    val minPayPerMile: Double = 1.50,
    val blacklistedStores: List<String> = emptyList(),
    val enabled: Boolean = true
)

data class OfferDetails(
    var pay: String = "",
    var distance: String = "",
    var timeEstimate: String = "",
    var storeName: String = ""
)
