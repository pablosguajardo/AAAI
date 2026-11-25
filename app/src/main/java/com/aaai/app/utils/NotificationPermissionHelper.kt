package com.aaai.app.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Centralizes the POST_NOTIFICATIONS permission check and the "already asked" flag,
 * which is shared between [SetupActivity] (new installs) and [MainActivity] (existing users).
 *
 * On API < 33 (Android < 13) the permission is always considered granted — no dialog is shown.
 */
object NotificationPermissionHelper {

    // Same SharedPreferences file used by SetupActivity so the flag survives across activities
    private const val PREFS = "aaai_prefs"
    private const val KEY_ASKED = "notification_permission_asked_v1"

    /** True if POST_NOTIFICATIONS is granted, or the device runs Android < 13. */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** True if we already asked the user (regardless of their answer). */
    fun wasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)

    /** Saves the flag so we never ask again. */
    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }

    /**
     * Returns true if we should ask: Android 13+, permission not yet granted, never asked.
     * If this returns false (either granted or already asked) the caller can skip the dialog.
     */
    fun shouldAsk(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isGranted(context) && !wasAsked(context)
}
