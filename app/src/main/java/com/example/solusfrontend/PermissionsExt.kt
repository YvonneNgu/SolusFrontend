package com.example.solusfrontend

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.solusfrontend.utils.PreferencesManager

/**
 * PERMISSION HANDLER - Think of this as the "bouncer" at the door
 *
 * Before your app can record audio (listen to the user's voice), Android requires
 * explicit permission from the user. This is like asking "Can I use your microphone?"
 *
 * This function handles the entire permission flow:
 * 1. Check if we already have permission
 * 2. If not, check if we've already asked before (to avoid repeated prompts)
 * 3. If we haven't asked before, ask the user for permission
 * 4. Handle their response (yes/no)
 * 5. Notify the app when we're good to go
 * 
 * Note: For better user experience, we only ask for permission once and remember
 * the user's choice to avoid annoying them with repeated prompts.
 */
fun ComponentActivity.requireNeededPermissions(
    onPermissionsGranted: (() -> Unit)? = null,
    onPermissionsDenied: (() -> Unit)? = null,
    forceRequest: Boolean = false // Set to true to force permission request regardless of history
) {
    // Get our preferences manager to track permission request history
    val prefsManager = PreferencesManager.getInstance(this)

    // SET UP THE PERMISSION REQUEST LAUNCHER
    // This is like preparing a formal request letter to the user
    // The system will show a popup asking for permission, and we'll get the result here
    val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions() // Can ask for multiple permissions at once
        ) { grants ->
            // HANDLE THE USER'S RESPONSE
            // 'grants' is a map of [permission -> true/false] showing what the user decided

            // Mark that we've asked for audio permission (regardless of outcome)
            if (grants.containsKey(Manifest.permission.RECORD_AUDIO)) {
                prefsManager.setAudioPermissionRequested(true)
            }

            // Check if any permissions were denied
            var anyDenied = false
            for (grant in grants.entries) {
                if (!grant.value) { // If permission was denied
                    anyDenied = true
                    // Show a toast message explaining what permission was missing
                    // This helps the user understand why the app might not work properly
                    Toast.makeText(
                        this,
                        "Missing permission: ${grant.key}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            // SUCCESS CASE: All permissions were granted
            if (grants.all { it.value }) {
                onPermissionsGranted?.invoke() // Tell the app "All good! You can proceed now"
            } else if (anyDenied) {
                // If permissions were denied, make sure service is not enabled
                // This prevents the app from trying to start a service that can't work
                prefsManager.setServiceEnabled(false)
                onPermissionsDenied?.invoke() // Tell the app "User said no"
            }
        }

    // CHECK WHAT PERMISSIONS WE ACTUALLY NEED TO REQUEST
    // Start with all the permissions we need for this app
    val neededPermissions = listOf(Manifest.permission.RECORD_AUDIO)
        .filter {
            // Only keep the ones we don't already have
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
        }
        .toTypedArray() // Convert to array format that the launcher expects

    // DECIDE WHAT TO DO NEXT
    if (neededPermissions.isNotEmpty()) {
        // We're missing some permissions
        
        // Check if we should show the permission request
        // We only show it if:
        // 1. We're forcing a request, OR
        // 2. We haven't asked before (to avoid annoying the user with repeated prompts)
        val shouldAskForPermission = forceRequest || !prefsManager.hasAudioPermissionBeenRequested()
        
        if (shouldAskForPermission) {
            // Time to ask the user - this will show the system permission dialog
            requestPermissionLauncher.launch(neededPermissions)
        } else {
            // We've already asked before and user denied, so don't ask again
            // Make sure service is not enabled since permissions are missing
            prefsManager.setServiceEnabled(false)
            onPermissionsDenied?.invoke()
        }
    } else {
        // We already have all the permissions we need - proceed immediately
        onPermissionsGranted?.invoke()
    }
}
