package com.example.solusfrontend.services

import android.content.Context
import android.widget.Toast
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import ai.picovoice.porcupine.PorcupineManagerErrorCallback
import com.example.solusfrontend.BuildConfig
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.e
import com.github.ajalt.timberkt.w
import timber.log.Timber

// This class manages wake word detection using the Picovoice Porcupine engine.
// Keyword: Solus
class WakeWordDetector(
    private val context: Context,
    private val callback: WakeWordCallback  // What to do when the wake word is detected
) {
    companion object {
        private const val TAG = "WakeWordDetector"  // Tag for logging
    }

    private val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY    // Picovoice access key
    private val keywordFilePath = "SolusWakeWord.ppn"           // Path to the keyword file assets/SolusWakeWord.ppn
    private var porcupineManager: PorcupineManager? = null      // This is the actual wake word engine
    private var isListening = false                             // To track if we are currently listening

    // Interface that other classes will implement to handle the wake word event
    interface WakeWordCallback {
        fun onWakeWordDetected()
    }

    // This function sets everything up — like plugging in the microphone and loading the keyword
    fun initialize() {
        try {
            // Callback that runs when the wake word is successfully detected
            val wakeWordCallback = PorcupineManagerCallback { _: Int ->
                Timber.tag(TAG).d { "Wake word detected!" }
                callback.onWakeWordDetected() // Trigger the action passed by whoever is using this class
            }

            // Callback to handle any errors that happen inside the wake word engine
            val errorCallback = PorcupineManagerErrorCallback { error: Exception ->
                Timber.tag(TAG).e(error) { "Porcupine error: ${error.message}" }
                Toast.makeText(
                    context,
                    "Wake word detection error: ${error.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // Build and initialize the PorcupineManager
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)             // Required API key
                .setKeywordPath(keywordFilePath)     // Your keyword file
                .setErrorCallback(errorCallback)     // Handle internal errors
                .build(context, wakeWordCallback)    // Finally build it with context and success callback

            Timber.tag(TAG).d { "PorcupineManager initialized successfully." }

        } catch (e: PorcupineException) {
            // If fail to initialize PorcupineManager
            Timber.tag(TAG).e(e) { "Failed to initialize PorcupineManager: ${e.message}" }
            Toast.makeText(
                context,
                "Error initializing wake word engine: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Start listening for the wake word
    fun startListening() {
        // Ensure manager is initialized & is not listening
        if (porcupineManager != null && !isListening) {
            try {
                porcupineManager?.start()      // Begin listening through the mic
                isListening = true             // Update status
                Timber.tag(TAG).d { "Started listening for wake word." }
                Toast.makeText(context, "Listening for wake word...", Toast.LENGTH_SHORT).show()
            } catch (e: PorcupineException) {
                Timber.tag(TAG).e(e) { "Failed to start PorcupineManager: ${e.message}" }
                Toast.makeText(
                    context,
                    "Error starting wake word listener: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (porcupineManager == null) {
            // If manager is not initialized
            Timber.tag(TAG).w { "PorcupineManager not initialized. Cannot start listening." }
            Toast.makeText(context, "Wake word engine not ready.", Toast.LENGTH_SHORT).show()
        } else {
            // Already listening, no need to restart
            Timber.tag(TAG).d { "Already listening." }
        }
    }

    // Stop listening for wake word
    fun stopListening() {
        if (porcupineManager != null && isListening) {
            try {
                porcupineManager?.stop()       // Stop mic input
                isListening = false            // Update status
                Timber.tag(TAG).d { "Stopped listening for wake word." }
                Toast.makeText(context, "Stopped listening.", Toast.LENGTH_SHORT).show()
            } catch (e: PorcupineException) {
                Timber.tag(TAG).e(e) { "Failed to stop PorcupineManager: ${e.message}" }
                Toast.makeText(
                    context,
                    "Error stopping wake word listener: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            // Manager = null
            // Is not listening
            Timber.tag(TAG).d { "Not currently listening or manager not initialized." }
        }
    }

    // Call after using the wake word detector (e.g., on app shutdown or cleanup)
    fun release() {
        if (porcupineManager != null) {
            Timber.tag(TAG).d { "Deleting PorcupineManager." }
            porcupineManager?.delete() // Frees up native resources
            porcupineManager = null
        }
    }
}
