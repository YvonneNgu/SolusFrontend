package com.example.solusfrontend

import android.widget.Toast
import androidx.activity.ComponentActivity
import com.github.ajalt.timberkt.Timber
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

fun ComponentActivity.requireToken(
    userName: String? = null,   // Optional: Display name
    onTokenGenerated: (url: String, token: String) -> Unit
) {
    val identity = "user123"  // Replace with dynamic user ID if needed
    val activity = this

    val tokenServerUrl = BuildConfig.TOKEN_SERVER_URL
    val livekitUrl = BuildConfig.LIVEKIT_URL

    val client = OkHttpClient()

    // Build GET URL with query parameter
    val request = Request.Builder()
        .url("${tokenServerUrl}api/get-token?participant=$identity")
        .get()
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            runOnUiThread {
                Toast.makeText(
                    activity,
                    "Failed to connect to token server. Check if backend is running.",
                    Toast.LENGTH_LONG
                ).show()
                Timber.e { "Token request failed: ${e.message}" }
            }
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.let { responseBody ->
                if (response.isSuccessful) {
                    try {
                        val json = responseBody.string()
                        val jsonObject = JSONObject(json)
                        val token = jsonObject.getString("token")

                        runOnUiThread {
                            onTokenGenerated(livekitUrl, token)
                            Timber.i { "Successfully got token for participant: $identity" }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(
                                activity,
                                "Invalid server response. Check backend JSON.",
                                Toast.LENGTH_LONG
                            ).show()
                            Timber.e { "Failed to parse token response: ${e.message}" }
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            activity,
                            "Token generation failed: HTTP ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                        Timber.e { "Token server responded with status: ${response.code}" }
                    }
                }
            }
        }
    })
}

/**
 * TOKEN GENERATOR - Gets access token from YOUR Python backend server
 *
 * Instead of using LiveKit's sandbox, this connects to your own secure backend
 * that generates tokens with proper permissions for your users.
 *
 * Your backend controls who can join which rooms and with what permissions.
 */
/*
fun ComponentActivity.requireToken(
    userName: String? = null,   // Optional: Display name for the user
    metadata: String = "",      // Optional: Any extra data about the user
    onTokenGenerated: (url: String, token: String) -> Unit
) {
    // Replace with real data in future !!!!!!!!!!
    val identity = "user123"                                // User identity / name
    val roomName = "user123_${System.currentTimeMillis()}"

    val activity = this

    // GET YOUR BACKEND URL from build config (configured in build.gradle.kts)
    // This points to your Python Flask server running the token generation
    val tokenServerUrl = BuildConfig.TOKEN_SERVER_URL
    val livekitUrl = BuildConfig.LIVEKIT_URL

    // CREATE HTTP CLIENT for making network requests
    val client = OkHttpClient()

    // PREPARE THE REQUEST BODY
    // This is the JSON data your Python backend expects
    val requestBodyJson = JSONObject().apply {
        put("identity", identity)     // Required: User identifier
        put("room", roomName)         // Required: Room name to join
        put("name", userName)         // Optional: Display name
        put("metadata", metadata)     // Optional: Extra user data
    }

    // CREATE THE HTTP REQUEST
    // We're making a POST request to your /token endpoint
    val requestBody = requestBodyJson.toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("${tokenServerUrl}token")  // Your backend's token endpoint
        .post(requestBody)              // Send the user data as JSON
        .header("Content-Type", "application/json")
        .build()

    // MAKE THE NETWORK REQUEST (ASYNCHRONOUSLY)
    // This happens in background so UI doesn't freeze while waiting for server
    client.newCall(request).enqueue(object : Callback {

        // HANDLE NETWORK FAILURES
        // This runs if internet is down, server unreachable, wrong URL, etc.
        override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace() // Log error for debugging

            // Show user-friendly error message on main UI thread
            runOnUiThread {
                Toast.makeText(
                    activity,
                    "Failed to connect to token server. Check if backend is running.",
                    Toast.LENGTH_LONG
                ).show()

                // Log more details for debugging
                Timber.e { "Token request failed: ${e.message}" }
                Timber.e { "Attempted URL: ${tokenServerUrl}token" }
            }
        }

        // HANDLE SERVER RESPONSE
        // This runs when server responds (could still be an error response)
        override fun onResponse(call: Call, response: Response) {
            response.body?.let { responseBody ->
                if (response.isSuccessful) {
                    // SUCCESS PATH: Server gave us a token

                    try {
                        // PARSE THE JSON RESPONSE from your Python backend
                        // Your backend returns: {"token": "eyJ0eXAiOiJKV1QiLCJhbGc..."}
                        val json = responseBody.string()
                        val jsonObject = JSONObject(json)
                        val token = jsonObject.getString("token")

                        // SUCCESS! We got our token from your backend
                        // Switch back to main UI thread to proceed with connection
                        runOnUiThread {
                            // Give the LiveKit URL and token to whoever requested them
                            onTokenGenerated(livekitUrl, token)

                            // Log success for debugging
                            Timber.i { "Successfully got token for user: $identity in room: $roomName" }
                        }

                    } catch (e: Exception) {
                        // ERROR PARSING: Server responded but JSON was malformed
                        runOnUiThread {
                            Toast.makeText(
                                activity,
                                "Server response was invalid. Check backend logs.",
                                Toast.LENGTH_LONG
                            ).show()

                            Timber.e { "Failed to parse token response: ${e.message}" }
                        }
                    }

                } else {
                    // ERROR PATH: Server responded but with an error status
                    // This happens when your backend returns 400, 500, etc.

                    try {
                        // Try to get error message from your backend
                        val errorJson = responseBody.string()
                        val errorObject = JSONObject(errorJson)
                        val errorMessage = errorObject.getString("error")

                        runOnUiThread {
                            Toast.makeText(
                                activity,
                                "Token generation failed: $errorMessage",
                                Toast.LENGTH_LONG
                            ).show()

                            Timber.e { "Backend error: $errorMessage (Status: ${response.code})" }
                        }

                    } catch (e: Exception) {
                        // Couldn't parse error message, show generic error
                        runOnUiThread {
                            Toast.makeText(
                                activity,
                                "Server error (${response.code}). Check backend logs.",
                                Toast.LENGTH_LONG
                            ).show()

                            Timber.e { "HTTP error: ${response.code}, couldn't parse error message" }
                        }
                    }
                }
            }
        }
    })
}*/

/**
 * SIMPLIFIED EXPLANATION:
 *
 * This function is like calling a hotel to get your reservation details:
 *
 * 1. "Hi, I'd like my room details please. My reservation ID is [sandboxID]"
 * 2. Hotel: "Let me check... yes, your room is at address [serverUrl] and here's your key card [token]"
 * 3. You: "Great! Now I can go to my room"
 *
 * If something goes wrong:
 * - No internet: "Sorry, can't reach the hotel right now"
 * - Wrong reservation ID: "Sorry, we can't find your reservation"
 * - Hotel system down: "Sorry, our system is having issues"
 *
 * The key points:
 * - This happens in the background (asynchronously) so the app doesn't freeze
 * - We handle both success and failure cases gracefully
 * - We give user-friendly error messages instead of crashing
 * - In production, you'd have your own secure server instead of using LiveKit's sandbox
 */

/**
 * Service version of requireToken - for use in non-Activity contexts
 * 
 * This version allows services like VoiceAssistantOverlayService to get tokens
 * without needing to be a ComponentActivity.
 */
fun android.app.Service.requireToken(
    userName: String? = null,   // Optional: Display name
    onTokenGenerated: (url: String, token: String) -> Unit
) {
    val identity = userName ?: "user123"  // Use provided name or default
    val service = this

    val tokenServerUrl = BuildConfig.TOKEN_SERVER_URL
    val livekitUrl = BuildConfig.LIVEKIT_URL

    val client = OkHttpClient()

    // Build GET URL with query parameter
    val request = Request.Builder()
        .url("${tokenServerUrl}api/get-token?participant=$identity")
        .get()
        .build()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // Cannot use runOnUiThread in a Service
            Timber.e { "Token request failed: ${e.message}" }
            
            // Show error toast on main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(
                    service,
                    "Failed to connect to token server. Check if backend is running.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        override fun onResponse(call: Call, response: Response) {
            response.body?.let { responseBody ->
                if (response.isSuccessful) {
                    try {
                        val json = responseBody.string()
                        val jsonObject = JSONObject(json)
                        val token = jsonObject.getString("token")

                        // Use handler to post to main thread
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onTokenGenerated(livekitUrl, token)
                            Timber.i { "Successfully got token for participant: $identity" }
                        }
                    } catch (e: Exception) {
                        Timber.e { "Failed to parse token response: ${e.message}" }
                        
                        // Show error toast on main thread
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(
                                service,
                                "Invalid server response. Check backend JSON.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    Timber.e { "Token server responded with status: ${response.code}" }
                    
                    // Show error toast on main thread
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(
                            service,
                            "Token generation failed: HTTP ${response.code}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    })
}