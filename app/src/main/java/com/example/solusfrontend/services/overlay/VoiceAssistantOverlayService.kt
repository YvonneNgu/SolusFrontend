@file:OptIn(Beta::class)

package com.example.solusfrontend.services.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
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
import io.livekit.android.compose.state.AgentState
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
import kotlin.math.roundToInt

/**
 * Background Voice Assistant Service with Bubble UI and Screen Sharing
 *
 * This service runs in the background and shows a floating bubble UI when the voice assistant is activated.
 * The bubble can be expanded to show conversation history, collapsed to show just the bubble,
 * or dragged around the screen.
 */
class VoiceAssistantOverlayService : Service(),
    WakeWordDetector.WakeWordCallback,
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // System overlay window manager
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null

    // Wake word detection
    private lateinit var wakeWordDetector: WakeWordDetector

    // Navigation guidance handler
    private lateinit var navigationGuidanceHandler: NavigationGuidanceHandler

    // Bubble state
    private var isBubbleExpanded by mutableStateOf(false)
    private var isVoiceAssistantActive by mutableStateOf(false)
    private var isMicOn by mutableStateOf(true)
    private var isScreenShareOn by mutableStateOf(false)
    private var bubblePosition by mutableStateOf(IntOffset(0, 0))
    private var lastBubblePositionBeforeExpand by mutableStateOf(IntOffset(0, 0))
    
    // Debug mode - set to true to show debug visuals
    private val isDebugMode = true

    // Connection details for LiveKit
    private var connectionUrl: String? = null
    private var connectionToken: String? = null

    // Screen capture
    private var screenCaptureIntent: Intent? = null
    private var audioCapturer: ScreenAudioCapturer? = null

    // Lifecycle management
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    companion object {
        private const val NOTIFICATION_ID = 12345
        private const val CHANNEL_ID = "voice_assistant_channel"

        const val ACTION_START_SERVICE = "START_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val ACTION_SCREEN_CAPTURE_RESULT = "SCREEN_CAPTURE_RESULT"
        const val ACTION_BUBBLE_EXPAND = "BUBBLE_EXPAND"
        const val ACTION_BUBBLE_CLOSE = "BUBBLE_CLOSE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        // Bubble constants
        private const val BUBBLE_SIZE = 60 // dp
        private const val PANEL_MAX_HEIGHT_RATIO = 0.4f
    }

    override fun onCreate() {
        super.onCreate()

        // Initialize lifecycle
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wakeWordDetector = WakeWordDetector(this, this)
        wakeWordDetector.initialize()

        initializeNavigationGuidance()
        createNotificationChannels()

        Timber.d { "VoiceAssistantOverlayService created" }
    }

    private fun initializeNavigationGuidance() {
        navigationGuidanceHandler = NavigationGuidanceHandler(this)
        Timber.i { "Navigation guidance handler initialized" }
    }

    private suspend fun registerRpcMethods(room: Room) {
        try {
            room.localParticipant.registerRpcMethod("display-navigation-guidance") { data ->
                try {
                    Timber.i { "Received navigation guidance RPC call from ${data.callerIdentity}" }
                    val response = navigationGuidanceHandler.handleNavigationGuidance(data.payload)
                    Timber.i { "Navigation guidance RPC handled successfully" }
                    response
                } catch (e: Exception) {
                    Timber.e(e) { "Error handling navigation guidance RPC: ${e.message}" }
                    throw RpcError(code = 1500, message = "Failed to display navigation guidance: ${e.message}")
                }
            }

            room.localParticipant.registerRpcMethod("clear-navigation-guidance") { data ->
                try {
                    navigationGuidanceHandler.clearCurrentGuidance()
                    Timber.i { "Navigation guidance cleared successfully" }
                    "Navigation guidance cleared successfully"
                } catch (e: Exception) {
                    Timber.e(e) { "Error clearing navigation guidance: ${e.message}" }
                    throw RpcError(code = 1501, message = "Failed to clear navigation guidance: ${e.message}")
                }
            }

            room.localParticipant.registerRpcMethod("is-guidance-active") { data ->
                try {
                    val isActive = navigationGuidanceHandler.isGuidanceActive()
                    Timber.d { "Navigation guidance active status: $isActive" }
                    if (isActive) "active" else "inactive"
                } catch (e: Exception) {
                    Timber.e(e) { "Error checking guidance status: ${e.message}" }
                    throw RpcError(code = 1502, message = "Failed to check guidance status: ${e.message}")
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
            ACTION_BUBBLE_EXPAND -> {
                toggleBubbleExpansion()
            }
            ACTION_BUBBLE_CLOSE -> {
                closeVoiceAssistant()
            }
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        Timber.i { "Voice Assistant is now listening in background" }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Background service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Voice assistant background service"
                setShowBadge(false)
            }

            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, VoiceAssistantOverlayService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Assistant")
            .setContentText("Say 'Solus' to activate • Tap to stop")
            .setSmallIcon(R.drawable.ic_mic_gradiant)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, "Stop", stopPendingIntent)
            .build()
    }

    private fun startWakeWordDetection() {
        wakeWordDetector.startListening()
        Timber.i { "Wake word detection started in background" }
    }

    override fun onWakeWordDetected() {
        Timber.i { "Wake word detected! Getting token and showing bubble..." }

        if (!canDrawOverlays()) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
            return
        }

        wakeWordDetector.stopListening()

        // Add a debug toast to show we're getting the token
        if (isDebugMode) {
            Toast.makeText(this, "DEBUG: Getting LiveKit token...", Toast.LENGTH_SHORT).show()
        }

        requireToken { url, token ->
            connectionUrl = url
            connectionToken = token
            Timber.d { "UI-DEBUG: Got token - URL: $url, Token length: ${token.length}" }
            
            // Add a debug toast to show we got the token
            if (isDebugMode) {
                Toast.makeText(
                    this, 
                    "DEBUG: Got token - URL: ${url.take(20)}...", 
                    Toast.LENGTH_SHORT
                ).show()
            }
            
            showVoiceAssistantBubble()
            isVoiceAssistantActive = true
            isMicOn = true
        }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    /**
     * Show the floating bubble and optionally the conversation panel
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun showVoiceAssistantBubble() {
        if (bubbleView != null) return

        // Initialize bubble position to center-right of screen
        val displayMetrics = resources.displayMetrics
        
        // Set expanded state BEFORE creating bubble
        isBubbleExpanded = true
        
        // Calculate dimensions for expanded state
        val panelHeight = (displayMetrics.heightPixels * PANEL_MAX_HEIGHT_RATIO).toInt()
        val bubbleSizePx = (BUBBLE_SIZE * displayMetrics.density).toInt()
        val rightMargin = 16 * displayMetrics.density.toInt()
        
        // Set initial position - first to center-right (will be saved as pre-expand position)
        lastBubblePositionBeforeExpand = IntOffset(
            displayMetrics.widthPixels - (BUBBLE_SIZE * displayMetrics.density + 32).toInt(),
            displayMetrics.heightPixels / 2
        )
        
        // Then set position for expanded state (at top-right of panel)
        val expandedX = displayMetrics.widthPixels - bubbleSizePx - rightMargin
        val expandedY = displayMetrics.heightPixels - panelHeight - bubbleSizePx - rightMargin
        bubblePosition = IntOffset(expandedX, expandedY)

        Timber.d { "UI-DEBUG: Creating bubble - screen size: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}, " +
                "panel height: $panelHeight, bubble size: $bubbleSizePx, " +
                "expanded position: $expandedX,$expandedY" }

        // Create the bubble with proper window parameters for expanded state
        createBubble()

        Timber.i { "Voice assistant bubble shown in expanded state at position $bubblePosition" }
    }


    private fun createBubble() {
        Timber.d { "UI-DEBUG: Creating ComposeView with expanded=${isBubbleExpanded}" }

        bubbleView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@VoiceAssistantOverlayService)
            setViewTreeViewModelStoreOwner(this@VoiceAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@VoiceAssistantOverlayService)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)

            setContent {
                Timber.d { "UI-DEBUG: Setting content for ComposeView" }
                VoiceAssistantBubble(
                    isMicOn = isMicOn,
                    isExpanded = isBubbleExpanded,
                    onBubbleClick = { toggleBubbleExpansion() },
                    onBubbleMove = { offset -> updateBubblePosition(offset) },
                    onClose = { closeVoiceAssistant() }
                )
            }
        }

        // Create parameters for the window
        val displayMetrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            if (isBubbleExpanded) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            if (isBubbleExpanded) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            (if (!isBubbleExpanded) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0) or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            
            if (isBubbleExpanded) {
                x = 0
                y = 0
            } else {
                x = bubblePosition.x
                y = bubblePosition.y
            }
            
            dimAmount = 0.0f
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            }
        }

        try {
            windowManager.addView(bubbleView, params)
            Timber.i { "Bubble view added with expanded=${isBubbleExpanded}, size=${params.width}x${params.height}" }
        } catch (e: Exception) {
            Timber.e { "Failed to show bubble: $e" }
            closeVoiceAssistant()
        }
    }

    private fun toggleBubbleExpansion() {
        val previousState = isBubbleExpanded
        isBubbleExpanded = !isBubbleExpanded

        Timber.d { "UI-DEBUG: Toggling bubble expansion from $previousState to $isBubbleExpanded" }

        // Instead of recreating, just update window parameters
        updateBubbleWindowParameters()

        Timber.i { "Bubble expansion toggled: $isBubbleExpanded" }
    }

    /**
    * Updates window parameters without recreating the view
    * This preserves the RoomScope and maintains connection
    */
    private fun updateBubbleWindowParameters() {
        val displayMetrics = resources.displayMetrics

        // Calculate positions based on current state
        if (isBubbleExpanded) {
            // Store current position before expanding if not already stored
            if (lastBubblePositionBeforeExpand == IntOffset.Zero) {
                lastBubblePositionBeforeExpand = bubblePosition
                Timber.d { "UI-DEBUG: Storing position before expand: $lastBubblePositionBeforeExpand" }
            }

            // Calculate position for bubble when panel is expanded
            val panelHeight = (displayMetrics.heightPixels * PANEL_MAX_HEIGHT_RATIO).toInt()
            val bubbleSizePx = (BUBBLE_SIZE * displayMetrics.density).toInt()
            val rightMargin = 16 * displayMetrics.density.toInt()

            // Position bubble at top-right of panel area
            val newX = displayMetrics.widthPixels - bubbleSizePx - rightMargin
            val newY = displayMetrics.heightPixels - panelHeight - bubbleSizePx - rightMargin
            bubblePosition = IntOffset(newX, newY)
            
            Timber.d { "UI-DEBUG: Expanded - setting new position: $newX,$newY" }
        } else {
            // Restore bubble to previous position if we have one saved
            if (lastBubblePositionBeforeExpand != IntOffset.Zero) {
                bubblePosition = lastBubblePositionBeforeExpand
                Timber.d { "UI-DEBUG: Collapsed - restoring position: $lastBubblePositionBeforeExpand" }
            }
        }

        // Update existing window parameters instead of recreating
        bubbleView?.let { view ->
            val params = view.layoutParams as WindowManager.LayoutParams
            
            // Update window size and position based on expansion state
            if (isBubbleExpanded) {
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.MATCH_PARENT
                params.x = 0
                params.y = 0
                // Allow focus when expanded for text input
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.x = bubblePosition.x
                params.y = bubblePosition.y
                // Set NOT_FOCUSABLE when collapsed
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }

            try {
                windowManager.updateViewLayout(view, params)
                Timber.d { "UI-DEBUG: Updated window params - expanded: $isBubbleExpanded" }
            } catch (e: Exception) {
                Timber.e { "Error updating window layout: $e" }
            }
        }
    }

    private fun updateBubblePosition(offset: IntOffset) {
        bubblePosition = offset

        // Only update the WindowManager layout if the bubble is collapsed.
        // If expanded, the bubble is positioned inside the Compose content.
        if (!isBubbleExpanded) {
            bubbleView?.let { view ->
                val params = view.layoutParams as WindowManager.LayoutParams
                params.x = offset.x
                params.y = offset.y
                try {
                    windowManager.updateViewLayout(view, params)
                    Timber.d { "UI-DEBUG: Updated bubble (window) position to $offset" }
                } catch (e: Exception) {
                    Timber.w { "Error updating bubble position: $e" }
                }
            }
        } else {
            // When expanded, we just update the state for recomposition.
            Timber.d { "UI-DEBUG: Updated bubble (internal composable) position to $offset" }
        }
    }

    private fun toggleMic() {
        isMicOn = !isMicOn
        Timber.i { "Mic toggled: isMicOn=$isMicOn" }
    }

    private fun toggleScreenSharing() {
        if (!isScreenShareOn) {
            requestScreenCapturePermission()
        } else {
            stopScreenSharing()
            isScreenShareOn = false
        }
    }

    private fun requestScreenCapturePermission() {
        val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startScreenCapture() {
        if (screenCaptureIntent == null) {
            Timber.e { "Cannot start screen capture - no intent available" }
            return
        }
        isScreenShareOn = true
        Timber.i { "Screen capture intent received, sharing will start when connected to room" }
    }

    private fun stopScreenSharing() {
        audioCapturer?.releaseAudioResources()
        audioCapturer = null
        screenCaptureIntent = null
        isScreenShareOn = false
        Timber.i { "Screen sharing stopped and resources released" }
    }

    private fun handleMessage(message: String) {
        if (message.isBlank()) return
        Timber.i { "User sent message: $message" }
    }

    private fun closeVoiceAssistant() {
        Timber.i { "Closing voice assistant bubble" }

        if (::navigationGuidanceHandler.isInitialized) {
            navigationGuidanceHandler.clearCurrentGuidance()
        }

        if (isScreenShareOn) {
            stopScreenSharing()
        }

        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Timber.w { "Error removing bubble view: $e" }
            }
        }
        bubbleView = null

        // Reset state
        isVoiceAssistantActive = false
        isBubbleExpanded = false
        isMicOn = true
        isScreenShareOn = false
        connectionUrl = null
        connectionToken = null

        // Resume wake word detection
        wakeWordDetector.startListening()
        Toast.makeText(this, "Say 'Solus' to activate", Toast.LENGTH_SHORT).show()
    }

    /**
     * The main bubble UI component
     */
    @Composable
    private fun VoiceAssistantBubble(
        isMicOn: Boolean,
        isExpanded: Boolean,
        onBubbleClick: () -> Unit,
        onBubbleMove: (IntOffset) -> Unit,
        onClose: () -> Unit
    ) {
        val density = LocalDensity.current
        var isDragging by remember { mutableStateOf(false) }
        var dragStartPosition by remember { mutableStateOf(IntOffset.Zero) }

        // Animation for speaking state
        val infiniteTransition = rememberInfiniteTransition(label = "bubble_pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )

        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp
        val panelHeight = screenHeight * PANEL_MAX_HEIGHT_RATIO
        
        Timber.d { "UI-DEBUG: VoiceAssistantBubble composable - isExpanded: $isExpanded, " +
                "screenHeight: $screenHeight, panelHeight: $panelHeight, " +
                "connectionUrl: ${connectionUrl != null}, connectionToken: ${connectionToken != null}" }

        // Main container for entire UI
        Box(modifier = Modifier.fillMaxSize()) {
            // Debug overlay to visualize panel area
            if (isDebugMode && isExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(panelHeight.dp)
                        .align(Alignment.BottomCenter)
                        .background(Color.Red.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = "DEBUG: Panel Area (${configuration.screenWidthDp}x$panelHeight)",
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                    )
                }
            }
            
            // Only show RoomScope when we have connection details
            if (connectionUrl != null && connectionToken != null) {
                // Debug connection info
                if (isDebugMode) {
                    Text(
                        text = "DEBUG: Connecting to LiveKit...",
                        color = Color.Yellow,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 40.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp)
                    )
                }
                
                RoomScope(
                    url = connectionUrl!!,
                    token = connectionToken!!,
                    audio = true,
                    video = false,
                    connect = true,
                    connectOptions = ConnectOptions(autoSubscribe = true),
                    roomOptions = RoomOptions(adaptiveStream = true, dynacast = true)
                ) { room ->
                    val voiceAssistant = rememberVoiceAssistant()
                    val coroutineScope = rememberCoroutineScope()
                    
                    // Track previous state to avoid redundant logging
                    var prevRoomState by remember { mutableStateOf<Room.State?>(null) }
                    var prevIsExpanded by remember { mutableStateOf(isExpanded) }
                    var prevBubblePosition by remember { mutableStateOf(bubblePosition) }
                    
                    // Only log when room state changes
                    if (room.state != prevRoomState) {
                        Timber.d { "UI-DEBUG: Inside RoomScope - room connected: ${room.state}" }
                        prevRoomState = room.state
                    }
                    
                    // Debug connection status
                    if (isDebugMode) {
                        Text(
                            text = "DEBUG: Room state: ${room.state}",
                            color = Color.Green,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp)
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(4.dp)
                        )
                    }

                    // Register RPC methods
                    LaunchedEffect(room) {
                        registerRpcMethods(room)
                    }

                    // Connection monitoring
                    LaunchedEffect(room) {
                        snapshotFlow { room.state }
                            .collect { state ->
                                Timber.d { "UI-DEBUG: Room connection state changed: $state" }
                            }
                    }

                    // Handle screen sharing
                    LaunchedEffect(isScreenShareOn) {
                        if (isScreenShareOn && screenCaptureIntent != null) {
                            handleScreenCapture(room)
                        }
                    }
                    
                    // First render conversation panel if expanded
                    if (isExpanded) {
                        // Only log when expansion state changes
                        if (prevIsExpanded != isExpanded) {
                            Timber.d { "UI-DEBUG: About to render conversation panel" }
                            prevIsExpanded = isExpanded
                        }
                        
                        // Conversation panel - positioned at bottom
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(panelHeight.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                )
                                .padding(1.dp) // Add slight padding to make the background visible
                                .then(
                                    if (isDebugMode) Modifier.border(
                                        width = 2.dp,
                                        color = Color.Green,
                                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                    ) else Modifier
                                )
                        ) {
                            // Only log once when panel content is rendered
                            if (prevIsExpanded != isExpanded) {
                                Timber.d { "UI-DEBUG: Rendering conversation panel content" }
                            }
                            
                            VoiceAssistantConversationPanelContent(
                                room = room,
                                voiceAssistant = voiceAssistant,
                                isMicOn = isMicOn,
                                isScreenShareOn = isScreenShareOn,
                                onMicToggle = { toggleMic() },
                                onScreenShareToggle = { toggleScreenSharing() },
                                onSendMessage = { message -> handleMessage(message) }
                            )
                        }
                    }
                    
                    // Bubble is positioned absolutely within the full-screen window
                    Box(
                        modifier = Modifier.offset {
                            // When the window is MATCH_PARENT (expanded), we offset the bubble inside it.
                            // When the window is WRAP_CONTENT (collapsed), the bubble is at (0,0) within the window.
                            if(isBubbleExpanded) bubblePosition else IntOffset.Zero
                        }
                    ) {
                        // Only log when bubble position changes
                        val currentOffset = if(isBubbleExpanded) bubblePosition else IntOffset.Zero
                        if (prevBubblePosition != currentOffset) {
                            Timber.d { "UI-DEBUG: Rendering bubble UI at offset: $currentOffset" }
                            prevBubblePosition = currentOffset
                        }
                        BubbleUI(
                            isMicOn = isMicOn,
                            isExpanded = isExpanded,
                            isDragging = isDragging,
                            pulseScale = pulseScale,
                            onDragStart = { isDragging = true; dragStartPosition = bubblePosition },
                            onDragEnd = { isDragging = false },
                            onDrag = { change, dragAmount ->
                                if (isDragging) {
                                    val newPosition = IntOffset(
                                        (bubblePosition.x + dragAmount.x).toInt(),
                                        (bubblePosition.y + dragAmount.y).toInt()
                                    )
                                    // onBubbleMove calls updateBubblePosition
                                    onBubbleMove(newPosition)
                                }
                            },
                            onClick = { if (!isDragging) onBubbleClick() },
                            onClose = onClose
                        )
                    }
                }
            } else {
                // Simple bubble when no connection
                Timber.d { "UI-DEBUG: Rendering simple bubble (no connection)" }
                
                // Debug message when expanded but no connection
                if (isDebugMode && isExpanded) {
                    Text(
                        text = "DEBUG: No connection - URL: ${connectionUrl != null}, Token: ${connectionToken != null}",
                        color = Color.Red,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(8.dp)
                    )
                }
                
                BubbleUI(
                    isMicOn = isMicOn,
                    isExpanded = isExpanded,
                    isDragging = isDragging,
                    pulseScale = pulseScale,
                    onDragStart = { isDragging = true; dragStartPosition = bubblePosition },
                    onDragEnd = { isDragging = false },
                    onDrag = { change, dragAmount ->
                        if (isDragging) {
                            val newPosition = IntOffset(
                                (bubblePosition.x + dragAmount.x).toInt(),
                                (bubblePosition.y + dragAmount.y).toInt()
                            )
                            onBubbleMove(newPosition)
                        }
                    },
                    onClick = { if (!isDragging) onBubbleClick() },
                    onClose = onClose
                )
            }
        }
    }
    
    @Composable
    private fun BubbleUI(
        isMicOn: Boolean,
        isExpanded: Boolean,
        isDragging: Boolean,
        pulseScale: Float,
        onDragStart: () -> Unit,
        onDragEnd: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
        onClick: () -> Unit,
        onClose: () -> Unit
    ) {
        // Main bubble
        Box(
            modifier = Modifier
                .size(BUBBLE_SIZE.dp)
                .scale(if (isMicOn && !isExpanded && !isDragging) pulseScale else 1f)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDrag = onDrag
                    )
                }
                .clickable(enabled = !isDragging) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            // Bubble background and icon
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = if (isMicOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic_gradiant),
                    contentDescription = "Voice Assistant",
                    tint = if (isMicOn) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Close button when expanded
            if (isExpanded) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(20.dp)
                        .offset(x = 20.dp, y = (-20).dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun VoiceAssistantConversationPanelContent(
        room: Room,
        voiceAssistant: io.livekit.android.compose.state.VoiceAssistant,
        isMicOn: Boolean,
        isScreenShareOn: Boolean,
        onMicToggle: () -> Unit,
        onScreenShareToggle: () -> Unit,
        onSendMessage: (String) -> Unit
    ) {
        // Track previous state values to avoid redundant logging
        val previousState = remember { mutableStateOf<String?>(null) }
        
        var textInput by remember { mutableStateOf("") }
        var lastActivityTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusRequester = remember { FocusRequester() }
        val agentState = voiceAssistant.state
        val coroutineScope = rememberCoroutineScope()
        
        // Log panel dimensions and state only when they change
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val screenWidth = configuration.screenWidthDp
        val screenHeight = configuration.screenHeightDp
        val panelHeight = screenHeight * PANEL_MAX_HEIGHT_RATIO
        
        // Create current state string for comparison
        val currentState = "width: $screenWidth, height: $panelHeight, agent: $agentState, mic: $isMicOn, share: $isScreenShareOn"
        
        // Only log if state has changed
        if (currentState != previousState.value) {
            Timber.d { "UI-DEBUG: Panel dimensions - width: $screenWidth, height: $panelHeight, " +
                    "agent state: $agentState, mic: $isMicOn, screen share: $isScreenShareOn" }
            previousState.value = currentState
        }

        // FIXED: Use Surface instead of Column directly for better visibility
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp), // Reduced padding
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp) // Internal padding
            ) {
                // Header with better styling
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic_gradiant),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Voice Assistant",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Screen share button with better styling
                    Surface(
                        onClick = onScreenShareToggle,
                        modifier = Modifier.size(32.dp),
                        shape = CircleShape,
                        color = if (isScreenShareOn) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share_screen),
                            contentDescription = if (isScreenShareOn) "Stop screen sharing" else "Start screen sharing",
                            tint = if (isScreenShareOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(6.dp)
                                .size(20.dp)
                        )
                    }
                }

                // Activity tracking
                LaunchedEffect(agentState) {
                    lastActivityTime = System.currentTimeMillis()
                }

                // Control microphone
                LaunchedEffect(isMicOn) {
                    try {
                        room.localParticipant.setMicrophoneEnabled(isMicOn)
                        Timber.i { "Microphone ${if (isMicOn) "enabled" else "disabled"}" }
                    } catch (e: Exception) {
                        Timber.e { "Failed to toggle microphone: $e" }
                    }
                }

                // Auto-close after inactivity
                LaunchedEffect(lastActivityTime) {
                    delay(30000)
                    val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                    if (timeSinceLastActivity >= 30000) {
                        Timber.i { "Auto-closing due to inactivity" }
                        closeVoiceAssistant()
                    }
                }

                // Audio visualizer with background
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    VoiceAssistantBarVisualizer(
                        voiceAssistant = voiceAssistant,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Conversation history with better container
                val segments = rememberTranscriptions()
                val localSegments = rememberParticipantTranscriptions(room.localParticipant)
                val lazyListState = rememberLazyListState()

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (segments.isEmpty()) {
                            item {
                                Text(
                                    text = "Start speaking or type a message...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        items(
                            items = segments,
                            key = { segment -> segment.id }
                        ) { segment ->
                            if (localSegments.contains(segment)) {
                                // User message
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Surface(
                                        modifier = Modifier.widthIn(max = 200.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                        shape = RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp)
                                    ) {
                                        Text(
                                            text = segment.text,
                                            modifier = Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            } else {
                                // Assistant message
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    Surface(
                                        modifier = Modifier.widthIn(max = 200.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp),
                                        tonalElevation = 2.dp
                                    ) {
                                        Text(
                                            text = segment.text,
                                            modifier = Modifier.padding(8.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
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

                // Text input with better styling
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            "Type a message...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
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
                                contentDescription = "Send message",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Controls with better layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            onClick = onMicToggle,
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = if (isMicOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_mic_gradiant),
                                contentDescription = if (isMicOn) "Mute microphone" else "Unmute microphone",
                                tint = if (isMicOn) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column {
                            Text(
                                text = if (isMicOn) "Listening" else "Muted",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMicOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                            if (isMicOn) {
                                Text(
                                    text = when (agentState) { // wrong code
                                        AgentState.LISTENING -> "Listening..."
                                        AgentState.THINKING -> "Thinking..."
                                        AgentState.SPEAKING -> "Speaking..."
                                        else -> "Ready"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Status indicator
                    if (isScreenShareOn) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share_screen),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Sharing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
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

        if (::navigationGuidanceHandler.isInitialized) {
            navigationGuidanceHandler.clearCurrentGuidance()
        }

        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Timber.w { "Error removing bubble on destroy: $e" }
            }
        }

        wakeWordDetector.stopListening()
        wakeWordDetector.release()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        Timber.d { "VoiceAssistantOverlayService destroyed" }
    }

    private fun handleScreenCaptureResult(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultCode == Activity.RESULT_OK && data != null) {
            screenCaptureIntent = data
            startScreenCapture()
        } else {
            Timber.e { "Screen capture permission denied or canceled" }
            isScreenShareOn = false
        }
    }

    /**
     * Handle screen capture with the provided Room instance
     * This is now separate from the UI to support our new architecture
     */
    private suspend fun handleScreenCapture(room: Room) {
        if (screenCaptureIntent == null) {
            Timber.e { "Cannot start screen capture - no intent available" }
            return
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Timber.w { "RECORD_AUDIO permission not granted for screen sharing" }
                return
            }

            // Enable screen share in LiveKit
            room.localParticipant.setScreenShareEnabled(
                true,
                ScreenCaptureParams(screenCaptureIntent!!)
            )
            Timber.i { "Screen sharing enabled successfully" }

            // Setup audio capture
            var retryCount = 0
            var screenCaptureTrack: LocalVideoTrack? = null
            while (screenCaptureTrack == null && retryCount < 10) {
                delay(500)
                screenCaptureTrack = room.localParticipant
                    .getTrackPublication(Track.Source.SCREEN_SHARE)?.track as? LocalVideoTrack
                retryCount++
            }

            if (screenCaptureTrack != null) {
                val audioTrack = room.localParticipant
                    .getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack

                if (audioTrack != null) {
                    audioCapturer = ScreenAudioCapturer.createFromScreenShareTrack(screenCaptureTrack)
                    audioCapturer?.let { capturer ->
                        capturer.gain = 0.1f
                        audioTrack.setAudioBufferCallback(capturer)
                        Timber.i { "Screen audio capture setup complete" }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e) { "Failed to enable screen sharing: ${e.message}" }
        }
    }
}