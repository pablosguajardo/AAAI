package com.aaai.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Catalog entry. Fields read from apps_data.json: appId, appName, packageName, description,
 * iconUrl, sourceType, category, and then either
 *  - FILE:           version, versionCode, downloadUrl
 *  - GITHUB_RELEASE: githubRepo (versions are fetched from the GitHub API)
 *
 * isInstalled / installedVersion / hasUpdate / downloadState are runtime state computed by the app.
 */
@Entity(tableName = "app_metadata")
data class AppMetadata(
    @PrimaryKey
    val appId: String,
    val appName: String,
    val packageName: String,
    /** Only meaningful for FILE apps. Empty for GITHUB_RELEASE. */
    val version: String,
    /** Only meaningful for FILE apps (used to detect updates). 0 for GITHUB_RELEASE. */
    val versionCode: Int,
    val description: String,
    val iconUrl: String,
    /** Only for FILE apps. Empty for GITHUB_RELEASE. */
    val downloadUrl: String,
    val sourceType: AppSourceType,
    val isInstalled: Boolean = false,
    /** Version that is installed on the device (GitHub tag for GITHUB_RELEASE apps). */
    val installedVersion: String? = null,
    val hasUpdate: Boolean = false,
    val category: AppCategory,
    val githubRepo: String? = null,
    val downloadState: DownloadState = DownloadState.IDLE
)
