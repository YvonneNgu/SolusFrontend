@file:OptIn(Beta::class)

package com.example.solusfrontend

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.github.ajalt.timberkt.Timber
import io.livekit.android.LiveKit
import io.livekit.android.annotations.Beta
import io.livekit.android.compose.local.RoomScope
import io.livekit.android.compose.state.rememberVoiceAssistant
import io.livekit.android.compose.state.transcriptions.rememberParticipantTranscriptions
import io.livekit.android.compose.state.transcriptions.rememberTranscriptions
import io.livekit.android.compose.ui.audio.VoiceAssistantBarVisualizer
import com.example.solusfrontend.ui.UserTranscription
import com.example.solusfrontend.ui.theme.LiveKitVoiceAssistantExampleTheme
import io.livekit.android.ConnectOptions
import io.livekit.android.RoomOptions
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.delay

/**
 * Main Activity - Enhanced with Wake Word Detection
 * Now the voice assistant only starts when the wake word "Solus" is detected
 * Think of this like having a doorman who only lets you in when you say the magic word
 */
class MainActivity : ComponentActivity(), WakeWordDetector.WakeWordCallback {

    // Our wake word detector - like a security guard listening for "Solus"
    private lateinit var wakeWordDetector: WakeWordDetector

    // State to track whether the voice assistant should be active
    // Think of this as a light switch - on when wake word detected, off otherwise
    private var isVoiceAssistantActive by mutableStateOf(false)

    // Store the connection details so we can use them when wake word is detected
    private var connectionUrl: String? = null
    private var connectionToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Turn on detailed logging so we can see what's happening behind the scenes
        LiveKit.loggingLevel = LoggingLevel.DEBUG

        // Initialize our wake word detector and tell it to call us back when "Solus" is heard
        // It's like setting up a doorbell that rings when someone says the magic word
        wakeWordDetector = WakeWordDetector(this, this)
        wakeWordDetector.initialize()

        // Ask for microphone permission and get authentication token
        // But don't start the voice assistant yet - wait for wake word!
        requireNeededPermissions {
            requireToken { url, token ->
                // Store these for later use when wake word is detected
                connectionUrl = url
                connectionToken = token

                // Start listening for the wake word immediately
                // Like having a guard dog that's always alert
                wakeWordDetector.startListening()

                // Set up our UI - but voice assistant starts as inactive
                setContent {
                    LiveKitVoiceAssistantExampleTheme {
                        // Show different UI based on whether wake word was detected
                        if (isVoiceAssistantActive) {
                            // Wake word detected! Show the full voice assistant
                            VoiceAssistant(
                                url = url,
                                token = token,
                                onSessionEnd = {
                                    // When conversation ends, go back to listening for wake word
                                    endVoiceSession()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Still waiting for wake word - show waiting screen
                            WaitingForWakeWordScreen(
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * This function gets called automatically when our WakeWordDetector hears "Solus"
     * It's like a doorbell ringing - this is what happens when someone rings it
     */
    override fun onWakeWordDetected() {
        // Log that we heard the wake word
        Timber.i { "Wake word 'Solus' detected! Activating voice assistant..." }

        // Stop listening for wake word temporarily (we're about to start conversation)
        // Like closing the door after someone comes in
        wakeWordDetector.stopListening()

        // Activate the voice assistant UI
        // This triggers a recomposition and shows the full voice assistant
        isVoiceAssistantActive = true

        // Optional: Show a quick toast to let user know we heard them
        Toast.makeText(this, "Hello! I heard you say Solus. How can I help?", Toast.LENGTH_SHORT).show()
    }

    /**
     * Call this when the voice conversation is finished
     * It's like saying goodbye and going back to waiting mode
     */
    private fun endVoiceSession() {
        Timber.i { "Voice session ended. Going back to wake word detection..." }

        // Hide the voice assistant UI
        isVoiceAssistantActive = false

        // Start listening for wake word again
        // Like the guard going back to their post
        wakeWordDetector.startListening()

        Toast.makeText(this, "Say 'Solus' to start another conversation", Toast.LENGTH_SHORT).show()
    }

    /**
     * Simple screen shown while waiting for the wake word
     * Like a welcome mat that says "say the magic word"
     */
    @Composable
    fun WaitingForWakeWordScreen(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Pulsing animation to show we're listening
                // Like a heartbeat to show the system is alive
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "alpha"
                )

                // Microphone icon that pulses
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Listening for wake word",
                    tint = Color.White.copy(alpha = alpha),
                    modifier = Modifier.size(64.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Instructions for the user
                Text(
                    text = "Say \"Solus\" to activate",
                    color = Color.White.copy(alpha = alpha),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Listening...",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    /**
     * Enhanced Voice Assistant UI with session management
     * Now includes the ability to end the session and return to wake word detection
     */
    @Composable
    fun VoiceAssistant(
        url: String,
        token: String,
        onSessionEnd: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        // Keep track of conversation activity
        // If there's no activity for a while, we might want to auto-end the session
        var lastActivityTime by remember { mutableStateOf(System.currentTimeMillis()) }

        ConstraintLayout(modifier = modifier) {
            val (topBar, audioVisualizer, chatLog, endButton) = createRefs()

            // Connection settings (same as before)
            val connectOptions = remember {
                ConnectOptions(autoSubscribe = true)
            }

            val roomOptions = remember {
                RoomOptions(adaptiveStream = true, dynacast = true)
            }

            // Top bar showing we're in active session
            Box(
                modifier = Modifier
                    .constrainAs(topBar) {
                        top.linkTo(parent.top)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        height = Dimension.wrapContent
                    }
                    .background(Color.Green.copy(alpha = 0.2f))
                    .padding(8.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "🟢 Voice Assistant Active",
                    color = Color.Green,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // RoomScope handles all the LiveKit connection magic
            RoomScope(
                url = url,
                token = token,
                audio = true,
                video = false,
                connect = true,
                connectOptions = connectOptions,
                roomOptions = roomOptions,
            ) { room ->

                val voiceAssistant = rememberVoiceAssistant()
                val agentState = voiceAssistant.state

                // Update activity time when agent state changes
                // This helps us know the conversation is still active
                LaunchedEffect(key1 = agentState) {
                    Timber.i { "agent state: $agentState" }
                    lastActivityTime = System.currentTimeMillis()
                }

                // Auto-end session after period of inactivity (optional)
                // Like a phone call that hangs up if nobody talks
                LaunchedEffect(key1 = lastActivityTime) {
                    delay(30000) // Wait 30 seconds
                    val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                    if (timeSinceLastActivity >= 30000) { // 30 seconds of inactivity
                        Timber.i { "Auto-ending session due to inactivity" }
                        onSessionEnd()
                    }
                }

                // Audio visualizer (same as before but positioned lower due to top bar)
                VoiceAssistantBarVisualizer(
                    voiceAssistant = voiceAssistant,
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth()
                        .constrainAs(audioVisualizer) {
                            height = Dimension.percent(0.1f)
                            width = Dimension.percent(0.8f)
                            top.linkTo(topBar.bottom, margin = 8.dp)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                        }
                )

                // Chat log (same as before but adjusted for new layout)
                val segments = rememberTranscriptions()
                val localSegments = rememberParticipantTranscriptions(room.localParticipant)
                val lazyListState = rememberLazyListState()

                LazyColumn(
                    userScrollEnabled = true,
                    state = lazyListState,
                    modifier = Modifier
                        .constrainAs(chatLog) {
                            top.linkTo(audioVisualizer.bottom, margin = 8.dp)
                            bottom.linkTo(endButton.top, margin = 8.dp)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            height = Dimension.fillToConstraints
                            width = Dimension.fillToConstraints
                        }
                ) {
                    items(
                        items = segments,
                        key = { segment -> segment.id }
                    ) { segment ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        ) {
                            if (localSegments.contains(segment)) {
                                UserTranscription(
                                    segment = segment,
                                    modifier = Modifier.align(Alignment.CenterEnd)
                                )
                            } else {
                                Text(
                                    text = segment.text,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                )
                            }
                        }
                    }
                }

                // Auto-scroll to latest message
                LaunchedEffect(segments) {
                    if (segments.isNotEmpty()) {
                        lazyListState.scrollToItem((segments.size - 1).coerceAtLeast(0))
                        lastActivityTime = System.currentTimeMillis() // Update activity time
                    }
                }

                // End session button
                Button(
                    onClick = onSessionEnd,
                    modifier = Modifier
                        .constrainAs(endButton) {
                            bottom.linkTo(parent.bottom, margin = 16.dp)
                            start.linkTo(parent.start, margin = 16.dp)
                            end.linkTo(parent.end, margin = 16.dp)
                        }
                        .fillMaxWidth()
                ) {
                    Text("End Conversation")
                }
            }
        }
    }

    /**
     * Clean up resources when the app is destroyed
     * Like turning off all the lights when leaving the house
     */
    override fun onDestroy() {
        super.onDestroy()
        // Stop listening and free up the wake word detector resources
        wakeWordDetector.stopListening()
        wakeWordDetector.release()
    }

    /**
     * Handle what happens when app goes to background/foreground
     * We want to pause wake word detection when app is not visible to save battery
     */
    override fun onPause() {
        super.onPause()
        if (!isVoiceAssistantActive) {
            // Only stop if we're not in an active voice session
            wakeWordDetector.stopListening()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isVoiceAssistantActive) {
            // Resume wake word detection when app comes back
            wakeWordDetector.startListening()
        }
    }
}

/**
 * Enhanced User Transcription component
 * Shows user messages in a nice bubble format
 */
@Composable
fun UserTranscription(segment: Any, modifier: Modifier = Modifier) {
    // You'll need to implement this based on your segment structure
    // This is just a placeholder showing the concept
    Box(
        modifier = modifier
            .background(
                Color.Blue.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = segment.toString(), // Replace with actual segment.text
            color = Color.Blue
        )
    }
}