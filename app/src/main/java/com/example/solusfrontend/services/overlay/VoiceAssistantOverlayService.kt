@file:OptIn(Beta::class)

package com.example.solusfrontend.services.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
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
import com.example.solusfrontend.R
import com.example.solusfrontend.requireToken
import com.example.solusfrontend.services.WakeWordDetector
import com.github.ajalt.timberkt.Timber
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.annotations.Beta
import io.livekit.android.audio.ScreenAudioCapturer
import io.livekit.android.compose.local.RoomScope
import io.livekit.android.compose.state.rememberVoiceAssistant
import io.livekit.android.compose.state.transcriptions.rememberParticipantTranscriptions
import io.livekit.android.compose.state.transcriptions.rememberTranscriptions
import io.livekit.android.compose.ui.audio.VoiceAssistantBarVisualizer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import io.livekit.android.rpc.RpcError
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/**
 * Background Voice Assistant Service with Screen Sharing and Navigation Guidance
 *
 * This service runs in the background and shows an overlay UI when the voice assistant is activated.
 * It provides voice conversation capabilities with an AI agent and now includes screen sharing
 * to allow the agent to see what's on your screen for better assistance, plus navigation guidance
 * functionality for displaying turn-by-turn directions.
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

    // Navigation guidance handler
    private lateinit var navigationGuidanceHandler: NavigationGuidanceHandler

    // Voice assistant state
    private var isVoiceAssistantActive by mutableStateOf(false)
    private var isMicOn by mutableStateOf(true) // Mic starts ON when activated
    private var isScreenShareOn by mutableStateOf(false) // Screen share starts OFF
    private var textInput by mutableStateOf("") // Text input for chat

    // Connection details for LiveKit - now obtained when needed, not stored
    private var connectionUrl: String? = null
    private var connectionToken: String? = null

    // Screen capture for screen sharing
    private var screenCaptureIntent: Intent? = null
    private var audioCapturer: ScreenAudioCapturer? = null

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
        const val ACTION_SCREEN_CAPTURE_RESULT = "SCREEN_CAPTURE_RESULT"

        // Intent extras
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize lifecycle
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Get window manager for overlay
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Initialize wake word detector
        wakeWordDetector = WakeWordDetector(this, this)
        wakeWordDetector.initialize()

        // Initialize navigation guidance handler
        initializeNavigationGuidance()

        // Create notification channel for foreground service
        createNotificationChannel()

        Timber.d { "VoiceAssistantOverlayService created" }
    }

    /**
     * Initialize the navigation guidance handler
     */
    private fun initializeNavigationGuidance() {
        navigationGuidanceHandler = NavigationGuidanceHandler(this)
        Timber.i { "Navigation guidance handler initialized" }
    }

    /**
     * Register RPC methods when connected to the room
     */
    private suspend fun registerRpcMethods(room: Room) {
        try {
            // Register navigation guidance RPC method
            room.localParticipant.registerRpcMethod("display-navigation-guidance") { data ->
                try {
                    Timber.i { "Received navigation guidance RPC call from ${data.callerIdentity}" }
                    Timber.d { "RPC payload: ${data.payload}" }

                    // Handle the navigation guidance display
                    val response = navigationGuidanceHandler.handleNavigationGuidance(data.payload)

                    Timber.i { "Navigation guidance RPC handled successfully" }
                    response

                } catch (e: Exception) {
                    Timber.e(e) { "Error handling navigation guidance RPC: ${e.message}" }
                    throw RpcError(
                        code = 1500,
                        message = "Failed to display navigation guidance: ${e.message}"
                    )
                }
            }

            // Register additional RPC methods for future expansion
            room.localParticipant.registerRpcMethod("clear-navigation-guidance") { data ->
                try {
                    Timber.i { "Received clear navigation guidance RPC call from ${data.callerIdentity}" }

                    navigationGuidanceHandler.clearCurrentGuidance()

                    Timber.i { "Navigation guidance cleared successfully" }
                    "Navigation guidance cleared successfully"

                } catch (e: Exception) {
                    Timber.e(e) { "Error clearing navigation guidance: ${e.message}" }
                    throw RpcError(
                        code = 1501,
                        message = "Failed to clear navigation guidance: ${e.message}"
                    )
                }
            }

            // Register method to check if guidance is active
            room.localParticipant.registerRpcMethod("is-guidance-active") { data ->
                try {
                    Timber.d { "Received guidance status check RPC call from ${data.callerIdentity}" }

                    val isActive = navigationGuidanceHandler.isGuidanceActive()

                    Timber.d { "Navigation guidance active status: $isActive" }
                    if (isActive) "active" else "inactive"

                } catch (e: Exception) {
                    Timber.e(e) { "Error checking guidance status: ${e.message}" }
                    throw RpcError(
                        code = 1502,
                        message = "Failed to check guidance status: ${e.message}"
                    )
                }
            }

            Timber.i { "All RPC methods registered successfully" }

        } catch (e: Exception) {
            Timber.e(e) { "Failed to register RPC methods: ${e.message}" }
        }
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
            ACTION_SCREEN_CAPTURE_RESULT -> {
                handleScreenCaptureResult(intent)
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
        wakeWordDetector.startListening()
        Timber.i { "Wake word detection started in background" }
    }

    /**
     * Called when wake word "Solus" is detected
     * This shows the overlay UI on top of current app
     */
    override fun onWakeWordDetected() {
        Timber.i { "Wake word detected! Getting token and showing overlay..." }

        // Check if we have overlay permission
        if (!canDrawOverlays()) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
            return
        }

        // Stop wake word detection temporarily
        wakeWordDetector.stopListening()

        // Get token from server and then show the overlay UI
        requireToken { url, token ->
            connectionUrl = url
            connectionToken = token

            // Show the overlay UI
            showVoiceAssistantOverlay()

            // Update state
            isVoiceAssistantActive = true
            isMicOn = true
        }
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
                    isScreenShareOn = isScreenShareOn,
                    onMicToggle = {
                        isMicOn = !isMicOn
                        Timber.i { "Mic toggled: isMicOn=$isMicOn" }
                    },
                    onScreenShareToggle = {
                        toggleScreenSharing()
                    },
                    onSendMessage = { message ->
                        handleMessage(message)
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
     * Called when user toggles screen sharing from the UI
     * This requests media projection permission if starting, or stops sharing if stopping
     */
    private fun toggleScreenSharing() {
        if (!isScreenShareOn) {
            // Start screen sharing - request permission first
            requestScreenCapturePermission()
        } else {
            // Stop screen sharing - cleanup resources
            stopScreenSharing()
            isScreenShareOn = false
        }
    }

    /**
     * Request permission to capture the screen
     * This launches the system dialog to allow screen recording
     */
    private fun requestScreenCapturePermission() {
        // Create media projection manager to request screen capture
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Create an activity to handle the permission result
        val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * Start screen capture after permission is granted
     * This is called after receiving the screen capture intent
     */
    private fun startScreenCapture() {
        if (screenCaptureIntent == null) {
            Timber.e { "Cannot start screen capture - no intent available" }
            return
        }

        isScreenShareOn = true
        Timber.i { "Screen capture intent received, sharing will start when connected to room" }

        // Note: The actual screen sharing is handled in the VoiceAssistantOverlayContent composable
        // when the isScreenShareOn state changes
    }

    /**
     * Stop screen sharing and clean up resources
     */
    private fun stopScreenSharing() {
        audioCapturer?.releaseAudioResources()
        audioCapturer = null
        screenCaptureIntent = null
        isScreenShareOn = false
        Timber.i { "Screen sharing stopped and resources released" }
    }

    /**
     * Handle an incoming text message from the user
     * This will be sent to the voice assistant
     */
    private fun handleMessage(message: String) {
        if (message.isBlank()) return

        // In a real implementation, you would send this message to the LiveKit room
        // For now, we just log it
        Timber.i { "User sent message: $message" }

        // Update activity timestamp to prevent auto-close
        // This will be handled by the LaunchedEffect in the UI
    }

    /**
     * Close the voice assistant and return to wake word detection
     * Removes overlay and starts listening for "Solus" again
     */
    private fun closeVoiceAssistant() {
        Timber.i { "Closing voice assistant overlay" }

        // Clear any active navigation guidance
        if (::navigationGuidanceHandler.isInitialized) {
            navigationGuidanceHandler.clearCurrentGuidance()
        }

        // Clean up screen sharing if active
        if (isScreenShareOn) {
            stopScreenSharing()
        }

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
        isScreenShareOn = false
        textInput = ""
        connectionUrl = null
        connectionToken = null

        // Resume wake word detection
        wakeWordDetector.startListening()

        Toast.makeText(this, "Say 'Solus' to activate", Toast.LENGTH_SHORT).show()
    }

    /**
     * The actual UI content for the overlay
     * This shows the conversation and controls at bottom of screen
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun VoiceAssistantOverlayContent(
        connectionUrl: String,
        connectionToken: String,
        isMicOn: Boolean,
        isScreenShareOn: Boolean = false,
        onMicToggle: () -> Unit,
        onScreenShareToggle: () -> Unit,
        onSendMessage: (String) -> Unit,
        onClose: () -> Unit
    ) {
        // Track conversation activity for auto-close
        var lastActivityTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

        // Text input state and keyboard controller
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }

        // Get screen height to limit overlay height to 50%
        val configuration = LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.5f).dp

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
                // Header with status, screen share and close button
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

                    // Screen share button
                    IconButton(
                        onClick = onScreenShareToggle,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share_screen),
                            contentDescription = if (isScreenShareOn) "Stop screen sharing" else "Start screen sharing",
                            tint = if (isScreenShareOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
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
                    val coroutineScope = rememberCoroutineScope()

                    // Register RPC methods when room is connected
                    LaunchedEffect(room) {
                        registerRpcMethods(room)
                    }

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

                    // Control screen sharing state - FIXED VERSION
                    LaunchedEffect(isScreenShareOn) {
                        coroutineScope.launch(Dispatchers.IO) {
                            if (isScreenShareOn && screenCaptureIntent != null) {
                                try {
                                    // Check for RECORD_AUDIO permission
                                    if (ActivityCompat.checkSelfPermission(
                                            this@VoiceAssistantOverlayService,
                                            Manifest.permission.RECORD_AUDIO
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        Timber.w { "RECORD_AUDIO permission not granted for screen sharing" }
                                        return@launch
                                    }

                                    // Enable screen sharing
                                    room.localParticipant.setScreenShareEnabled(
                                        true,
                                        ScreenCaptureParams(screenCaptureIntent!!)
                                    )
                                    Timber.i { "Screen sharing enabled successfully" }

                                    // Wait for the screen share track to be available
                                    var retryCount = 0
                                    var screenCaptureTrack: LocalVideoTrack? = null
                                    while (screenCaptureTrack == null && retryCount < 10) {
                                        delay(500) // Wait for track to be available
                                        screenCaptureTrack = room.localParticipant
                                            .getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack
                                        retryCount++
                                    }

                                    if (screenCaptureTrack != null) {
                                        // Get the microphone track
                                        val audioTrack = room.localParticipant
                                            .getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack

                                        if (audioTrack != null) {
                                            // Create audio capturer for screen audio
                                            audioCapturer = ScreenAudioCapturer.createFromScreenShareTrack(screenCaptureTrack)
                                            audioCapturer?.let { capturer ->
                                                capturer.gain = 0.1f // Lower volume so mic can still be heard
                                                audioTrack.setAudioBufferCallback(capturer)
                                                Timber.i { "Screen audio capture setup complete" }
                                            }
                                        } else {
                                            Timber.w { "Audio track not available for screen audio capture" }
                                        }
                                    } else {
                                        Timber.e { "Screen capture track not available after retries" }
                                    }

                                } catch (e: Exception) {
                                    Timber.e(e) { "Failed to enable screen sharing: ${e.message}" }
                                }
                            } else if (!isScreenShareOn) {
                                try {
                                    // Clean up audio buffer callback first
                                    val audioTrack = room.localParticipant
                                        .getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
                                    audioTrack?.setAudioBufferCallback(null)

                                    // Release audio capturer resources
                                    audioCapturer?.releaseAudioResources()
                                    audioCapturer = null

                                    // Disable screen sharing
                                    room.localParticipant.setScreenShareEnabled(false)
                                    Timber.i { "Screen sharing disabled successfully" }

                                } catch (e: Exception) {
                                    Timber.e(e) { "Failed to disable screen sharing: ${e.message}" }
                                }
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

                    // Text input field
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            placeholder = { Text("Type a message...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (textInput.isNotBlank()) {
                                        onSendMessage(textInput)
                                        textInput = ""
                                        keyboardController?.hide()
                                    }
                                }
                            ),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        if (textInput.isNotBlank()) {
                                            onSendMessage(textInput)
                                            textInput = ""
                                            keyboardController?.hide()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Send message"
                                    )
                                }
                            }
                        )
                    }

                    // Controls row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
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

                        Spacer(modifier = Modifier.width(8.dp))

                        // Status text
                        Text(
                            text = if (isMicOn) "Listening..." else "Muted",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isMicOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // Clear navigation guidance
        if (::navigationGuidanceHandler.isInitialized) {
            navigationGuidanceHandler.clearCurrentGuidance()
        }

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

    /**
     * Handle the result of screen capture permission request
     * This is called from ScreenCaptureActivity with the result of the system permission dialog
     */
    private fun handleScreenCaptureResult(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            // Store the screen capture intent for later use when connected to the room
            screenCaptureIntent = data
            startScreenCapture()
        } else {
            // Permission denied or canceled
            Timber.w { "Screen capture permission not granted" }
            isScreenShareOn = false
            Toast.makeText(
                this,
                "Screen sharing permission denied",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}