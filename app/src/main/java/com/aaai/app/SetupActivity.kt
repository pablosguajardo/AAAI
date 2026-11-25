package com.aaai.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aaai.app.utils.NotificationPermissionHelper

/**
 * First-run setup screen.
 * Shows instructions for disabling Play Protect and granting install permission.
 * Only shown once — after the user taps "Continue" it is never shown again.
 *
 * On Android 13+ the notification permission is requested here so that Android Auto
 * update alerts work out of the box for new installs. The dialog is shown by the system
 * immediately after the user taps "Continue"; accepting or declining both proceed normally.
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "aaai_prefs"
        private const val KEY_SETUP_DONE = "setup_done"

        fun isSetupDone(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SETUP_DONE, false)
        }

        private fun markSetupDone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SETUP_DONE, true)
                .apply()
        }
    }

    // Handles the system POST_NOTIFICATIONS prompt result.
    // Regardless of the user's choice we mark it as asked and continue to main.
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            NotificationPermissionHelper.markAsked(this)
            goToMain()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Open Play Store — user navigates to Profile > Play Protect > Settings
        // There's no direct deep link to Play Protect, so we open Play Store main screen
        findViewById<Button>(R.id.btnOpenPlayProtect).setOnClickListener {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.android.vending")
                if (intent != null) {
                    startActivity(intent)
                } else {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/")))
                }
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/")))
                } catch (ex: Exception) {
                    android.widget.Toast.makeText(this, "Could not open Play Store", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Open install permission settings
        findViewById<Button>(R.id.btnInstallPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        // Continue to main app.
        // On Android 13+ the system notification-permission dialog is shown first;
        // the result callback (above) always calls goToMain() afterwards.
        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            markSetupDone(this)
            if (NotificationPermissionHelper.shouldAsk(this)) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                goToMain()
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, GithubSearchActivity::class.java))
        finish()
    }
}
