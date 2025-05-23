package com.example.voiceassistant.ActivationInput;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import ai.picovoice.porcupine.*;

public class WakeWordDetector {
    private static final String TAG = "WakeWordDetector";
    private final String accessKey;
    private final String keywordFilePath;
    private PorcupineManager porcupineManager;
    private boolean isListening = false;
    private final Context context;
    private final WakeWordCallback callback;

    public interface WakeWordCallback {
        void onWakeWordDetected();
    }

    public WakeWordDetector(Context context, String accessKey, String keywordFilePath, WakeWordCallback callback) {
        this.context = context;
        this.accessKey = accessKey;
        this.keywordFilePath = keywordFilePath;
        this.callback = callback;
    }

    public void initialize() {
        try {
            PorcupineManagerCallback wakeWordCallback = keywordIndex -> {
                Log.d(TAG, "Wake word detected!");
                callback.onWakeWordDetected();
            };

            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(keywordFilePath)
                    .build(context, wakeWordCallback);

            Log.d(TAG, "PorcupineManager initialized successfully.");

        } catch (PorcupineException e) {
            Log.e(TAG, "Failed to initialize PorcupineManager: " + e.getMessage());
            e.printStackTrace();
            Toast.makeText(context, "Error initializing wake word engine: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void startListening() {
        if (porcupineManager != null && !isListening) {
            try {
                porcupineManager.start();
                isListening = true;
                Log.d(TAG, "Started listening for wake word.");
                Toast.makeText(context, "Listening for wake word...", Toast.LENGTH_SHORT).show();
            } catch (PorcupineException e) {
                Log.e(TAG, "Failed to start PorcupineManager: " + e.getMessage());
                Toast.makeText(context, "Error starting wake word listener: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (porcupineManager == null) {
            Log.w(TAG, "PorcupineManager not initialized. Cannot start listening.");
            Toast.makeText(context, "Wake word engine not ready.", Toast.LENGTH_SHORT).show();
        } else {
            Log.d(TAG, "Already listening.");
        }
    }

    public void stopListening() {
        if (porcupineManager != null && isListening) {
            try {
                porcupineManager.stop();
                isListening = false;
                Log.d(TAG, "Stopped listening for wake word.");
                Toast.makeText(context, "Stopped listening.", Toast.LENGTH_SHORT).show();
            } catch (PorcupineException e) {
                Log.e(TAG, "Failed to stop PorcupineManager: " + e.getMessage());
                Toast.makeText(context, "Error stopping wake word listener: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            Log.d(TAG, "Not currently listening or manager not initialized.");
        }
    }

    public void release() {
        if (porcupineManager != null) {
            Log.d(TAG, "Deleting PorcupineManager.");
            porcupineManager.delete();
            porcupineManager = null;
        }
    }
}
