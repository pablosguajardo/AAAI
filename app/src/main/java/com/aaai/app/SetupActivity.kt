package com.aaai.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * First-run setup screen.
 * Shows instructions for disabling Play Protect and granting install permission.
 * Only shown once — after the user taps "Continue" it is never shown again.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        // Open Play Store — user navigates to Profile > Play Protect > Settings
        // There's no direct deep link to Play Protect, so we open Play Store main screen
        findViewById<Button>(R.id.btnOpenPlayProtect).setOnClickListener {
            try {
                // Try to open Play Store app directly
                val intent = packageManager.getLaunchIntentForPackage("com.android.vending")
                if (intent != null) {
                    startActivity(intent)
                } else {
                    // Fallback: open in browser
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

        // Continue to main app
        findViewById<Button>(R.id.btnContinue).setOnClickListener {
            markSetupDone(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
