package com.example.voiceassistant;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.example.voiceassistant.ActivationInput.GoogleSTT;
import com.example.voiceassistant.ActivationInput.QueryRecord;
import com.example.voiceassistant.ActivationInput.WakeWordDetector;
import com.example.voiceassistant.overlay.VisualOverlays;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ACCESS_KEY = "PQ/EFPj9vA2SNHZriQkGf6A8H1FZFYEVGTjngGbTRCk7iYK2W0aTZg==";
    private static final String KEYWORD_FILE_PATH = "SolusWakeWord.ppn";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    
    // Delay constants
    private static final int WAKE_WORD_RESTART_DELAY = 1000; // 1 second
    private static final int RECORDING_TIMEOUT = 10000; // 10 seconds max for recording

    private WakeWordDetector wakeWordDetector;
    private QueryRecord queryRecord;
    private GoogleSTT googleSTT;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isProcessingInput = false;
    private Runnable recordingTimeoutRunnable = null;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Initialize components only after permission is granted
                Toast.makeText(this, "Microphone permission granted. Initializing...", Toast.LENGTH_LONG).show();
                initWakeWordDetector();
                initQueryRecordAndSTT();
                startWakeWordDetection();
            } else {
                Toast.makeText(this, "Microphone permission is required for voice assistant.", Toast.LENGTH_LONG).show();
                // Handle permission denial
                Toast.makeText(this, "Microphone permission denied. Voice features disabled.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Please enable Voice Assistant accessibility service", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } else {
            initializeComponents();
        }
    }

    private void initializeComponents() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initWakeWordDetector();
            initQueryRecordAndSTT();
            startWakeWordDetection();
        } else {
            requestMicrophonePermission();
        }
    }

    private void requestMicrophonePermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_RECORD_AUDIO_PERMISSION);
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Error finding setting: " + e.getMessage());
        }

        if (accessibilityEnabled == 1) {
            String service = getPackageName() + "/" + VisualOverlays.class.getCanonicalName();
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabledServices != null && enabledServices.contains(service);
        }
        return false;
    }

    private void initWakeWordDetector() {
        if (wakeWordDetector == null) {
            Log.d(TAG, "Initializing WakeWordDetector...");
            wakeWordDetector = new WakeWordDetector(
                    this,
                    ACCESS_KEY,
                    KEYWORD_FILE_PATH,
                    this::onWakeWordDetected
            );
            wakeWordDetector.initialize();
        }
    }

    private void initQueryRecordAndSTT() {
        // Initialize QueryRecord
        if (queryRecord == null) {
            Log.d(TAG, "Initializing QueryRecord...");
            queryRecord = new QueryRecord(
                    this,
                    new QueryRecord.Callback() {
                        @Override
                        public void onRecordingStart() {
                            Log.d(TAG, "Recording started");
                            updateOverlayText("Listening...");
                            setRecordingTimeout();
                        }

                        @Override
                        public void onRecordingEnd(byte[] audioData) {
                            Log.d(TAG, "Recording ended, got " + audioData.length + " bytes");
                            cancelRecordingTimeout();
                            
                            if (audioData.length > 0) {
                                updateOverlayText("Processing...");
                                processAudioWithSTT(audioData, queryRecord.getSampleRate());
                            } else {
                                updateOverlayText("No speech detected");
                                restartWakeWordDetectionAfterDelay(WAKE_WORD_RESTART_DELAY);
                            }
                        }
                    }
            );
        }

        // Initialize GoogleSTT
        if (googleSTT == null) {
            Log.d(TAG, "Initializing GoogleSTT...");
            googleSTT = new GoogleSTT(
                    this,
                    new GoogleSTT.GoogleSTTCallback() {
                        @Override
                        public void onTranscriptionComplete(String text, float confidence) {
                            Log.d(TAG, "Transcription complete: " + text + " (confidence: " + confidence + ")");
                            updateOverlayText("Query: " + text);
                            restartWakeWordDetectionAfterDelay(WAKE_WORD_RESTART_DELAY);
                        }

                        @Override
                        public void onTranscriptionError(String errorMessage) {
                            Log.e(TAG, "Transcription error: " + errorMessage);
                            updateOverlayText("Sorry, couldn't understand that");
                            restartWakeWordDetectionAfterDelay(WAKE_WORD_RESTART_DELAY);
                        }
                    }
            );
        }

        // Set up overlay dismiss listener
        if (VisualOverlays.getInstance() != null) {
            VisualOverlays.getInstance().setOnDialogDismissListener(() -> {
                isProcessingInput = false;
                cancelRecordingTimeout();
                if (queryRecord != null) {
                    queryRecord.stop();
                }
                startWakeWordDetection();
            });
        }
    }

    private void updateOverlayText(String text) {
        if (VisualOverlays.getInstance() != null) {
            VisualOverlays.getInstance().displayText(text);
        }
    }

    private void setRecordingTimeout() {
        // First, ensure any existing timeout is canceled
        cancelRecordingTimeout();
        
        // Create a new timeout
        recordingTimeoutRunnable = () -> {
            Log.w(TAG, "Recording timeout reached, forcing stop");
            if (queryRecord != null) {
                queryRecord.stop(); // Force stop the recording
            }
        };
        
        // Set the timeout
        mainHandler.postDelayed(recordingTimeoutRunnable, RECORDING_TIMEOUT);
    }
    
    private void cancelRecordingTimeout() {
        if (recordingTimeoutRunnable != null) {
            mainHandler.removeCallbacks(recordingTimeoutRunnable);
            recordingTimeoutRunnable = null;
        }
    }

    private void processAudioWithSTT(byte[] audioData, int sampleRate) {
        if (googleSTT != null && audioData.length > 0) {
            googleSTT.transcribe(audioData, sampleRate);
        } else {
            Log.e(TAG, "Cannot process audio - STT is null or audio data is empty");
            updateOverlayText("Error processing audio. Listening for wake word...");
            restartWakeWordDetectionAfterDelay(WAKE_WORD_RESTART_DELAY);
        }
    }

    private void onWakeWordDetected() {
        if (isProcessingInput) {
            Log.d(TAG, "Already processing input, ignoring this wake word");
            return;
        }

        isProcessingInput = true;
        Log.d(TAG, "Wake word detected via callback");
        updateOverlayText("Activating...");

        if (wakeWordDetector != null) {
            wakeWordDetector.stopListening();
            Log.d(TAG, "Wake word detection stopped.");

            if (queryRecord != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                mainHandler.postDelayed(() -> {
                    try {
                        if (!isProcessingInput) {
                            Log.d(TAG, "Processing was cancelled, not starting recording");
                            return;
                        }
                        queryRecord.start();
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting recording: " + e.getMessage());
                        isProcessingInput = false;
                        restartWakeWordDetectionAfterDelay(WAKE_WORD_RESTART_DELAY);
                    }
                }, 1000);
            } else {
                Log.e(TAG, "Cannot start recording - recorder null or no permission");
                updateOverlayText("Error: Cannot start recording");
                isProcessingInput = false;
                restartWakeWordDetectionAfterDelay(WAKE_WORD_RESTART_DELAY);
            }
        }
    }

    private void startWakeWordDetection() {
        if (wakeWordDetector != null &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {

            Log.d(TAG, "Starting wake word detection...");
            
            // Make sure we're not in the middle of processing and cancel any timeouts
            isProcessingInput = false;
            cancelRecordingTimeout();
            
            wakeWordDetector.startListening();
            Log.d(TAG, "Wake word detection started/resumed.");
        } else {
            Log.w(TAG, "Cannot start wake word detection - detector null or no permission.");
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
                updateOverlayText("Waiting for microphone permission...");
            } else {
                updateOverlayText("Wake word engine not ready.");
            }
        }
    }
    
    private void restartWakeWordDetectionAfterDelay(long delayMs) {
        isProcessingInput = false; // Allow next wake word detection
        cancelRecordingTimeout(); // Ensure any recording timeout is canceled
        
        Log.d(TAG, "Scheduling wake word restart with delay: " + delayMs + "ms");
        mainHandler.postDelayed(this::startWakeWordDetection, delayMs);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called.");
        
        // Check if accessibility service is enabled
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Voice Assistant requires accessibility service", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }

        // Initialize components if needed
        if (wakeWordDetector == null || queryRecord == null || googleSTT == null) {
            initializeComponents();
        } else if (!isProcessingInput) {
            startWakeWordDetection();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause called.");
        
        // Don't stop services when going to background
        // Only cleanup if the activity is finishing
        if (isFinishing()) {
            cleanup();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called.");
        cleanup();
    }

    private void cleanup() {
        // Stop wake word detection only if accessibility service is not running
        if (!isAccessibilityServiceEnabled()) {
            if (wakeWordDetector != null) {
                wakeWordDetector.stopListening();
                wakeWordDetector.release();
                wakeWordDetector = null;
            }
        }
        
        // Stop recording if in progress
        if (queryRecord != null) {
            queryRecord.stop();
            queryRecord = null;
        }
        
        if (googleSTT != null) {
            googleSTT.destroy();
            googleSTT = null;
        }

        // Clean up handler
        cancelRecordingTimeout();
        mainHandler.removeCallbacksAndMessages(null);
        isProcessingInput = false;
    }
}