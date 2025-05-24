@file:OptIn(Beta::class)

package com.example.solusfrontend

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.github.ajalt.timberkt.Timber
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.local.RoomScope
import io.livekit.android.compose.state.rememberVoiceAssistant
import io.livekit.android.compose.state.transcriptions.rememberParticipantTranscriptions
import io.livekit.android.compose.state.transcriptions.rememberTranscriptions
import io.livekit.android.compose.ui.audio.VoiceAssistantBarVisualizer
import kotlinx.coroutines.delay

/**
 * Background Voice Assistant Service
 * This service runs in the background and shows an overlay UI when the voice assistant is activated
 * Think of it as a floating window that appears on top of whatever app you're using
 */
class VoiceAssistantOverlayService : Service(),
    WakeWordDetector.WakeWordCallback,
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // System overlay window manager - this lets us draw on top of other apps
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    // Wake word detection - always listening in the background
    private lateinit var wakeWordDetector: WakeWordDetector

    // Voice assistant state
    private var isVoiceAssistantActive by mutableStateOf(false)
    private var isMicOn by mutableStateOf(true) // Mic starts ON when activated

    // Connection details for LiveKit
    private var connectionUrl: String? = null
    private var connectionToken: String? = null

    // Lifecycle management for Compose in Service
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "voice_assistant_channel"

        // Actions for controlling the service
        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_SET_CONNECTION = "SET_CONNECTION"

        // Intent extras
        const val EXTRA_URL = "url"
        const val EXTRA_TOKEN = "token"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize lifecycle
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Get window manager for overlay
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Initialize wake word detector
        wakeWordDetector = WakeWordDetector(this, this)
        wakeWordDetector.initialize()

        // Create notification channel for foreground service
        createNotificationChannel()

        Timber.d { "VoiceAssistantOverlayService created" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                startForegroundService()
                startWakeWordDetection()
            }
            ACTION_STOP_SERVICE -> {
                stopSelf()
            }
            ACTION_SET_CONNECTION -> {
                connectionUrl = intent.getStringExtra(EXTRA_URL)
                connectionToken = intent.getStringExtra(EXTRA_TOKEN)
                Timber.d { "Connection details updated: url=$connectionUrl, token=$connectionToken" }
            }
        }

        // Return START_STICKY so service restarts if killed by system
        return START_STICKY
    }

    /**
     * Start the service as foreground service with persistent notification
     * This keeps the service running and shows user that voice assistant is listening
     */
    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Timber.i { "Voice Assistant is now listening in background" }
    }

    /**
     * Create notification channel for Android 8.0+
     * This is required for foreground services
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Assistant",
                NotificationManager.IMPORTANCE_LOW // Low importance = no sound/vibration
            ).apply {
                description = "Voice assistant background service"
                setShowBadge(false)
            }

            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Create the persistent notification shown while service is running
     * Users can tap this to open settings or stop the service
     */
    private fun createNotification(): Notification {
        // Intent to stop the service when user taps "Stop"
        val stopIntent = Intent(this, VoiceAssistantOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText("Say 'Solus' to activate • Tap to stop")
            .setSmallIcon(R.drawable.ic_mic_gradiant) // Use your mic icon
            .setOngoing(true) // Can't be swiped away
            .addAction(
                R.drawable.ic_close, // Use close icon
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    /**
     * Start listening for wake word in background
     * This runs continuously until service is stopped
     */
    private fun startWakeWordDetection() {
        if (connectionUrl != null && connectionToken != null) {
            wakeWordDetector.startListening()
            Timber.i { "Wake word detection started in background" }
        } else {
            Timber.w { "Cannot start wake word detection - missing connection details" }
        }
    }

    /**
     * Called when wake word "Solus" is detected
     * This shows the overlay UI on top of current app
     */
    override fun onWakeWordDetected() {
        Timber.i { "Wake word detected! Showing overlay..." }

        // Check if we have overlay permission
        if (!canDrawOverlays()) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
            return
        }

        // Stop wake word detection temporarily
        wakeWordDetector.stopListening()

        // Show the overlay UI
        showVoiceAssistantOverlay()

        // Update state
        isVoiceAssistantActive = true
        isMicOn = true
    }

    /**
     * Check if app has permission to draw overlays
     * Required for showing UI on top of other apps
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true // No permission needed on older versions
        }
    }

    /**
     * Show the floating voice assistant UI overlay
     * This appears at the bottom of screen over other apps
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun showVoiceAssistantOverlay() {
        if (overlayView != null) {
            // Already showing, don't create duplicate
            return
        }

        // Create the overlay view with Compose content
        overlayView = ComposeView(this).apply {
            // Set up lifecycle owners for Compose
            setViewTreeLifecycleOwner(this@VoiceAssistantOverlayService)
            setViewTreeViewModelStoreOwner(this@VoiceAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VoiceAssistantOverlayService)

            setContent {
                VoiceAssistantOverlayContent(
                    connectionUrl = connectionUrl!!,
                    connectionToken = connectionToken!!,
                    isMicOn = isMicOn,
                    onMicToggle = {
                        isMicOn = !isMicOn
                        Timber.i { "Mic toggled: isMicOn=$isMicOn" }
                    },
                    onClose = { closeVoiceAssistant() }
                )
            }
        }

        // Set up window parameters for overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, // Full width
            WindowManager.LayoutParams.WRAP_CONTENT,  // Height adjusts to content
            // Use different overlay types based on Android version
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or  // Don't steal focus from current app
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, // Can extend to screen edges
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM // Position at bottom of screen
        }

        // Add the overlay to window manager
        try {
            windowManager.addView(overlayView, params)
            Timber.i { "Voice assistant overlay shown" }
        } catch (e: Exception) {
            Timber.e { "Failed to show overlay" }
            Toast.makeText(this, "Failed to show voice assistant: $e", Toast.LENGTH_SHORT).show()
            closeVoiceAssistant()
        }
    }

    /**
     * Close the voice assistant and return to wake word detection
     * Removes overlay and starts listening for "Solus" again
     */
    private fun closeVoiceAssistant() {
        Timber.i { "Closing voice assistant overlay" }

        // Remove overlay from screen
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Timber.w { "Error removing overlay view: $e" }
            }
        }
        overlayView = null

        // Reset state
        isVoiceAssistantActive = false
        isMicOn = true

        // Resume wake word detection
        wakeWordDetector.startListening()

        Toast.makeText(this, "Say 'Solus' to activate", Toast.LENGTH_SHORT).show()
    }

    /**
     * The actual UI content for the overlay
     * This shows the conversation and controls at bottom of screen
     */
    @Composable
    private fun VoiceAssistantOverlayContent(
        connectionUrl: String,
        connectionToken: String,
        isMicOn: Boolean,
        onMicToggle: () -> Unit,
        onClose: () -> Unit
    ) {
        // Track conversation activity for auto-close
        var lastActivityTime by remember { mutableStateOf(System.currentTimeMillis()) }

        // Get screen height to limit overlay height to 40%
        val configuration = LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.4f).dp

        // Main overlay container
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = maxHeight) // Dynamic height with limits
                .padding(8.dp),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Header with status and close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color.Green, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Voice Assistant Active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Close button
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close voice assistant",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Voice assistant content with LiveKit
                RoomScope(
                    url = connectionUrl,
                    token = connectionToken,
                    audio = true,
                    video = false,
                    connect = true,
                    connectOptions = ConnectOptions(autoSubscribe = true),
                    roomOptions = RoomOptions(adaptiveStream = true, dynacast = true)
                ) { room ->

                    val voiceAssistant = rememberVoiceAssistant()
                    val agentState = voiceAssistant.state

                    // Update activity time when agent state changes
                    LaunchedEffect(agentState) {
                        lastActivityTime = System.currentTimeMillis()
                    }

                    // Control recording based on mic state
                    LaunchedEffect(isMicOn) {
                        if (isMicOn) {
                            // Start/resume recording
                            try {
                                room.localParticipant.setMicrophoneEnabled(true)
                                Timber.i { "Microphone enabled - recording started" }
                            } catch (e: Exception) {
                                Timber.e { "Failed to enable microphone: $e" }
                            }
                        } else {
                            // Stop recording
                            try {
                                room.localParticipant.setMicrophoneEnabled(false)
                                Timber.i { "Microphone disabled - recording stopped" }
                            } catch (e: Exception) {
                                Timber.e { "Failed to disable microphone: $e" }
                            }
                        }
                    }

                    // Auto-close after inactivity
                    LaunchedEffect(lastActivityTime) {
                        delay(30000) // 30 seconds
                        val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                        if (timeSinceLastActivity >= 30000) {
                            Timber.i { "Auto-closing due to inactivity" }
                            onClose()
                        }
                    }

                    // Audio visualizer
                    VoiceAssistantBarVisualizer(
                        voiceAssistant = voiceAssistant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Conversation history
                    val segments = rememberTranscriptions()
                    val localSegments = rememberParticipantTranscriptions(room.localParticipant)
                    val lazyListState = rememberLazyListState()

                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f) // Takes remaining space
                            .heightIn(max = 200.dp), // Max height for conversation
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(
                            items = segments,
                            key = { segment -> segment.id }
                        ) { segment ->
                            if (localSegments.contains(segment)) {
                                // User message (right aligned)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Card(
                                        modifier = Modifier.widthIn(max = 250.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = segment.text,
                                            modifier = Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            } else {
                                // Assistant message (left aligned)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Card(
                                        modifier = Modifier.widthIn(max = 250.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = segment.text,
                                            modifier = Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Auto-scroll to latest message
                    LaunchedEffect(segments) {
                        if (segments.isNotEmpty()) {
                            lazyListState.scrollToItem((segments.size - 1).coerceAtLeast(0))
                            lastActivityTime = System.currentTimeMillis()
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mic toggle button
                        IconButton(
                            onClick = onMicToggle,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isMicOn) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mic_gradiant),
                                contentDescription = if (isMicOn) "Mute microphone" else "Unmute microphone",
                                tint = if (isMicOn) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Status text
                        Text(
                            text = if (isMicOn) "Listening..." else "Muted",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isMicOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )

                        // End conversation button
                        TextButton(onClick = onClose) {
                            Text(
                                text = "End",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // Clean up overlay
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Timber.w { "Error removing overlay on destroy: $e" }
            }
        }

        // Clean up wake word detector
        wakeWordDetector.stopListening()
        wakeWordDetector.release()

        // Clean up lifecycle
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        Timber.d { "VoiceAssistantOverlayService destroyed" }
    }
}