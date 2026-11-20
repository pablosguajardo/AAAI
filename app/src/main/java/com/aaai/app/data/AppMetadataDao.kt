package com.aaai.app.data

import androidx.room.*

@Dao
interface AppMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: AppMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<AppMetadata>)

    @Update
    suspend fun updateApp(app: AppMetadata)

    @Query("SELECT * FROM app_metadata ORDER BY category, appName")
    suspend fun getAllApps(): List<AppMetadata>

    @Query("SELECT * FROM app_metadata WHERE category = :category ORDER BY appName")
    suspend fun getAppsByCategory(category: String): List<AppMetadata>

    @Query("SELECT * FROM app_metadata WHERE appId = :appId LIMIT 1")
    suspend fun getApp(appId: String): AppMetadata?

    @Query("SELECT * FROM app_metadata WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppByPackage(packageName: String): AppMetadata?

    @Query("SELECT * FROM app_metadata WHERE isInstalled = 1 ORDER BY appName")
    suspend fun getInstalledApps(): List<AppMetadata>

    @Query("SELECT * FROM app_metadata WHERE isInstalled = 1 AND hasUpdate = 1")
    suspend fun getAppsWithUpdates(): List<AppMetadata>

    @Query("SELECT COUNT(*) FROM app_metadata")
    suspend fun getAppsCount(): Int

    @Query("UPDATE app_metadata SET isInstalled = :isInstalled, installedVersion = :installedVersion WHERE packageName = :packageName")
    suspend fun updateInstallationStatus(packageName: String, isInstalled: Boolean, installedVersion: String?)

    @Query("UPDATE app_metadata SET hasUpdate = :hasUpdate WHERE packageName = :packageName")
    suspend fun updateHasUpdate(packageName: String, hasUpdate: Boolean)

    @Query("UPDATE app_metadata SET signatureMismatch = :mismatch WHERE packageName = :packageName")
    suspend fun updateSignatureMismatch(packageName: String, mismatch: Boolean)

    @Query("UPDATE app_metadata SET downloadState = :downloadState WHERE appId = :appId")
    suspend fun updateDownloadState(appId: String, downloadState: DownloadState)

    @Query("DELETE FROM app_metadata")
    suspend fun clearAll()
}
