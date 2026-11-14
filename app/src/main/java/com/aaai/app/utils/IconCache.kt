package com.aaai.app.utils

import android.content.Context

/**
 * Gives each icon URL a "version stamp" that changes at most once every 30 days.
 * Applies to every app icon in the list (Firebase, GitHub or any other source).
 *
 * Used as a Glide signature: while the stamp is unchanged Glide serves the icon from its
 * disk cache without touching the network. After 30 days the stamp changes, Glide downloads the
 * icon again and the new bytes replace what is shown. The previous stamp is kept so the
 * old cached icon can still be shown if the refresh fails (e.g. offline).
 */
object IconCache {

    private const val PREFS = "icon_cache"
    const val TTL_MS = 30L * 24 * 60 * 60 * 1000

    data class Stamp(val current: Long, val previous: Long?)

    fun stampFor(ctx: Context, url: String): Stamp {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "ts_" + url.hashCode()
        val prevKey = "prev_" + url.hashCode()
        val now = System.currentTimeMillis()
        val ts = prefs.getLong(key, 0L)

        if (ts == 0L) {
            prefs.edit().putLong(key, now).apply()
            return Stamp(now, null)
        }
        if (now - ts >= TTL_MS) {
            prefs.edit().putLong(prevKey, ts).putLong(key, now).apply()
            return Stamp(now, ts)
        }
        val prev = prefs.getLong(prevKey, 0L).takeIf { it != 0L }
        return Stamp(ts, prev)
    }
}
