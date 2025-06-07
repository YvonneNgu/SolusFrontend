package com.example.solusfrontend.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * PreferencesManager - Persistent storage for app preferences and states
 * 
 * This utility class provides a centralized way to store and retrieve
 * application preferences and states that need to persist across app restarts.
 * It uses Android's SharedPreferences as the underlying storage mechanism.
 */
class PreferencesManager(context: Context) {
    
    // The shared preferences instance - our persistent storage
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFERENCES_NAME, 
        Context.MODE_PRIVATE
    )
    
    /**
     * Store whether the user has been asked for audio permission
     * This helps prevent repeated permission requests when user has already decided
     * 
     * @param hasBeenAsked true if the permission dialog has been shown to the user
     */
    fun setAudioPermissionRequested(hasBeenAsked: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_AUDIO_PERMISSION_REQUESTED, hasBeenAsked).apply()
    }
    
    /**
     * Check if we've already asked the user for audio permission
     * 
     * @return true if we've already shown the permission dialog to the user
     */
    fun hasAudioPermissionBeenRequested(): Boolean {
        return sharedPreferences.getBoolean(KEY_AUDIO_PERMISSION_REQUESTED, false)
    }
    
    /**
     * Store whether the user has been asked for overlay permission
     * 
     * @param hasBeenAsked true if the permission dialog has been shown to the user
     */
    fun setOverlayPermissionRequested(hasBeenAsked: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_OVERLAY_PERMISSION_REQUESTED, hasBeenAsked).apply()
    }
    
    /**
     * Check if we've already asked the user for overlay permission
     * 
     * @return true if we've already shown the permission dialog to the user
     */
    fun hasOverlayPermissionBeenRequested(): Boolean {
        return sharedPreferences.getBoolean(KEY_OVERLAY_PERMISSION_REQUESTED, false)
    }
    
    /**
     * Store user's preference for service state
     * This helps remember if the user explicitly stopped the service
     * 
     * @param isServiceEnabled true if the service should be running, false if user stopped it
     */
    fun setServiceEnabled(isServiceEnabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SERVICE_ENABLED, isServiceEnabled).apply()
    }
    
    /**
     * Check if the service should be running based on user's last action
     * Default is true (enabled) unless the user explicitly stopped it
     * 
     * @return true if the service should be running
     */
    fun isServiceEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_SERVICE_ENABLED, true)
    }
    
    companion object {
        // Shared preferences file name
        private const val PREFERENCES_NAME = "solus_preferences"
        
        // Keys for storing values
        private const val KEY_AUDIO_PERMISSION_REQUESTED = "audio_permission_requested"
        private const val KEY_OVERLAY_PERMISSION_REQUESTED = "overlay_permission_requested"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        
        // Singleton instance
        @Volatile
        private var INSTANCE: PreferencesManager? = null
        
        /**
         * Get the singleton instance of PreferencesManager
         * 
         * @param context Application context
         * @return The PreferencesManager instance
         */
        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
} 