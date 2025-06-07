package com.example.solusfrontend.services.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.example.solusfrontend.services.overlay.VoiceAssistantOverlayService
import com.github.ajalt.timberkt.Timber

/**
 * Activity for requesting screen capture permission
 * 
 * This is a transparent activity that immediately requests screen capture permission
 * and forwards the result back to the VoiceAssistantOverlayService.
 * 
 * It's designed to be lightweight and finish itself immediately after getting the result.
 */
class ScreenCaptureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make activity transparent
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        
        // Request screen capture permission
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(
            mediaProjectionManager.createScreenCaptureIntent(),
            REQUEST_SCREEN_CAPTURE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            // Forward result back to service
            val serviceIntent = Intent(this, VoiceAssistantOverlayService::class.java).apply {
                action = VoiceAssistantOverlayService.ACTION_SCREEN_CAPTURE_RESULT
                putExtra(VoiceAssistantOverlayService.EXTRA_RESULT_CODE, resultCode)
                putExtra(VoiceAssistantOverlayService.EXTRA_DATA, data)
            }
            startService(serviceIntent)
            
            Timber.d { "Screen capture permission result: ${if (resultCode == RESULT_OK) "granted" else "denied"}" }
            
            // Close this activity
            finish()
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 101
    }
} 