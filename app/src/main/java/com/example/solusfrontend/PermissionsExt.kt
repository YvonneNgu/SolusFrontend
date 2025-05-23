package com.example.solusfrontend

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * PERMISSION HANDLER - Think of this as the "bouncer" at the door
 *
 * Before your app can record audio (listen to the user's voice), Android requires
 * explicit permission from the user. This is like asking "Can I use your microphone?"
 *
 * This function handles the entire permission flow:
 * 1. Check if we already have permission
 * 2. If not, ask the user for permission
 * 3. Handle their response (yes/no)
 * 4. Notify the app when we're good to go
 */
fun ComponentActivity.requireNeededPermissions(onPermissionsGranted: (() -> Unit)? = null) {

    // SET UP THE PERMISSION REQUEST LAUNCHER
    // This is like preparing a formal request letter to the user
    // The system will show a popup asking for permission, and we'll get the result here
    val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions() // Can ask for multiple permissions at once
        ) { grants ->
            // HANDLE THE USER'S RESPONSE
            // 'grants' is a map of [permission -> true/false] showing what the user decided

            // Check if any permissions were denied
            for (grant in grants.entries) {
                if (!grant.value) { // If permission was denied
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
            // Only proceed if we have ALL the permissions we need AND there's a callback to notify
            if (onPermissionsGranted != null && grants.all { it.value }) {
                onPermissionsGranted() // Tell the app "All good! You can proceed now"
            }
        }

    // CHECK WHAT PERMISSIONS WE ACTUALLY NEED TO REQUEST
    // Start with all the permissions we need for this app
    val neededPermissions = listOf(Manifest.permission.RECORD_AUDIO)
        .filter {
            // Only keep the ones we don't already have
            // It's like checking "Do I already have this key?" before asking for it
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
        }
        .toTypedArray() // Convert to array format that the launcher expects

    // DECIDE WHAT TO DO NEXT
    if (neededPermissions.isNotEmpty()) {
        // We're missing some permissions - time to ask the user
        // This will show the system permission dialog
        requestPermissionLauncher.launch(neededPermissions)
    } else {
        // We already have all the permissions we need - proceed immediately
        // This is the "fast path" for users who already granted permission before
        onPermissionsGranted?.invoke()
    }
}

/**
 * SIMPLIFIED EXPLANATION:
 *
 * This function is like a polite doorman:
 * 1. "Do you already have a membership card?" (Check existing permissions)
 * 2. If yes: "Welcome in!" (Call onPermissionsGranted immediately)
 * 3. If no: "Can I see some ID please?" (Request permissions)
 * 4. User shows ID or refuses
 * 5. If they show ID: "Thank you, welcome in!" (Call onPermissionsGranted)
 * 6. If they refuse: "Sorry, you can't enter without ID" (Show error toast)
 *
 * This ensures the app only works when it has the necessary permissions,
 * and provides clear feedback to the user about what's happening.
 */