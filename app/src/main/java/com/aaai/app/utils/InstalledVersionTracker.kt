package com.aaai.app.utils

import android.content.Context

/**
 * Remembers which GitHub release tag is installed for each catalog app, and the latest
 * tag seen on GitHub, so the list can show "update available".
 *
 * Installs are recorded as "pending" first and confirmed later when the package's
 * lastUpdateTime moves past the moment the install was started (the install result code
 * from the system installer is not reliable).
 */
object InstalledVersionTracker {

    private const val PREFS = "installed_versions"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getInstalledTag(ctx: Context, appId: String): String? = prefs(ctx).getString("installed_$appId", null)

    fun getLatestTag(ctx: Context, appId: String): String? = prefs(ctx).getString("latest_$appId", null)

    fun setLatestTag(ctx: Context, appId: String, tag: String) {
        prefs(ctx).edit().putString("latest_$appId", tag).apply()
    }

    fun setPending(ctx: Context, appId: String, tag: String) {
        prefs(ctx).edit()
            .putString("pending_tag_$appId", tag)
            .putLong("pending_time_$appId", System.currentTimeMillis())
            .apply()
    }

    /** Promotes the pending tag to "installed" if the package was updated after the install started. */
    fun confirmPendingIfInstalled(ctx: Context, appId: String, packageLastUpdateTime: Long) {
        val p = prefs(ctx)
        val tag = p.getString("pending_tag_$appId", null) ?: return
        val started = p.getLong("pending_time_$appId", 0L)
        if (packageLastUpdateTime >= started) {
            p.edit()
                .putString("installed_$appId", tag)
                .remove("pending_tag_$appId")
                .remove("pending_time_$appId")
                .apply()
        }
    }

    /** Called when the app is no longer installed. */
    fun clearInstalled(ctx: Context, appId: String) {
        prefs(ctx).edit().remove("installed_$appId").apply()
    }
}
