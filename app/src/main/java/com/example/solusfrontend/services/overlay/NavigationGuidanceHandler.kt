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
        private const val OVERLAY_DISPLAY_DURATION = 10000L // 10 seconds
        private const val ARROW_SIZE = 60f
        private const val ARROW_STROKE_WIDTH = 8f
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
        // Infinite pulse animation
        val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 800,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )

        var isVisible by remember { mutableStateOf(false) }

        // Trigger entrance animation
        LaunchedEffect(Unit) {
            isVisible = true
        }

        val alpha by animateFloatAsState(
            targetValue = if (isVisible) 1f else 0f,
            animationSpec = tween(
                durationMillis = 300,
                easing = FastOutSlowInEasing
            ),
            label = "instruction_alpha"
        )

        Box(modifier = Modifier.fillMaxSize().alpha(alpha)) {
            // Draw the navigation overlay (arrow and highlight)
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                drawNavigationOverlay(
                    targetCenterX = targetCenterX,
                    targetCenterY = targetCenterY,
                    targetLeft = targetLeft,
                    targetTop = targetTop,
                    targetRight = targetRight,
                    targetBottom = targetBottom,
                    pulseScale = pulseScale
                )
            }

            // Position instruction text dynamically based on bounding box
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
        // Determine optimal position for instruction text based on target location
        val screenHeight = screenHeight.toFloat()
        val screenWidth = screenWidth.toFloat()

        // Calculate if there's enough space above or below the target
        val spaceAbove = targetTop
        val spaceBelow = screenHeight - targetBottom
        val spaceLeft = targetLeft
        val spaceRight = screenWidth - targetRight

        // Preferred positioning: above if there's space, otherwise below, otherwise to the side
        val (alignment, offsetX, offsetY) = when {
            spaceAbove > 120 -> Triple(Alignment.TopCenter, 0, targetTop - 60)
            spaceBelow > 120 -> Triple(Alignment.TopCenter, 0, targetBottom + 40)
            spaceLeft > 200 -> Triple(Alignment.CenterStart, targetLeft - 200, targetCenterY)
            spaceRight > 200 -> Triple(Alignment.CenterStart, targetRight + 20, targetCenterY)
            else -> Triple(Alignment.TopCenter, 0, maxOf(100, targetTop - 60)) // Fallback to top with minimum offset
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = offsetX.dp, y = offsetY.dp),
            contentAlignment = alignment
        ) {
            Text(
                text = instructionText,
                modifier = Modifier
                    .background(
                        color = Color(0xDD000000),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 32.dp, vertical = 16.dp),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                maxLines = 3
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
        pulseScale: Float
    ) {
        // Draw highlight around target element
        drawHighlight(targetLeft, targetTop, targetRight, targetBottom)

        // Draw arrow pointing to target
        drawArrow(targetCenterX, targetCenterY, targetTop, targetBottom, pulseScale)
    }

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

        val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), 0f)

        drawRoundRect(
            color = Color(0x44FF4444),
            topLeft = rect.topLeft,
            size = rect.size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
            style = Stroke(
                width = 6f,
                pathEffect = dashPathEffect
            )
        )
    }

    private fun DrawScope.drawArrow(
        targetCenterX: Int,
        targetCenterY: Int,
        targetTop: Int,
        targetBottom: Int,
        pulseScale: Float
    ) {
        val screenHeight = size.height
        val arrowStartY = if (targetCenterY > screenHeight / 2) {
            // Target is in bottom half, arrow points down from above
            targetTop - ARROW_SIZE * 2
        } else {
            // Target is in top half, arrow points up from below
            targetBottom + ARROW_SIZE * 2
        }

        val arrowTipX = targetCenterX.toFloat()
        val arrowTipY = targetCenterY.toFloat()
        val arrowBaseX = arrowTipX
        val arrowBaseY = arrowStartY

        val arrowColor = Color(0xFFFF4444)

        // Draw arrow shaft
        drawLine(
            color = arrowColor,
            start = Offset(arrowBaseX, arrowBaseY),
            end = Offset(arrowTipX, arrowTipY),
            strokeWidth = ARROW_STROKE_WIDTH,
            cap = StrokeCap.Round
        )

        // Calculate arrow direction and head
        val angle = atan2(arrowTipY - arrowBaseY, arrowTipX - arrowBaseX)
        val arrowHeadLength = ARROW_SIZE * 0.6f
        val arrowHeadAngle = Math.PI / 6 // 30 degrees

        val arrowPath = Path().apply {
            moveTo(arrowTipX, arrowTipY)
            lineTo(
                (arrowTipX - arrowHeadLength * cos(angle - arrowHeadAngle)).toFloat(),
                (arrowTipY - arrowHeadLength * sin(angle - arrowHeadAngle)).toFloat()
            )
            lineTo(
                (arrowTipX - arrowHeadLength * cos(angle + arrowHeadAngle)).toFloat(),
                (arrowTipY - arrowHeadLength * sin(angle + arrowHeadAngle)).toFloat()
            )
            close()
        }

        // Apply pulse scale around the arrow tip point and draw arrow head
        drawIntoCanvas { canvas ->
            canvas.save()
            // Translate to pivot point
            canvas.translate(arrowTipX, arrowTipY)
            // Scale around origin (which is now the pivot point)
            canvas.scale(pulseScale, pulseScale)
            // Translate back
            canvas.translate(-arrowTipX, -arrowTipY)

            drawPath(
                path = arrowPath,
                color = arrowColor,
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
            canvas.restore()
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