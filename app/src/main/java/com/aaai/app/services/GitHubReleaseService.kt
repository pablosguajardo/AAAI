package com.aaai.app.services

import android.content.Context
import android.os.Build
import android.util.Log
import com.aaai.app.utils.HttpStatusException
import com.aaai.app.utils.NetworkErrors
import com.aaai.app.utils.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Queries the GitHub Releases API for a repo and returns up to [MAX_RELEASES] releases
 * that contain at least one .apk asset, sorted from newest to oldest.
 *
 * Responses are cached on disk for [CACHE_TTL_MS] (24h). If GitHub cannot be reached
 * (offline, rate limit...) an expired cache is used instead of failing.
 */
class GitHubReleaseService(private val context: Context) {

    /**
     * Set when the last [getReleases] call could not reach GitHub and an older cached copy
     * was used instead (e.g. "GitHub API rate limit reached..."). Null when data is fresh.
     */
    var lastWarning: String? = null
        private set

    data class Asset(val name: String, val downloadUrl: String, val size: Long)

    data class Release(
        val tag: String,
        val title: String,
        val publishedAt: Long,
        val prerelease: Boolean,
        val apkAssets: List<Asset>
    ) {
        /** Best APK for this device (matches ABI when the release ships several builds). */
        fun bestAsset(): Asset? = pickAsset(apkAssets)
    }

    companion object {
        private const val TAG = "com.aaai.app-GitHubReleaseService"
        const val MAX_RELEASES = 10
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

        private val ABI_KEYWORDS = listOf("arm64", "armeabi", "arm32", "armv7", "x86_64", "x86")

        /** Picks the APK matching the device ABI; falls back to a universal one, then the first. */
        fun pickAsset(assets: List<Asset>): Asset? {
            if (assets.isEmpty()) return null
            if (assets.size == 1) return assets[0]

            for (abi in Build.SUPPORTED_ABIS) {
                val keys = when (abi) {
                    "arm64-v8a" -> listOf("arm64")
                    "armeabi-v7a" -> listOf("armeabi-v7a", "armv7", "arm32", "armeabi")
                    "x86_64" -> listOf("x86_64")
                    "x86" -> listOf("x86")
                    else -> listOf(abi)
                }
                assets.firstOrNull { a ->
                    val n = a.name.lowercase()
                    keys.any { n.contains(it) } && !(abi == "x86" && n.contains("x86_64"))
                }?.let { return it }
            }
            // Universal build (no ABI keyword in the name)
            assets.firstOrNull { a -> ABI_KEYWORDS.none { a.name.lowercase().contains(it) } }?.let { return it }
            return assets[0]
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun cacheFile(repo: String, cacheTag: String = ""): File {
        val safe = repo.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val suffix = if (cacheTag.isEmpty()) "" else "_$cacheTag"
        return File(context.cacheDir, "gh_releases_$safe$suffix.json")
    }

    /**
     * Milliseconds left before the cache identified by [cacheTag] expires for the given [ttlMs].
     * 0 if there is no cache or it is already expired (the next call will query GitHub).
     */
    fun cacheRemainingMs(repo: String, cacheTag: String, ttlMs: Long): Long {
        val cache = cacheFile(repo, cacheTag)
        if (!cache.exists()) return 0L
        val age = System.currentTimeMillis() - cache.lastModified()
        return (ttlMs - age).coerceAtLeast(0L)
    }

    /**
     * Returns releases (newest first). Uses the on-disk cache while it is younger than 24h
     * (unless [forceRefresh]); falls back to an expired cache if GitHub is unreachable.
     */
    suspend fun getReleases(
        repo: String,
        forceRefresh: Boolean = false,
        cacheTag: String = "",
        ttlMs: Long = CACHE_TTL_MS
    ): List<Release> =
        withContext(Dispatchers.IO) {
            lastWarning = null
            // A non-empty cacheTag uses its own cache file, independent from the default one
            val cache = cacheFile(repo, cacheTag)
            val cacheAge = if (cache.exists()) System.currentTimeMillis() - cache.lastModified() else Long.MAX_VALUE

            if (!forceRefresh && cacheAge < ttlMs) {
                try {
                    Log.d(TAG, "Using cached releases for $repo (age ${cacheAge / 60000} min)")
                    return@withContext parse(cache.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Corrupt cache for $repo, refetching: ${e.message}")
                    cache.delete()
                }
            }

            try {
                val body = fetch(repo)
                val releases = parse(body)
                try { cache.writeText(body) } catch (e: Exception) { Log.w(TAG, "Cache write failed: ${e.message}") }
                releases
            } catch (e: Exception) {
                // Network error / rate limit: an expired cache is better than nothing
                if (cache.exists()) {
                    try {
                        Log.w(TAG, "GitHub failed for $repo (${e.message}); using expired cache")
                        lastWarning = "${NetworkErrors.describe(e)} Showing previously saved versions."
                        return@withContext parse(cache.readText())
                    } catch (_: Exception) { /* fall through */ }
                }
                throw e
            }
        }

    private fun fetch(repo: String): String {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AAAI-App")
            .build()

        client.newCall(request).execute().use { response ->
            val rateLimited = response.code == 429 ||
                    (response.code == 403 && response.header("X-RateLimit-Remaining") == "0")
            if (rateLimited) {
                throw HttpStatusException(
                    response.code,
                    "GitHub API request limit reached. Please try again later."
                )
            }
            if (response.code == 404) {
                throw HttpStatusException(404, "Repository or releases not found on GitHub ($repo).")
            }
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, "GitHub: " + NetworkErrors.httpMessage(response.code))
            }
            return response.body?.string() ?: throw Exception("Empty response from GitHub")
        }
    }

    private fun parse(json: String): List<Release> {
        val array = JSONArray(json)
        val result = mutableListOf<Release>()
        for (i in 0 until array.length()) {
            val r = array.getJSONObject(i)
            if (r.optBoolean("draft", false)) continue

            val assetsJson = r.optJSONArray("assets") ?: continue
            val apks = mutableListOf<Asset>()
            for (j in 0 until assetsJson.length()) {
                val a = assetsJson.getJSONObject(j)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apks.add(Asset(name, a.optString("browser_download_url"), a.optLong("size", 0L)))
                }
            }
            if (apks.isEmpty()) continue

            val tag = r.optString("tag_name")
            result.add(
                Release(
                    tag = tag,
                    title = r.optString("name").ifEmpty { tag },
                    publishedAt = parseDate(r.optString("published_at")),
                    prerelease = r.optBoolean("prerelease", false),
                    apkAssets = apks
                )
            )
        }
        Log.d(TAG, "Parsed ${result.size} releases with APKs")
        return sortNewestFirst(result).take(MAX_RELEASES)
    }

    /**
     * Newest first. When every tag carries a number ("v0.3", "0.0.5") the highest version wins, because some repos
     * publish releases out of order; otherwise (tags like "nightly") the publication date decides.
     */
    private fun sortNewestFirst(releases: List<Release>): List<Release> {
        if (releases.any { !VersionCompare.hasNumber(it.tag) }) return releases.sortedByDescending { it.publishedAt }
        // SemVer precedence decides; publication date only breaks ties
        return releases.sortedWith(Comparator { a, b ->
            val c = VersionCompare.compare(b.tag, a.tag) ?: 0
            if (c != 0) c else b.publishedAt.compareTo(a.publishedAt)
        })
    }

    private fun parseDate(value: String): Long {
        if (value.isEmpty()) return 0L
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(value)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
