package com.example.solusfrontend.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.livekit.android.room.types.TranscriptionSegment

/**
 * USER MESSAGE BUBBLE - Think of this as a fancy speech bubble for user messages
 *
 * This creates a visual representation of what the user said, similar to:
 * - iMessage bubbles (blue on the right)
 * - WhatsApp messages (green on the right for sent messages)
 * - Any chat app where your messages appear differently from others
 *
 * Key features:
 * 1. Styled like a message bubble with rounded corners
 * 2. Appears with a fade-in animation (smooth entrance)
 * 3. Distinguished from AI messages by background color and positioning
 */
@Composable
fun UserTranscription(
    segment: TranscriptionSegment, // The actual text that was spoken and transcribed
    modifier: Modifier = Modifier  // Allows parent to customize positioning/styling
) {

    // ANIMATION SETUP
    // Create an animation state that controls when this message appears
    // Think of this as a "director" that controls the entrance of an actor on stage
    val state = remember {
        MutableTransitionState(false).apply {
            // Start the animation immediately when this composable is created
            // Like saying "Action!" as soon as the actor steps on stage
            targetState = true
        }
    }

    // ANIMATED CONTAINER
    // This wraps our message bubble with fade-in animation
    // The message will smoothly appear instead of just popping into existence
    AnimatedVisibility(
        visibleState = state,           // Use our animation controller
        enter = fadeIn(),               // Fade in smoothly when appearing
        modifier = modifier             // Apply any positioning from parent
    ) {
        // MESSAGE BUBBLE CONTAINER
        // This creates the actual "bubble" appearance
        Box(
            modifier = modifier
                // SHAPE: Create rounded corners like a modern message bubble
                // The asymmetric corners (8,2,8,8) create a "speech bubble" effect
                // - Top-left: 8dp radius (rounded)
                // - Top-right: 2dp radius (slightly pointed - like bubble tail)
                // - Bottom-right: 8dp radius (rounded)
                // - Bottom-left: 8dp radius (rounded)
                .clip(RoundedCornerShape(8.dp, 2.dp, 8.dp, 8.dp))

                // BACKGROUND: Light gray to distinguish from AI messages
                // In a real app, you might use your brand colors
                .background(Color.LightGray)
        ) {
            // MESSAGE TEXT
            // The actual transcribed text that the user spoke
            Text(
                text = segment.text,              // What the user said
                fontWeight = FontWeight.Medium,   // Make it slightly bold for emphasis
                modifier = Modifier.padding(8.dp) // Add inner padding so text doesn't touch edges
            )
        }
    }
}