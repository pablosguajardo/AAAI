package com.aaai.app.services

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.aaai.app.utils.ApkPackageRenamer
import androidx.core.content.pm.PackageInfoCompat
import com.aaai.app.data.*
import com.aaai.app.utils.InstalledVersionTracker
import com.aaai.app.utils.NetworkErrors
import com.aaai.app.utils.PackageNaming
import com.aaai.app.utils.SigningCheck
import com.aaai.app.utils.VersionCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Servicio de metadatos de apps.
 * Lee el catálogo desde assets/apps_data.json en lugar de Firebase.
 * No requiere conexión a internet para cargar el catálogo.
 */
class AppMetadataService(private val context: Context) {

    companion object {
        private const val TAG = "com.aaai.app-AppMetadataService"
        private const val CATALOG_FILE = "apps_data.json"
        /** When a GitHub query fails (or only an old saved copy was available), try again after this long. */
        private const val RETRY_AFTER_FAILURE_MS = 15L * 60 * 1000
    }

    private val db = AppDatabase.getInstance(context)
    private val dao = db.appMetadataDao()

    /**
     * Set by [refreshLatestGithubTags] when GitHub could not be queried (rate limit, not found,
     * no connection...). Null when everything went fine or nothing had to be queried.
     */
    var lastRefreshWarning: String? = null
        private set

    /**
     * Inicializa la base de datos local cargando el catálogo desde assets si está vacía.
     */
    suspend fun initializeAppData() = withContext(Dispatchers.IO) {
        try {
            val count = dao.getAppsCount()
            val metaPrefs = context.getSharedPreferences("catalog_meta", Context.MODE_PRIVATE)
            val catalogHash = currentCatalogHash()
            // If apps_data.json changed since the last load, reload it into Room
            val needsReload = count > 0 && metaPrefs.getInt("catalog_hash", 0) != catalogHash
            if (count == 0 || needsReload) {
                Log.d(TAG, if (needsReload) "Catálogo modificado, recargando..." else "DB vacía, cargando catálogo desde assets...")
                val apps = loadCatalogFromAssets()
                if (apps.isNotEmpty()) {
                    if (needsReload) {
                        // Apps removed from the JSON: forget their saved versions/tags too
                        val newIds = apps.map { it.appId }.toSet()
                        dao.getAllApps().map { it.appId }.filter { it !in newIds }.forEach {
                            Log.d(TAG, "App removida del catálogo: $it")
                            InstalledVersionTracker.forget(context, it)
                        }
                        dao.clearAll()
                    }
                    dao.insertApps(apps)
                    metaPrefs.edit().putInt("catalog_hash", catalogHash).apply()
                    Log.d(TAG, "Catálogo cargado: ${apps.size} apps insertadas en Room")
                }
            } else {
                Log.d(TAG, "DB ya inicializada con $count apps")
            }
            // Later loads show the states already stored in Room (onResume refreshes them right after);
            // only a fresh catalog load needs the full scan now.
            if (count == 0 || needsReload) updateInstallationStates()
        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar datos: ${e.message}", e)
        }
    }

    private fun currentCatalogHash(): Int {
        return try {
            context.assets.open(CATALOG_FILE).bufferedReader().use { it.readText() }.hashCode()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Lee el catálogo completo desde assets/apps_data.json
     */
    private fun loadCatalogFromAssets(): List<AppMetadata> {
        return try {
            val json = context.assets.open(CATALOG_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val appsArray: JSONArray = root.getJSONArray("app_metadata")
            parseAppsArray(appsArray)
        } catch (e: Exception) {
            Log.e(TAG, "Error leyendo $CATALOG_FILE: ${e.message}", e)
            emptyList()
        }
    }

    private fun parseAppsArray(array: JSONArray): List<AppMetadata> {
        val result = mutableListOf<AppMetadata>()
        for (i in 0 until array.length()) {
            try {
                val obj = array.getJSONObject(i)
                // Entries without an appId are ignored
                val appId = obj.optString("appId", "").trim()
                if (appId.isEmpty()) {
                    Log.d(TAG, "Skipping catalog entry $i: empty appId")
                    continue
                }
                result.add(
                    AppMetadata(
                        appId = appId,
                        appName = obj.getString("appName"),
                        packageName = obj.getString("packageName"),
                        version = obj.optString("version", ""),
                        versionCode = obj.optInt("versionCode", 0),
                        description = obj.optString("description", ""),
                        iconUrl = obj.optString("iconUrl", ""),
                        downloadUrl = obj.optString("downloadUrl", ""),
                        sourceType = AppSourceType.fromString(obj.optString("sourceType")),
                        isInstalled = false,
                        installedVersion = null,
                        hasUpdate = false,
                        category = obj.optString("category", "").trim().ifEmpty { "OTHER" },
                        githubRepo = obj.optString("githubRepo").takeIf { it.isNotEmpty() },
                        action = obj.optString("action").trim().takeIf { it.isNotEmpty() },
                        carUrl = obj.optString("carUrl").trim().takeIf { it.isNotEmpty() },
                        downloadState = DownloadState.IDLE
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error parseando app $i: ${e.message}")
            }
        }
        return result
    }

    /**
     * Obtiene todas las apps (desde Room).
     */
    suspend fun getAllApps(): List<AppMetadata> = withContext(Dispatchers.IO) {
        // Sorted by category (A-Z), then by app name (A-Z, case-insensitive)
        dao.getAllApps().sortedWith(
            compareBy<AppMetadata>({ it.category.lowercase() }, { it.appName.lowercase() })
        )
    }

    /**
     * Obtiene apps por categoría.
     */
    suspend fun getAppsByCategory(category: String): List<AppMetadata> =
        withContext(Dispatchers.IO) {
            dao.getAppsByCategory(category)
        }

    /**
     * Obtiene una app por su ID.
     */
    suspend fun getAppById(appId: String): AppMetadata? = withContext(Dispatchers.IO) {
        dao.getApp(appId)
    }

    /**
     * Devuelve el package realmente instalado para una app del catálogo, o null si no está instalada.
     * Las apps se instalan con un package renombrado (guardado en "pkg_mappings"), por lo que
     * primero se busca ese y, como respaldo, el package original.
     */
    fun resolveInstalledPackage(
        originalPackage: String,
        installed: Set<String> = PackageNaming.installedPackageNames(context)
    ): String? = PackageNaming.resolveInstalled(context, originalPackage, installed)

    /**
     * Actualiza los estados de instalación comparando con las apps instaladas en el dispositivo.
     */
    // Package events, onResume and install results all ask for a refresh: run them one at a time
    private val stateLock = Mutex()

    suspend fun updateInstallationStates() = withContext(Dispatchers.IO) {
        stateLock.withLock { scanInstallationStates() }
    }

    /**
     * Fast path for a "package removed" event: marks the catalog app that owned [removedPackage] as not installed
     * right away, without the full scan. The full refresh that follows confirms it. Returns true if a row changed.
     */
    suspend fun markPackageRemoved(removedPackage: String): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        for (app in dao.getAllApps()) {
            val owns = removedPackage == app.packageName || removedPackage.startsWith("${app.packageName}.aaai")
            if (!owns || !app.isInstalled) continue
            // Only if the package is really gone (a renamed copy may still coexist with the original)
            if (resolveInstalledPackage(app.packageName) != null) continue
            dao.updateInstallationStatus(app.packageName, false, null)
            dao.updateHasUpdate(app.packageName, false)
            dao.updateSignatureMismatch(app.packageName, false)
            InstalledVersionTracker.clearInstalled(context, app.appId)
            changed = true
        }
        changed
    }

    private suspend fun scanInstallationStates() {
        try {
            val packageManager = context.packageManager
            val apps = dao.getAllApps()
            val t0 = android.os.SystemClock.elapsedRealtime()
            // One scan of the installed packages for the whole list
            val installedNames = PackageNaming.installedPackageNames(context)
            val t1 = android.os.SystemClock.elapsedRealtime()
            val ownCert = SigningCheck.ownCertHash(context)
            val t2 = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "timing: package scan ${t1 - t0} ms, own certificate ${t2 - t1} ms")
            var sigMs = 0L
            for (app in apps) {
                try {
                    val installedPackage = resolveInstalledPackage(app.packageName, installedNames)
                        ?: throw IllegalStateException("not installed")
                    val packageInfo = packageManager.getPackageInfo(installedPackage, 0)
                    // Stamped version + signature check: cached per package, recomputed only if it changed
                    val s0 = android.os.SystemClock.elapsedRealtime()
                    val cached = cachedPackageData(installedPackage, packageInfo.lastUpdateTime, ownCert)
                    sigMs += android.os.SystemClock.elapsedRealtime() - s0
                    val isInstalled = true
                    val isGithub = app.isGithubRelease()
                    val hasUpdate: Boolean
                    val shownVersion: String?
                    if (isGithub) {
                        // GitHub apps: compare the tag we installed with the newest tag known
                        InstalledVersionTracker.confirmPendingIfInstalled(context, app.appId, packageInfo.lastUpdateTime)
                        // After reinstalling AAAI the saved tag is gone: recover it from the installed APK itself
                        cached.first?.let {
                            InstalledVersionTracker.recoverInstalled(context, app.appId, it)
                        }
                        val installedTag = InstalledVersionTracker.getInstalledTag(context, app.appId)
                        val latestTag = InstalledVersionTracker.getLatestTag(context, app.appId)
                        // Installed tag unknown (installed before the version stamp): compare the APK's own
                        // versionName ("0.84.900-xyz") with the latest tag by their numeric parts
                        hasUpdate = if (installedTag != null) {
                            latestTag != null && isNewerTag(latestTag, installedTag)
                        } else {
                            latestTag != null && isNewerVersion(latestTag, packageInfo.versionName)
                        }
                        shownVersion = installedTag ?: packageInfo.versionName
                        Log.d(TAG, "ghstate ${app.appId}: installedTag=$installedTag latestTag=$latestTag " +
                            "stamped=${cached.first} apkVersionName=${packageInfo.versionName} hasUpdate=$hasUpdate")
                    } else {
                        // FILE apps: compare the catalog "version" with the version recorded at install time.
                        // (The real APK versionCode is not comparable: the catalog value may differ from it.)
                        InstalledVersionTracker.confirmPendingIfInstalled(context, app.appId, packageInfo.lastUpdateTime)
                        // Recover the real installed version from the APK first; the baseline below
                        // (assume "same as catalog") only applies to apps installed before the stamp existed.
                        cached.first?.let {
                            InstalledVersionTracker.recoverInstalled(context, app.appId, it)
                        }
                        if (app.version.isNotEmpty()) {
                            InstalledVersionTracker.ensureBaseline(context, app.appId, app.version)
                        }
                        val installedVersion = InstalledVersionTracker.getInstalledTag(context, app.appId)
                        hasUpdate = app.version.isNotEmpty() && installedVersion != null &&
                                UpdateChecker.compareVersions(app.version, installedVersion) > 0
                        shownVersion = installedVersion ?: packageInfo.versionName
                    }
                    dao.updateInstallationStatus(app.packageName, isInstalled, shownVersion)
                    dao.updateHasUpdate(app.packageName, hasUpdate)
                    // Installed copy signed with another key (AAAI reinstalled, key regenerated): can't update in place
                    dao.updateSignatureMismatch(app.packageName, cached.second)
                } catch (e: Exception) {
                    // App no instalada
                    dao.updateInstallationStatus(app.packageName, false, null)
                    dao.updateHasUpdate(app.packageName, false)
                    dao.updateSignatureMismatch(app.packageName, false)
                    if (app.isGithubRelease()) {
                        InstalledVersionTracker.clearInstalled(context, app.appId)
                    }
                }
            }
            Log.d(TAG, "Estados de instalación actualizados (total ${android.os.SystemClock.elapsedRealtime() - t0} ms, " +
                "firmas $sigMs ms)")
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando estados: ${e.message}", e)
        }
    }

    /**
     * Consulta GitHub por el último release de cada app de tipo GitHub que esté instalada y guarda
     * el tag más nuevo (máx. 1 consulta cada 24 h por app, para no agotar el límite de la API).
     * Devuelve true si algún dato cambió.
     */
    /**
     * (stamped catalog version, signature mismatch) of an installed package. Cached in SharedPreferences with
     * the package's lastUpdateTime and AAAI's certificate hash, so unchanged packages are not re-read.
     */
    private fun cachedPackageData(pkg: String, lastUpdate: Long, ownCert: String?): Pair<String?, Boolean> {
        val prefs = context.getSharedPreferences("pkg_scan_cache", Context.MODE_PRIVATE)
        val key = "v1|$pkg|$lastUpdate|${ownCert ?: "none"}"
        prefs.getString(key, null)?.let { saved ->
            val parts = saved.split('\u0001')
            if (parts.size == 2) return (parts[0].ifEmpty { null }) to (parts[1] == "1")
        }
        val version = readCatalogVersion(pkg)
        val mismatch = SigningCheck.isMismatch(context, pkg, ownCert)
        // Drop older entries of the same package, then store the fresh one
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("v1|$pkg|") }.forEach { editor.remove(it) }
        editor.putString(key, (version ?: "") + '\u0001' + (if (mismatch) "1" else "0")).apply()
        return version to mismatch
    }

    /** Latest tag vs. an installed APK versionName ("0.84.900-label"): numeric core only, the suffix is a build label. */
    private fun isNewerVersion(latest: String, installed: String?): Boolean =
        VersionCompare.compare(latest, installed, ignorePreRelease = true)?.let { it > 0 }
            ?: (installed != null && latest != installed)

    /** Latest tag vs. an installed tag (SemVer precedence); without numbers, any difference counts. */
    private fun isNewerTag(latest: String, installedTag: String): Boolean =
        VersionCompare.compare(latest, installedTag)?.let { it > 0 } ?: (latest != installedTag)

    /** Newest stable release (SemVer: a pre-release is not a target for updates); falls back to the newest one. */
    private fun latestStableTag(releases: List<GitHubReleaseService.Release>): String? =
        (releases.firstOrNull { !it.prerelease && !VersionCompare.isPreRelease(it.tag) } ?: releases.firstOrNull())?.tag

    /** Version stamped into the installed APK's manifest by ApkPackageRenamer, or null if absent. */
    private fun readCatalogVersion(installedPackage: String): String? = try {
        val info = context.packageManager.getApplicationInfo(installedPackage, PackageManager.GET_META_DATA)
        info.metaData?.getString(ApkPackageRenamer.META_CATALOG_VERSION)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    suspend fun refreshLatestGithubTags(
        force: Boolean = false,
        onAppUpdated: suspend () -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        lastRefreshWarning = null
        val prefs = context.getSharedPreferences("catalog_meta", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // One-time: releases used to be ordered by publish date, so saved "latest" tags may be wrong. Re-evaluate once.
        val reorder = prefs.getInt("tag_order_version", 0) < 3
        val forceAll = force || reorder
        val pending = dao.getAllApps().filter { app ->
            val repo = app.githubRepo
            val noLatestYet = InstalledVersionTracker.getLatestTag(context, app.appId) == null
            // Never answered by GitHub yet: try again, but at most once every 15 min
            app.isGithubRelease() && !repo.isNullOrEmpty() && app.isInstalled &&
                (forceAll ||
                    if (noLatestYet) now - prefs.getLong("last_try_${app.appId}", 0L) >= RETRY_AFTER_FAILURE_MS
                    else now - prefs.getLong("last_check_${app.appId}", 0L) >= GitHubReleaseService.CACHE_TTL_MS)
        }
        // One query per app, all at once: each app flips its button as soon as its own answer arrives
        val gate = Semaphore(3) // at most 3 GitHub queries at once, to stay far from the API rate limit
        val results = coroutineScope {
            pending.map { app ->
                async {
                    val key = "last_check_${app.appId}"
                    gate.acquire()
                    prefs.edit().putLong("last_try_${app.appId}", now).apply()
                    try {
                        val service = GitHubReleaseService(context)
                        val latest = latestStableTag(service.getReleases(app.githubRepo!!))
                            ?: return@async false
                        // Only a fresh answer blocks the next query for 24 h; an old saved copy is retried soon
                        val warning = service.lastWarning
                        prefs.edit().putLong(
                            key,
                            if (warning == null) now else now - (GitHubReleaseService.CACHE_TTL_MS - RETRY_AFTER_FAILURE_MS)
                        ).apply()
                        warning?.let { lastRefreshWarning = "${app.appName}: $it" }
                        if (InstalledVersionTracker.getLatestTag(context, app.appId) != latest) {
                            InstalledVersionTracker.setLatestTag(context, app.appId, latest)
                            val installedTag = InstalledVersionTracker.getInstalledTag(context, app.appId)
                            val newer = if (installedTag != null) isNewerTag(latest, installedTag)
                                        else isNewerVersion(latest, app.installedVersion)
                            dao.updateHasUpdate(app.packageName, newer)
                            onAppUpdated()
                            true
                        } else false
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo consultar ${app.githubRepo}: ${e.message}")
                        lastRefreshWarning = "${app.appName}: ${NetworkErrors.describe(e)}"
                        // Failed: retry in 15 min (not on every refresh, not after 24 h)
                        prefs.edit().putLong(key, now - (GitHubReleaseService.CACHE_TTL_MS - RETRY_AFTER_FAILURE_MS)).apply()
                        false
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
        if (reorder) prefs.edit().putInt("tag_order_version", 3).apply()
        results.any { it }
    }

    /**
     * Actualiza el estado de descarga de una app.
     */
    suspend fun updateDownloadState(appId: String, state: DownloadState) =
        withContext(Dispatchers.IO) {
            dao.updateDownloadState(appId, state)
        }

    /**
     * Obtiene apps instaladas.
     */
    suspend fun getInstalledApps(): List<AppMetadata> = withContext(Dispatchers.IO) {
        dao.getInstalledApps()
    }

    /**
     * Obtiene apps con actualizaciones disponibles.
     */
    suspend fun getAppsWithUpdates(): List<AppMetadata> = withContext(Dispatchers.IO) {
        dao.getAppsWithUpdates()
    }

    /**
     * Fuerza recarga del catálogo desde assets (descarta y recarga Room).
     */
    suspend fun forceReloadFromAssets() = withContext(Dispatchers.IO) {
        try {
            dao.clearAll()
            val apps = loadCatalogFromAssets()
            if (apps.isNotEmpty()) {
                dao.insertApps(apps)
                Log.d(TAG, "Catálogo recargado: ${apps.size} apps")
            }
            updateInstallationStates()
        } catch (e: Exception) {
            Log.e(TAG, "Error recargando catálogo: ${e.message}", e)
        }
    }
}
