@file:OptIn(Beta::class)

package com.example.solusfrontend
//Some import
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.ajalt.timberkt.Timber
import io.livekit.android.LiveKit
import io.livekit.android.annotations.Beta
import com.example.solusfrontend.ui.theme.LiveKitVoiceAssistantExampleTheme
import io.livekit.android.util.LoggingLevel

/**
 * Enhanced Main Activity - Now manages background voice assistant service
 * The main app now focuses on setup and controlling the background service
 * Think of this as the control center for your voice assistant
 */
class MainActivity : ComponentActivity() {

    // Track service state
    private var isServiceRunning by mutableStateOf(false)
    private var hasOverlayPermission by mutableStateOf(false)

    // Store connection details
    private var connectionUrl: String? = null
    private var connectionToken: String? = null

    /**
     * Permission launcher for overlay permission
     * This is needed to show UI on top of other apps
     */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Check if permission was granted
        hasOverlayPermission = canDrawOverlays()
        if (hasOverlayPermission) {
            Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
            // If we have connection details, we can start the service now
            if (connectionUrl != null && connectionToken != null) {
                startBackgroundService()
            }
        } else {
            Toast.makeText(this, "Overlay permission is required for background mode", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable detailed logging
        LiveKit.loggingLevel = LoggingLevel.DEBUG

        // Check initial permissions
        hasOverlayPermission = canDrawOverlays()

        // Get authentication and set up UI
        requireNeededPermissions {
            requireToken { url, token ->
                // Store connection details
                connectionUrl = url
                connectionToken = token

                // Set up the main UI
                setContent {
                    LiveKitVoiceAssistantExampleTheme {
                        MainScreen(
                            hasOverlayPermission = hasOverlayPermission,
                            isServiceRunning = isServiceRunning,
                            onRequestOverlayPermission = { requestOverlayPermission() },
                            onStartBackgroundService = { startBackgroundService() },
                            onStopBackgroundService = { stopBackgroundService() },
                            onOpenSettings = { openAppSettings() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Check if app can draw overlays on other apps
     * Required for showing voice assistant UI system-wide
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true // No permission needed on older Android versions
        }
    }

    /**
     * Request overlay permission from user
     * Shows system settings where user can grant permission
     */
    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)

    }

    /**
     * Start the background voice assistant service
     * This makes the voice assistant available system-wide
     */
    private fun startBackgroundService() {
        if (!hasOverlayPermission) {
            Toast.makeText(this, "Overlay permission required first", Toast.LENGTH_SHORT).show()
            return
        }

        if (connectionUrl == null || connectionToken == null) {
            Toast.makeText(this, "Connection not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Send connection details to service
            val connectionIntent = Intent(this, VoiceAssistantOverlayService::class.java).apply {
                action = VoiceAssistantOverlayService.ACTION_SET_CONNECTION
                putExtra(VoiceAssistantOverlayService.EXTRA_URL, connectionUrl)
                putExtra(VoiceAssistantOverlayService.EXTRA_TOKEN, connectionToken)
            }
            startService(connectionIntent)

            // Start the overlay service
            val serviceIntent = Intent(this, VoiceAssistantOverlayService::class.java).apply {
                action = VoiceAssistantOverlayService.ACTION_START_SERVICE
            }
            startForegroundService(serviceIntent)

            isServiceRunning = true
            Toast.makeText(this, "Voice assistant is now running in background! Say 'Solus' to activate.", Toast.LENGTH_LONG).show()

            Timber.i { "Background voice assistant service started" }
        } catch (e: Exception) {
            Timber.e { "Failed to start background service: $e" }
            Toast.makeText(this, "Failed to start background service", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Stop the background voice assistant service
     * This disables system-wide voice assistant
     */
    private fun stopBackgroundService() {
        val intent = Intent(this, VoiceAssistantOverlayService::class.java).apply {
            action = VoiceAssistantOverlayService.ACTION_STOP_SERVICE
        }
        startService(intent)

        isServiceRunning = false
        Toast.makeText(this, "Background voice assistant stopped", Toast.LENGTH_SHORT).show()

        Timber.i { "Background voice assistant service stopped" }
    }

    /**
     * Open app settings page
     * Useful if user needs to manage permissions manually
     */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    /**
     * Main screen UI - Control center for the voice assistant
     * Shows current status and provides controls for background service
     */
    @Composable
    fun MainScreen(
        hasOverlayPermission: Boolean,
        isServiceRunning: Boolean,
        onRequestOverlayPermission: () -> Unit,
        onStartBackgroundService: () -> Unit,
        onStopBackgroundService: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App title and description
            Text(
                text = "Solus Voice Assistant",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your AI assistant that works everywhere",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Status cards
            StatusCard(
                title = "Overlay Permission",
                status = if (hasOverlayPermission) "Granted" else "Not Granted",
                isGood = hasOverlayPermission,
                description = if (hasOverlayPermission)
                    "Can show voice assistant on top of other apps"
                else
                    "Required to show voice assistant over other apps"
            )

            Spacer(modifier = Modifier.height(16.dp))

            StatusCard(
                title = "Background Service",
                status = if (isServiceRunning) "Running" else "Stopped",
                isGood = isServiceRunning,
                description = if (isServiceRunning)
                    "Voice assistant is listening for 'Solus' in background"
                else
                    "Voice assistant is not active"
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons
            if (!hasOverlayPermission) {
                // Need overlay permission first
                Button(
                    onClick = onRequestOverlayPermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Overlay Permission")
                }
            } else if (!isServiceRunning) {
                // Can start background service
                Button(
                    onClick = onStartBackgroundService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50) // Green
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Background Voice Assistant")
                }
            } else {
                // Service is running - show stop button
                Button(
                    onClick = onStopBackgroundService,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF44336) // Red
                    )
                ) {
                    Text("Stop Background Service")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Instructions when service is running
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "🎤 Voice Assistant Active",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• Say 'Solus' from any app to activate\n" +
                                    "• A chat window will appear at the bottom\n" +
                                    "• Use the mic button to mute/unmute\n" +
                                    "• Tap the X to close and return to listening",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Settings button
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("App Settings")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Footer info
            Text(
                text = "You can minimize this app and the voice assistant will keep running in the background",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }

    /**
     * Status card showing current state of permissions/services
     * Visual indicator of what's working and what needs attention
     */
    @Composable
    fun StatusCard(
        title: String,
        status: String,
        isGood: Boolean,
        description: String
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isGood)
                    Color(0xFF4CAF50).copy(alpha = 0.1f) // Light green
                else
                    Color(0xFFF44336).copy(alpha = 0.1f) // Light red
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isGood) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                // Status badge
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isGood) Color(0xFF4CAF50) else Color(0xFFF44336)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = status,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh permission status when app comes back to foreground
        hasOverlayPermission = canDrawOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Note: We don't stop the background service here because we want it to continue
        // running even when the main app is closed. Users control it through the notification
        // or by reopening the app.
    }
}