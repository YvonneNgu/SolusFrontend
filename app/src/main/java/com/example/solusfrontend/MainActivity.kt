@file:OptIn(Beta::class)

package com.example.solusfrontend

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Main Activity - Think of this as the starting point of your voice assistant app
 * This is like the "front door" of your app where everything begins
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Turn on detailed logging so we can see what's happening behind the scenes
        // This is like turning on a flashlight to see what's going on in the dark
        LiveKit.loggingLevel = LoggingLevel.DEBUG

        // First, we need to ask for microphone permission (can't record voice without it!)
        // Then we need to get a token (like a key card) to connect to LiveKit servers
        requireNeededPermissions {
            requireToken { url, token ->
                // Once we have permissions and token, we can start building our UI
                setContent {
                    // Apply our app's theme (colors, fonts, etc.)
                    LiveKitVoiceAssistantExampleTheme {
                        // Create the main voice assistant interface
                        VoiceAssistant(
                            url,    // Server address to connect to
                            token,  // Authentication token
                            modifier = Modifier.fillMaxSize() // Make it fill the whole screen
                        )
                    }
                }
            }
        }
    }

    /**
     * This is the main UI component - like the blueprint of your voice assistant screen
     * It has two main parts:
     * 1. A visual bar that shows when the AI is speaking (top 10% of screen)
     * 2. A chat log showing the conversation (bottom 90% of screen)
     */
    @Composable
    fun VoiceAssistant(url: String, token: String, modifier: Modifier = Modifier) {
        // ConstraintLayout helps us position elements precisely on screen
        // Think of it like a grid where you can place things exactly where you want
        ConstraintLayout(modifier = modifier) {
            // Set up LiveKit room options
            val connectOptions = remember {
                ConnectOptions(autoSubscribe = true)
            }

            val roomOptions = remember {
                RoomOptions(adaptiveStream = true, dynacast = true)
            }

            // RoomScope is like creating a "room" where you and the AI can talk
            // It handles all the complex networking stuff behind the scenes
            RoomScope(
                url,           // Where to connect
                token,         // Your access pass
                audio = true,  // We want audio (voice)
                video = false,
                connect = true, // Start connecting immediately
                connectOptions = connectOptions,
                roomOptions = roomOptions,
            ) { room ->

                // Create references for positioning our UI elements
                // Think of these as anchors we can attach our components to
                val (audioVisualizer, chatLog) = createRefs()

                // Get access to the voice assistant functionality
                // This is like getting a remote control for the AI
                val voiceAssistant = rememberVoiceAssistant()

                // Monitor the AI's state (thinking, listening, speaking, etc.)
                val agentState = voiceAssistant.state

                // Whenever the AI's state changes, log it so we can see what's happening
                // This is useful for debugging - like having a status indicator
                LaunchedEffect(key1 = agentState) {
                    Timber.i { "agent state: $agentState" }
                }

                // VISUAL COMPONENT 1: Audio Visualizer
                // This shows animated bars when the AI is speaking
                // Like the equalizer you see on music players
                VoiceAssistantBarVisualizer(
                    voiceAssistant = voiceAssistant,
                    modifier = Modifier
                        .padding(8.dp)              // Add some space around it
                        .fillMaxWidth()             // Stretch across the screen
                        .constrainAs(audioVisualizer) {  // Position it using constraints
                            height = Dimension.percent(0.1f)  // Take up 10% of screen height
                            width = Dimension.percent(0.8f)   // Take up 80% of screen width

                            // Stick it to the top center of the screen
                            top.linkTo(parent.top)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                        }
                )

                // VISUAL COMPONENT 2: Chat History
                // Get all the conversation transcripts (what was said by everyone)
                val segments = rememberTranscriptions()           // All conversations
                val localSegments = rememberParticipantTranscriptions(room.localParticipant) // Just user's words

                // Remember the scroll position so we can auto-scroll to new messages
                val lazyListState = rememberLazyListState()

                // LazyColumn is like a scrollable list that only loads visible items
                // Perfect for chat logs that could get very long
                LazyColumn(
                    userScrollEnabled = true,  // User can scroll manually
                    state = lazyListState,     // Remember scroll position
                    modifier = Modifier
                        .constrainAs(chatLog) {    // Position using constraints
                            bottom.linkTo(parent.bottom)      // Stick to bottom
                            start.linkTo(parent.start)        // Align to left
                            end.linkTo(parent.end)            // Align to right
                            height = Dimension.percent(0.9f)  // Take up 90% of screen
                            width = Dimension.fillToConstraints // Fill available width
                        }
                ) {
                    // For each conversation segment (piece of dialogue)
                    items(
                        items = segments,              // The list of conversation pieces
                        key = { segment -> segment.id } // Unique identifier for each piece
                    ) { segment ->
                        // Container for each message bubble
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()    // Stretch across available width
                                .padding(8.dp)     // Add space around each message
                        ) {
                            // Check if this message came from the user or the AI
                            if (localSegments.contains(segment)) {
                                // USER MESSAGE: Show on the right side with special styling
                                UserTranscription(
                                    segment = segment,
                                    modifier = Modifier.align(Alignment.CenterEnd) // Right-aligned
                                )
                            } else {
                                // AI MESSAGE: Show on the left side as plain text
                                Text(
                                    text = segment.text,
                                    modifier = Modifier.align(Alignment.CenterStart) // Left-aligned
                                )
                            }
                        }
                    }
                }

                // AUTO-SCROLL FEATURE
                // Whenever new messages come in, automatically scroll to the bottom
                // Like how messaging apps always show the latest message
                LaunchedEffect(segments) {
                    // Scroll to the last item (newest message)
                    // The coerceAtLeast(0) ensures we don't try to scroll to negative positions
                    lazyListState.scrollToItem((segments.size - 1).coerceAtLeast(0))
                }
            }
        }
    }
}