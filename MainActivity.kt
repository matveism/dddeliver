package com.dasher.automate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Check and request accessibility permission
        checkAccessibilityPermission()
        
        // Start foreground service
        val serviceIntent = Intent(this, DasherForegroundService::class.java)
        startForegroundService(serviceIntent)
        
        // Generate unique device ID
        val deviceId = generateDeviceId()
        
        // Show QR code to connect to web app
        showConnectionQR(deviceId)
    }
    
    private fun generateDeviceId(): String {
        val androidId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )
        return "DASHER-${androidId?.hashCode()?.toString(16)}"
    }
    
    private fun showConnectionQR(deviceId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            // Create QR with connection URL
            val qrUrl = "https://dasher-auto.web.app/connect?device=$deviceId"
            // Display QR code image
            // Implementation depends on your QR library
        }
    }
    
    private fun checkAccessibilityPermission() {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        
        if (!enabledServices.contains(packageName)) {
            // Open accessibility settings
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }
}
