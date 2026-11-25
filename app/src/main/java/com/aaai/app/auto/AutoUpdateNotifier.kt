package com.aaai.app.auto

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aaai.app.GithubSearchActivity
import com.aaai.app.R
import com.aaai.app.data.GithubRepo
import com.aaai.app.data.GithubRepoDbHelper
import com.aaai.app.services.GitHubReleaseService
import com.aaai.app.utils.ApkPackageRenamer
import com.aaai.app.utils.InstalledVersionTracker
import com.aaai.app.utils.PackageNaming
import com.aaai.app.utils.VersionCompare
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Posts a notification when Android Auto connects and some installed app has an update.
 *
 * Uses the same data as the main screen:
 *  - catalog: github_repos.db (GithubRepoDbHelper)
 *  - installed version: InstalledVersionTracker, or the version stamped inside the installed APK
 *  - latest version: GitHub releases through GitHubReleaseService (24 h disk cache, expired cache
 *    used if offline), falling back to the tag stored in the DB.
 */
class AutoUpdateNotifier(private val context: Context) {

    companion object {
        private const val TAG = "com.aaai.app-AutoUpdateNotifier"
        const val CHANNEL_ID = "aaai_auto_updates"
        const val NOTIFICATION_ID = 2001
        /** Receiver work must finish quickly (goAsync window is ~10 s). */
        private const val NETWORK_BUDGET_MS = 7_000L
        /** No re-postear la misma notificación dentro de esta ventana. */
        private const val NOTIFY_COOLDOWN_MS = 60 * 60 * 1_000L  // 1 hora
        private const val PREFS = "aaai_prefs"
        private const val KEY_LAST_NOTIFY = "auto_last_notify_ms"
    }

    /** Información de una app instalada que tiene update disponible. */
    data class AppUpdate(val displayName: String, val installed: String, val latest: String)

    private data class Update(val repo: GithubRepo, val installed: String, val latest: String)

    /**
     * Retorna la lista de apps instaladas del catálogo que tienen una versión más nueva
     * disponible. Puede ser llamado desde la pantalla de Android Auto.
     */
    suspend fun getAvailableUpdates(): List<AppUpdate> =
        findUpdates().map { AppUpdate(it.repo.displayName, it.installed, it.latest) }

    suspend fun checkAndNotify() {
        Log.d(TAG, "▶ checkAndNotify() START")

        // Evitar re-postear si ya notificamos recientemente (p.ej. onResume repetidos)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastNotify = prefs.getLong(KEY_LAST_NOTIFY, 0L)
        if (System.currentTimeMillis() - lastNotify < NOTIFY_COOLDOWN_MS) {
            Log.d(TAG, "▶ checkAndNotify() SKIPPED — cooldown active")
            return
        }

        val updates = findUpdates()
        Log.d(TAG, "Updates found: ${updates.size}")
        updates.forEach { Log.d(TAG, "  ${it.repo.displayName}: ${it.installed} -> ${it.latest}") }
        if (updates.isEmpty()) {
            Log.d(TAG, "▶ checkAndNotify() END — nothing to notify")
            return
        }

        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
        if (!granted) {
            Log.w(TAG, "▶ END — notification permission not granted")
            return
        }

        ensureChannel()

        val names = updates.take(3).joinToString(", ") { it.repo.displayName }
        val body = if (updates.size > 3) "$names and ${updates.size - 3} more" else names
        val title = if (updates.size == 1) "1 update available" else "${updates.size} updates available"

        val tap = PendingIntent.getActivity(
            context,
            0,
            Intent(context, GithubSearchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            prefs.edit().putLong(KEY_LAST_NOTIFY, System.currentTimeMillis()).apply()
            Log.d(TAG, "✅ Notification posted: $title / $body")
        } catch (e: SecurityException) {
            Log.w(TAG, "❌ Cannot post notification: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Notification failed: ${e.message}", e)
        }
        Log.d(TAG, "▶ checkAndNotify() END")
    }

    /** Apps del catálogo instaladas cuya última versión conocida es más nueva que la instalada. */
    private suspend fun findUpdates(): List<Update> {
        val helper = GithubRepoDbHelper(context)
        val db = helper.openReadable()
        if (db == null) {
            Log.w(TAG, "github_repos.db not available")
            return emptyList()
        }
        try {
            val repos = helper.allWithPackage(db)
            val installedPkgs = PackageNaming.installedPackageNames(context)
            val installed = repos.mapNotNull { repo ->
                val pkg = PackageNaming.resolveInstalled(context, repo.packageName, installedPkgs)
                    ?: return@mapNotNull null
                repo to pkg
            }
            Log.d(TAG, "Catalog: ${repos.size} apps with package, ${installed.size} installed")

            // Refresh latest tags from GitHub (disk cache first). Bounded in time; failures are ignored.
            withTimeoutOrNull(NETWORK_BUDGET_MS) {
                installed.filter { !it.first.isFileSource }.forEach { (repo, _) ->
                    try {
                        val service = GitHubReleaseService(context)
                        val releases = service.getReleases(repo.fullName)
                        val latest = (releases.firstOrNull { !it.prerelease && !VersionCompare.isPreRelease(it.tag) }
                            ?: releases.firstOrNull())?.tag
                        if (latest != null) InstalledVersionTracker.setLatestTag(context, repo.appId, latest)
                    } catch (e: Exception) {
                        Log.d(TAG, "No GitHub data for ${repo.fullName}: ${e.message}")
                    }
                }
            } ?: Log.w(TAG, "GitHub refresh timed out; using what is cached")

            return installed.mapNotNull { (repo, pkg) ->
                // Installed version: saved tag, else the version stamped inside the installed APK
                var installedVersion = InstalledVersionTracker.getInstalledTag(context, repo.appId)
                if (installedVersion == null) {
                    val stamped = readStampedVersion(pkg)
                    Log.d(TAG, "${repo.appId} pkg=$pkg stamped=$stamped")
                    if (stamped != null) {
                        InstalledVersionTracker.recoverInstalled(context, repo.appId, stamped)
                        installedVersion = stamped
                    }
                }
                val latest = InstalledVersionTracker.getLatestTag(context, repo.appId) ?: repo.lastTag
                if (latest == null) return@mapNotNull null

                if (installedVersion != null) {
                    if (installedVersion == latest) return@mapNotNull null
                    val newer = VersionCompare.compare(latest, installedVersion)?.let { it > 0 }
                        ?: (latest != installedVersion)
                    return@mapNotNull if (newer) Update(repo, installedVersion, latest) else null
                }

                // No tag known at all (installed before version tracking existed, no stamp either):
                // fall back to the installed APK's own versionName, ignoring pre-release suffixes
                // (same fallback the main screen and the old AppMetadataService used).
                val apkVersionName = try { context.packageManager.getPackageInfo(pkg, 0).versionName } catch (e: Exception) { null }
                Log.d(TAG, "${repo.appId} no tag at all, apkVersionName=$apkVersionName latest=$latest")
                val newer = VersionCompare.compare(latest, apkVersionName, ignorePreRelease = true)?.let { it > 0 }
                    ?: (apkVersionName != null && latest != apkVersionName)
                if (newer) Update(repo, apkVersionName ?: "?", latest) else null
            }
        } finally {
            try { db.close() } catch (_: Exception) {}
        }
    }

    private fun readStampedVersion(installedPackage: String): String? = try {
        context.packageManager.getApplicationInfo(installedPackage, PackageManager.GET_META_DATA)
            .metaData?.getString(ApkPackageRenamer.META_CATALOG_VERSION)
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Android Auto update alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies about available app updates when connected to Android Auto"
                enableVibration(false)
                setShowBadge(true)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
