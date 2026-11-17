package com.aaai.app.services

import android.content.Context
import android.util.Log
import com.aaai.app.BuildConfig
import com.aaai.app.utils.NetworkErrors

/**
 * Checks whether a newer AAAI release exists on GitHub.
 *
 * - If the newest release is newer than the installed version, an [UpdateInfo] is returned.
 * - The update becomes [UpdateInfo.mandatory] once [MAX_DAYS_WITHOUT_UPDATE] days have passed
 *   since that release was published (counted from its GitHub publish date).
 * - Any network problem returns null (a failed check never blocks the app).
 */
class UpdateChecker(private val context: Context) {

    data class UpdateInfo(
        val tag: String,
        val assetUrl: String,
        val daysSincePublished: Long,
        val mandatory: Boolean
    )

    companion object {
        private const val TAG = "UpdateChecker"

        /** Repository whose releases are used to update this app. */
        const val UPDATE_REPO = "pablosguajardo/AAAI"

        /** After this many days with a newer release available, updating becomes mandatory. */
        const val MAX_DAYS_WITHOUT_UPDATE = 15

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Minimum time between real GitHub queries made from the "Check for updates" menu button.
         * Presses in between reuse the saved result.
         */
        const val MANUAL_CHECK_INTERVAL_MS = 3L * 60 * 60 * 1000

        /** Compares dotted versions ("v1.0.0" vs "1.0"). Missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = Regex("\\d+").findAll(a).map { it.value.toLong() }.toList()
            val pb = Regex("\\d+").findAll(b).map { it.value.toLong() }.toList()
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0L }
                val y = pb.getOrElse(i) { 0L }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }

    /** Milliseconds until the menu button may query GitHub again. 0 means the next press will query. */
    fun manualCooldownRemainingMs(): Long =
        GitHubReleaseService(context).cacheRemainingMs(UPDATE_REPO, "manual", MANUAL_CHECK_INTERVAL_MS)

    /**
     * Set by [check] when GitHub could not be reached (message for the user). Null if the check
     * went fine. With a warning, the result may come from an older saved copy.
     */
    var lastError: String? = null
        private set

    /**
     * Returns info about a newer release, or null if up to date / the check failed (see [lastError]).
     * With [manual] (menu button) a separate cache is used: the first press queries GitHub, further
     * presses reuse that result until [MANUAL_CHECK_INTERVAL_MS] have passed since it was saved, then
     * GitHub is queried again. The automatic check keeps its own 24h cache and is not affected.
     */
    suspend fun check(manual: Boolean = false): UpdateInfo? {
        lastError = null
        return try {
            val service = GitHubReleaseService(context)
            val releases = if (manual) {
                service.getReleases(UPDATE_REPO, cacheTag = "manual", ttlMs = MANUAL_CHECK_INTERVAL_MS)
            } else {
                service.getReleases(UPDATE_REPO)
            }.filter { !it.prerelease }
            lastError = service.lastWarning
            // Newest by version number (not only by date)
            val newer = releases.filter { compareVersions(it.tag, BuildConfig.VERSION_NAME) > 0 }
            val latest = newer.maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) } ?: return null
            val asset = latest.bestAsset() ?: return null

            // Days are counted from the FIRST release newer than the installed one, so skipping
            // several versions does not restart the 15-day clock.
            val firstNewerDate = newer.map { it.publishedAt }.filter { it > 0 }.minOrNull()
            val days = if (firstNewerDate != null) {
                (System.currentTimeMillis() - firstNewerDate) / DAY_MS
            } else 0L
            UpdateInfo(
                tag = latest.tag,
                assetUrl = asset.downloadUrl,
                daysSincePublished = days,
                mandatory = days >= MAX_DAYS_WITHOUT_UPDATE
            )
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            lastError = NetworkErrors.describe(e)
            null
        }
    }
}
