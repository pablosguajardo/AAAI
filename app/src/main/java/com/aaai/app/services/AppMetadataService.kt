package com.aaai.app.services

import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.aaai.app.data.*
import com.aaai.app.utils.InstalledVersionTracker
import com.aaai.app.utils.NetworkErrors
import com.aaai.app.utils.PackageNaming
import kotlinx.coroutines.Dispatchers
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
                    if (needsReload) dao.clearAll()
                    dao.insertApps(apps)
                    metaPrefs.edit().putInt("catalog_hash", catalogHash).apply()
                    Log.d(TAG, "Catálogo cargado: ${apps.size} apps insertadas en Room")
                }
            } else {
                Log.d(TAG, "DB ya inicializada con $count apps")
            }
            updateInstallationStates()
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
    suspend fun updateInstallationStates() = withContext(Dispatchers.IO) {
        try {
            val packageManager = context.packageManager
            val apps = dao.getAllApps()
            // One scan of the installed packages for the whole list
            val installedNames = PackageNaming.installedPackageNames(context)
            for (app in apps) {
                try {
                    val installedPackage = resolveInstalledPackage(app.packageName, installedNames)
                        ?: throw IllegalStateException("not installed")
                    val packageInfo = packageManager.getPackageInfo(installedPackage, 0)
                    val isInstalled = true
                    val isGithub = app.isGithubRelease()
                    val hasUpdate: Boolean
                    val shownVersion: String?
                    if (isGithub) {
                        // GitHub apps: compare the tag we installed with the newest tag known
                        InstalledVersionTracker.confirmPendingIfInstalled(context, app.appId, packageInfo.lastUpdateTime)
                        val installedTag = InstalledVersionTracker.getInstalledTag(context, app.appId)
                        val latestTag = InstalledVersionTracker.getLatestTag(context, app.appId)
                        hasUpdate = installedTag != null && latestTag != null && installedTag != latestTag
                        shownVersion = installedTag ?: packageInfo.versionName
                    } else {
                        // FILE apps: compare the catalog "version" with the version recorded at install time.
                        // (The real APK versionCode is not comparable: the catalog value may differ from it.)
                        InstalledVersionTracker.confirmPendingIfInstalled(context, app.appId, packageInfo.lastUpdateTime)
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
                } catch (e: Exception) {
                    // App no instalada
                    dao.updateInstallationStatus(app.packageName, false, null)
                    dao.updateHasUpdate(app.packageName, false)
                    if (app.isGithubRelease()) {
                        InstalledVersionTracker.clearInstalled(context, app.appId)
                    }
                }
            }
            Log.d(TAG, "Estados de instalación actualizados")
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando estados: ${e.message}", e)
        }
    }

    /**
     * Consulta GitHub por el último release de cada app de tipo GitHub que esté instalada y guarda
     * el tag más nuevo (máx. 1 consulta cada 24 h por app, para no agotar el límite de la API).
     * Devuelve true si algún dato cambió.
     */
    suspend fun refreshLatestGithubTags(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        var changed = false
        lastRefreshWarning = null
        val prefs = context.getSharedPreferences("catalog_meta", Context.MODE_PRIVATE)
        val service = GitHubReleaseService(context)
        val now = System.currentTimeMillis()
        for (app in dao.getAllApps()) {
            val repo = app.githubRepo
            if (!app.isGithubRelease() || repo.isNullOrEmpty() || !app.isInstalled) continue
            val key = "last_check_${app.appId}"
            if (!force && now - prefs.getLong(key, 0L) < GitHubReleaseService.CACHE_TTL_MS) continue
            try {
                val latest = service.getReleases(repo).firstOrNull()?.tag ?: continue
                prefs.edit().putLong(key, now).apply()
                service.lastWarning?.let { lastRefreshWarning = "${app.appName}: $it" }
                if (InstalledVersionTracker.getLatestTag(context, app.appId) != latest) {
                    InstalledVersionTracker.setLatestTag(context, app.appId, latest)
                    changed = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo consultar $repo: ${e.message}")
                lastRefreshWarning = "${app.appName}: ${NetworkErrors.describe(e)}"
                // Do not retry on every refresh when GitHub is failing or rate limiting
                prefs.edit().putLong(key, now).apply()
            }
        }
        if (changed) updateInstallationStates()
        changed
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
