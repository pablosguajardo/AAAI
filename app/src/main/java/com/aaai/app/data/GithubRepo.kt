package com.aaai.app.data

/**
 * Represents one row from the exported github_repos.db.
 * NOT a Room entity — read with raw SQLiteDatabase.
 */
data class GithubRepo(
    val id: Long,
    val fullName: String,         // "owner/repo" or "file/appId"
    val owner: String,
    val name: String,
    val description: String?,
    val stars: Int,
    val packageName: String,
    val avatarUrl: String?,       // best icon: icon_url → avatar_url
    val lastTag: String?,
    val groupName: String?,       // "verified" | "android-auto" | "android"
    val pushedAt: String?,
    val topics: List<String>,
    // Extended fields (verified + unified list)
    val sourceType: String = "github",   // "github" | "file"
    val downloadUrl: String? = null,     // only for sourceType="file"
    val appAction: String? = null,       // "g"=game "n"=nav "w"=webview etc.
    val appName: String? = null,         // display name from catalog
    val isVerified: Boolean = false,
    // Runtime state (computed by the app, not stored in DB)
    val isInstalled: Boolean = false,
    val installedVersion: String? = null,
    val hasUpdate: Boolean = false,
    val signatureMismatch: Boolean = false
) {
    /** True when from android-auto group or verified catalog. */
    val isAndroidAuto: Boolean
        get() = groupName?.equals("android-auto", ignoreCase = true) == true ||
                (isVerified && sourceType == "github")

    /** Display name: catalog name takes priority for verified, else repo name. */
    val displayName: String
        get() = appName?.takeIf { it.isNotBlank() } ?: name

    /** True for apps with a static download URL (no GitHub releases). */
    val isFileSource: Boolean get() = sourceType == "file"

    /** Stable appId for caching/tracking purposes. */
    val appId: String
        get() = fullName.replace("/", "_").replace("[^A-Za-z0-9_]".toRegex(), "_")

    /** Default AutoMode based on group and action field. */
    fun defaultAutoMode(): com.aaai.app.autoconv.AutoMode =
        com.aaai.app.autoconv.AutoMode.fromAction(appAction)

    /** Converts to AppMetadata for the existing install pipeline. */
    fun toAppMetadata(actionOverride: String? = null): AppMetadata = AppMetadata(
        appId        = appId,
        appName      = displayName,
        packageName  = packageName,
        version      = lastTag ?: "",
        versionCode  = 0,
        description  = description ?: "",
        iconUrl      = avatarUrl ?: "",
        downloadUrl  = downloadUrl ?: "",
        sourceType   = if (isFileSource) AppSourceType.FILE else AppSourceType.GITHUB_RELEASE,
        isInstalled  = isInstalled,
        installedVersion = installedVersion,
        hasUpdate    = hasUpdate,
        category     = groupName ?: "OTHER",
        githubRepo   = if (isFileSource) null else fullName,
        action       = actionOverride ?: appAction,
        carUrl       = null,
        downloadState = DownloadState.IDLE,
        signatureMismatch = signatureMismatch
    )
}
