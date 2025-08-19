package com.example.solusfrontend.services.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.Color as Color
import android.graphics.Paint as  Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.ajalt.timberkt.Timber
import org.json.JSONObject
import kotlin.math.*

/**
 * Handles navigation guidance visual cues and instructions
 * This class manages the display of arrows, highlights, and instruction text
 * to guide users through app navigation tasks
 */
class NavigationGuidanceHandler(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var currentOverlay: ComposeView? = null
    private var currentInstructionView: ComposeView? = null
    private val dismissalHandler = Handler(Looper.getMainLooper())
    private var dismissalRunnable: Runnable? = null

    private val screenWidth: Int
    private val screenHeight: Int
    private val topSystemBar: Int

    // Lifecycle owner for ComposeView
    private val lifecycleOwner = OverlayLifecycleOwner()

    companion object {
        private const val OVERLAY_DISPLAY_DURATION = 300000L // 5 min
        private const val ARROW_SIZE = 100f
        private const val ARROW_STROKE_WIDTH = 13f
        private const val HIGHLIGHT_PADDING = 16f
    }

    init {
        // Initialize screen dimensions once in the init block
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars()
            )
            screenWidth = windowMetrics.bounds.width()
            screenHeight = windowMetrics.bounds.height()
            topSystemBar = insets.top

            Timber.i {"insets-bottom: ${insets.bottom}"}
        } else {
            @Suppress("DEPRECATION")
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val realMetrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(realMetrics)

            screenWidth = realMetrics.widthPixels
            screenHeight = realMetrics.heightPixels

            // Log the difference to see what was excluded
            topSystemBar = screenHeight - displayMetrics.heightPixels
        }

        Timber.i { "Screen dimensions initialized - Width: $screenWidth, Height: $screenHeight"}
        Timber.i {"insets-top: $topSystemBar"}
    }

    /**
     * Custom LifecycleOwner and SavedStateRegistryOwner for overlay ComposeViews
     */
    private inner class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    /**
     * Parse RPC payload and display navigation guidance
     * Called from the RPC method handler
     */
    fun handleNavigationGuidance(payload: String): String {
        try {
            Timber.i { "Parsing navigation guidance payload: $payload" }

            val jsonPayload = JSONObject(payload)
            val instructionText = jsonPayload.getString("instruction_text")
            val boundingBoxArray = jsonPayload.getJSONArray("bounding_box")
            val visualCueType = jsonPayload.optString("visual_cue_type", "arrow")

            // Extract bounding box coordinates [top, left, bottom, right]
            val top = getRelativeYPosition(boundingBoxArray.getDouble(0))
            val left = getRelativeXPosition(boundingBoxArray.getDouble(1))
            val bottom = getRelativeYPosition(boundingBoxArray.getDouble(2))
            val right = getRelativeXPosition(boundingBoxArray.getDouble(3))

            Timber.i { "Navigation guidance - Instruction: $instructionText, BoundingBox: [$top, $left, $bottom, $right], VisualCue: $visualCueType" }

            // Display on UI thread
            Handler(Looper.getMainLooper()).post {
                displayNavigationGuidance(
                    instructionText = instructionText,
                    top = top,
                    left = left,
                    bottom = bottom,
                    right = right,
                    visualCueType = visualCueType
                )
            }

            return "Navigation guidance displayed successfully"

        } catch (e: Exception) {
            Timber.e(e) { "Error parsing navigation guidance payload: ${e.message}" }
            throw Exception("Failed to display navigation guidance: ${e.message}")
        }
    }

    private fun getRelativeXPosition(position: Double): Int {
        return (position / 1000 * screenWidth).toInt()
    }

    private fun getRelativeYPosition(position: Double): Int {
        return (position / 1000 * screenHeight - topSystemBar).toInt()
    }

    /**
     * Display navigation guidance with visual cues and instruction text
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun displayNavigationGuidance(
        instructionText: String,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        visualCueType: String
    ) {
        try {
            // Clear any existing guidance
            clearCurrentGuidance()

            // Create and display visual cues with instruction text positioned dynamically
            when (visualCueType.lowercase()) {
                "arrow" -> createArrowOverlay(left, top, right, bottom, instructionText)
                else -> {
                    Timber.w { "Unknown visual cue type: $visualCueType, defaulting to arrow" }
                    createArrowOverlay(left, top, right, bottom, instructionText)
                }
            }

            // Schedule auto-dismissal
            scheduleOverlayDismissal()

            Timber.i { "Navigation guidance displayed successfully" }

        } catch (e: Exception) {
            Timber.e(e) { "Failed to display navigation guidance: ${e.message}" }
        }
    }

    /**
     * Create arrow overlay pointing to the target element with dynamic instruction positioning
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun createArrowOverlay(left: Int, top: Int, right: Int, bottom: Int, instructionText: String) {
        // Calculate center point of the target element
        val centerX = (right + left) / 2
        val centerY = (top + bottom) / 2

        val composeView = ComposeView(context).apply {
            // Set lifecycle and saved state registry owners
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                NavigationOverlayComposable(
                    targetCenterX = centerX,
                    targetCenterY = centerY,
                    targetLeft = left,
                    targetTop = top,
                    targetRight = right,
                    targetBottom = bottom,
                    instructionText = instructionText
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(composeView, params)
        currentOverlay = composeView

        Timber.d { "Arrow overlay created at center: ($centerX, $centerY), bounds: [$left, $top, $right, $bottom]" }
    }

    // 4. Enhanced pulse animation with more dramatic effect
    @Composable
    private fun NavigationOverlayComposable(
        targetCenterX: Int,
        targetCenterY: Int,
        targetLeft: Int,
        targetTop: Int,
        targetRight: Int,
        targetBottom: Int,
        instructionText: String
    ) {
        // Combined scale and movement animation for arrow
        val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
        val arrowScale by infiniteTransition.animateFloat(
            initialValue = 0.85f, // Smaller scale (nearer to box)
            targetValue = 1.1f,  // Larger scale (further from box)
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1200,
                    easing = EaseInOutSine
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "arrow_scale"
        )

        // Movement offset - when large (1.3x), move 100px away from box
        val arrowMovement by infiniteTransition.animateFloat(
            initialValue = 0f,   // No movement when small
            targetValue = 100f,  // 100px away when large
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1200,
                    easing = EaseInOutSine
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "arrow_movement"
        )

        var isVisible by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            isVisible = true
        }

        val alpha by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0f,
            animationSpec = tween(
                durationMillis = 400, // Slightly slower entrance
                easing = EaseOutCubic
            ),
            label = "instruction_alpha"
        )

        Box(modifier = Modifier.fillMaxSize().alpha(alpha)) {
            // Semi-transparent overlay to dim background
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Color(0x20000000), // Very subtle dark overlay
                    size = size
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                drawNavigationOverlay(
                    targetCenterX = targetCenterX,
                    targetCenterY = targetCenterY,
                    targetLeft = targetLeft,
                    targetTop = targetTop,
                    targetRight = targetRight,
                    targetBottom = targetBottom,
                    arrowScale = arrowScale,
                    arrowMovement = arrowMovement // Add movement parameter
                )
            }

            InstructionTextPositioned(
                instructionText = instructionText,
                targetLeft = targetLeft,
                targetTop = targetTop,
                targetRight = targetRight,
                targetBottom = targetBottom,
                targetCenterX = targetCenterX,
                targetCenterY = targetCenterY
            )
        }
    }

    private fun DrawScope.drawNavigationOverlay(
        targetCenterX: Int,
        targetCenterY: Int,
        targetLeft: Int,
        targetTop: Int,
        targetRight: Int,
        targetBottom: Int,
        arrowScale: Float,
        arrowMovement: Float
    ) {
        // Draw highlight around target element
        drawHighlight(targetLeft, targetTop, targetRight, targetBottom)

        // Draw arrow pointing to target
        drawArrow(targetCenterX, targetCenterY, targetTop, targetBottom, arrowScale, arrowMovement)
    }

    // 1. Enhanced highlight with glow effect and better colors
    private fun DrawScope.drawHighlight(
        targetLeft: Int,
        targetTop: Int,
        targetRight: Int,
        targetBottom: Int
    ) {
        val rect = Rect(
            offset = Offset(
                (targetLeft - HIGHLIGHT_PADDING),
                (targetTop - HIGHLIGHT_PADDING)
            ),
            size = androidx.compose.ui.geometry.Size(
                (targetRight - targetLeft + 2 * HIGHLIGHT_PADDING),
                (targetBottom - targetTop + 2 * HIGHLIGHT_PADDING)
            )
        )

        // Draw glow effect (multiple layers with decreasing opacity)
        val glowColors = listOf(
            Color(0x80FF6B35), // Outer glow - more transparent
            Color(0xAAFF6B35), // Middle glow
            Color(0xFFFF6B35)  // Inner bright color
        )

        glowColors.forEachIndexed { index, color ->
            val strokeWidth = (12f - index * 2f)
            drawRoundRect(
                color = color,
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
                style = Stroke(width = strokeWidth)
            )
        }

        // Animated dashed border on top
        val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(25f, 10f), 0f)
        drawRoundRect(
            color = androidx.compose.ui.graphics.Color.White,
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
            style = Stroke(
                width = 3f,
                pathEffect = dashPathEffect
            )
        )
    }

    // 2. Enhanced arrow with scale and movement animation
    private fun DrawScope.drawArrow(
        targetCenterX: Int,
        targetCenterY: Int,
        targetTop: Int,
        targetBottom: Int,
        arrowScale: Float, // Scale for the entire arrow
        arrowMovement: Float // Additional movement distance from box
    ) {
        val screenHeight = size.height
        val pointingDown = targetCenterY > screenHeight / 2

        val arrowTipX = targetCenterX.toFloat()

        // Calculate tip position with movement offset
        val baseTipY = if (pointingDown) {
            (targetTop - 50).toFloat() // Base position: 10px above the top
        } else {
            (targetBottom + 50).toFloat() // Base position: 10px below the bottom
        }

        // Apply movement offset - move further away from box when large
        val arrowTipY = if (pointingDown) {
            baseTipY - arrowMovement // Move up when pointing down (further above)
        } else {
            baseTipY + arrowMovement // Move down when pointing up (further below)
        }

        // Base arrow length that will be scaled
        val baseArrowLength = ARROW_SIZE * 2.5f
        val scaledArrowLength = baseArrowLength * arrowScale

        val arrowStartY = if (pointingDown) {
            arrowTipY - scaledArrowLength // Arrow starts above and points down
        } else {
            arrowTipY + scaledArrowLength // Arrow starts below and points up
        }

        val arrowBaseX = arrowTipX
        val arrowBaseY = arrowStartY

        val arrowColor = Color(0xFFFF6B35)
        val arrowGlowColor = Color(0x80FF6B35)

        // Calculate arrow direction and head (scaled)
        val angle = atan2(arrowTipY - arrowBaseY, arrowTipX - arrowBaseX)
        val scaledArrowHeadLength = ARROW_SIZE * 0.8f * arrowScale
        val arrowHeadAngle = Math.PI / 5

        // Calculate where the line should end (at the back of the scaled arrow head)
        val lineEndOffsetDistance = scaledArrowHeadLength * 0.7f
        val lineEndX = arrowTipX - lineEndOffsetDistance * cos(angle)
        val lineEndY = arrowTipY - lineEndOffsetDistance * sin(angle)

        // Scale the stroke width as well
        val scaledStrokeWidth = ARROW_STROKE_WIDTH * arrowScale
        val scaledGlowWidth = (ARROW_STROKE_WIDTH + 8f) * arrowScale

        // Draw arrow shaft with glow effect (scaled)
        // Glow layer
        drawLine(
            color = arrowGlowColor,
            start = Offset(arrowBaseX, arrowBaseY),
            end = Offset(lineEndX.toFloat(), lineEndY.toFloat()),
            strokeWidth = scaledGlowWidth,
            cap = StrokeCap.Round
        )

        // Main arrow shaft
        drawLine(
            color = arrowColor,
            start = Offset(arrowBaseX, arrowBaseY),
            end = Offset(lineEndX.toFloat(), lineEndY.toFloat()),
            strokeWidth = scaledStrokeWidth,
            cap = StrokeCap.Round
        )

        // Create scaled arrow head
        val arrowPath = Path().apply {
            moveTo(arrowTipX, arrowTipY)
            lineTo(
                (arrowTipX - scaledArrowHeadLength * cos(angle - arrowHeadAngle)).toFloat(),
                (arrowTipY - scaledArrowHeadLength * sin(angle - arrowHeadAngle)).toFloat()
            )
            lineTo(
                (arrowTipX - scaledArrowHeadLength * cos(angle + arrowHeadAngle)).toFloat(),
                (arrowTipY - scaledArrowHeadLength * sin(angle + arrowHeadAngle)).toFloat()
            )
            close()
        }

        // Draw arrow head with glow (scaled)
        val scaledGlowStrokeWidth = 6f * arrowScale

        // Arrow head glow
        drawPath(
            path = arrowPath,
            color = arrowGlowColor,
            style = Stroke(width = scaledGlowStrokeWidth)
        )

        // Main arrow head
        drawPath(
            path = arrowPath,
            color = arrowColor,
            style = androidx.compose.ui.graphics.drawscope.Fill
        )
    }

    // 3. Enhanced instruction text with better contrast and effects
    @Composable
    private fun BoxScope.InstructionTextPositioned(
        instructionText: String,
        targetLeft: Int,
        targetTop: Int,
        targetRight: Int,
        targetBottom: Int,
        targetCenterX: Int,
        targetCenterY: Int
    ) {
        val screenHeight = screenHeight.toFloat()
        val screenWidth = screenWidth.toFloat()

        val spaceAbove = targetTop
        val spaceBelow = screenHeight - targetBottom
        val spaceLeft = targetLeft
        val spaceRight = screenWidth - targetRight

        val (alignment, offsetX, offsetY) = when {
            spaceAbove > 120 -> Triple(Alignment.TopCenter, 0, targetTop - 60)
            spaceBelow > 120 -> Triple(Alignment.TopCenter, 0, targetBottom + 40)
            spaceLeft > 200 -> Triple(Alignment.CenterStart, targetLeft - 200, targetCenterY)
            spaceRight > 200 -> Triple(Alignment.CenterStart, targetRight + 20, targetCenterY)
            else -> Triple(Alignment.TopCenter, 0, maxOf(100, targetTop - 60))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = offsetX.dp, y = offsetY.dp),
            contentAlignment = alignment
        ) {
            // Shadow/glow effect
            Text(
                text = instructionText,
                modifier = Modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .background(
                        color = Color(0x60000000),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 36.dp, vertical = 18.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3
            )

            // Main text with enhanced styling
            Text(
                text = instructionText,
                modifier = Modifier
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xEE1A1A1A),
                                Color(0xEE000000)
                            )
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 2.dp,
                        color = Color(0xFFFF6B35),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 36.dp, vertical = 18.dp),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                style = TextStyle(
                    shadow = Shadow(
                        color = androidx.compose.ui.graphics.Color.Black,
                        offset = Offset(2f, 2f),
                        blurRadius = 4f
                    )
                )
            )
        }
    }

    /**
     * Schedule automatic dismissal of the guidance overlay
     */
    private fun scheduleOverlayDismissal() {
        dismissalRunnable?.let { dismissalHandler.removeCallbacks(it) }

        dismissalRunnable = Runnable {
            clearCurrentGuidance()
            Timber.d { "Navigation guidance auto-dismissed after timeout" }
        }

        dismissalHandler.postDelayed(dismissalRunnable!!, OVERLAY_DISPLAY_DURATION)
    }

    /**
     * Clear all current guidance overlays and cancel scheduled dismissal
     */
    fun clearCurrentGuidance() {
        try {
            // Remove overlay view
            currentOverlay?.let { overlay ->
                windowManager.removeView(overlay)
                currentOverlay = null
                Timber.d { "Navigation overlay removed" }
            }

            // Remove instruction view (now part of overlay, but keeping for compatibility)
            currentInstructionView?.let { instruction ->
                windowManager.removeView(instruction)
                currentInstructionView = null
                Timber.d { "Instruction text removed" }
            }

            // Cancel scheduled dismissal
            dismissalRunnable?.let { runnable ->
                dismissalHandler.removeCallbacks(runnable)
                dismissalRunnable = null
            }

        } catch (e: Exception) {
            Timber.w(e) { "Error clearing navigation guidance: ${e.message}" }
        }
    }

    /**
     * Check if guidance is currently being displayed
     */
    fun isGuidanceActive(): Boolean {
        return currentOverlay != null || currentInstructionView != null
    }

    /**
     * Manually dismiss current guidance
     */
    fun dismissGuidance() {
        clearCurrentGuidance()
        Timber.i { "Navigation guidance manually dismissed" }
    }

    /**
     * Clean up resources when the handler is destroyed
     */
    fun destroy() {
        clearCurrentGuidance()
        lifecycleOwner.destroy()
    }
}