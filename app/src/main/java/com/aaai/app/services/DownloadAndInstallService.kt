package com.aaai.app.services

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.aaai.app.autoconv.AutoConverter
import com.aaai.app.autoconv.AutoMode
import com.aaai.app.data.AppMetadata
import com.aaai.app.utils.ApkPackageRenamer
import com.aaai.app.utils.ApkSignerV2
import com.aaai.app.utils.ApkStampInjector
import com.aaai.app.utils.ApkValidator
import com.aaai.app.utils.HttpStatusException
import com.aaai.app.utils.InstalledVersionTracker
import com.aaai.app.utils.NetworkErrors
import com.aaai.app.utils.PackageNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads an APK (catalog, custom URL or local file), renames its package, signs it
 * and hands it back so the Activity can launch the system installer.
 */
class DownloadAndInstallService(private val context: Context) {

    companion object {
        private const val TAG = "DownloadAndInstallService"

        /**
         * Set to true to always re-download APKs (useful for testing).
         * Set to false (default) to reuse cached APKs if they exist.
         */
        const val FORCE_REDOWNLOAD = false

        private const val CUSTOM_URL_KEY = "custom_url"
        private const val CUSTOM_FILE_KEY = "custom_file"
        private const val SELF_UPDATE_PREFIX = "aaai_self_update_"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /** Catalog flow: download (or reuse cache) and prepare. */
    suspend fun downloadAndInstall(
        app: AppMetadata,
        onProgress: (Int, String) -> Unit,
        onReadyToInstall: (File) -> Unit,  // called with the APK file ready to install
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val downloadedApk = File(context.cacheDir, "${app.appId}.apk")

        try {
            // 1. Download APK (or reuse cache)
            if (!FORCE_REDOWNLOAD && downloadedApk.exists() && downloadedApk.length() > 0) {
                Log.d(TAG, "Using cached APK: ${downloadedApk.name} (${downloadedApk.length()} bytes)")
                onProgress(85, "Using cached ${app.appName}...")
            } else {
                if (FORCE_REDOWNLOAD) Log.d(TAG, "Force re-download enabled")
                onProgress(0, "Downloading ${app.appName}...")
                downloadApk(app.downloadUrl, downloadedApk) { progress ->
                    onProgress(progress, "Downloading ${app.appName}... $progress%")
                }
                Log.d(TAG, "Downloaded: ${downloadedApk.length()} bytes")
            }

            val apkToInstall = prepareForInstall(
                downloadedApk, app.packageName, app.appId, onProgress, AutoMode.fromAction(app.action)
            )
            // FILE apps: remember the catalog version being installed (confirmed once the package is updated)
            if (app.version.isNotEmpty()) InstalledVersionTracker.setPending(context, app.appId, app.version)
            onProgress(95, "Ready to install")
            onReadyToInstall(apkToInstall)
        } catch (e: Exception) {
            Log.e(TAG, "Download/install error", e)
            onError("Could not download ${app.appName}. ${NetworkErrors.describe(e)}")
            if (FORCE_REDOWNLOAD) downloadedApk.delete()
        }
    }

    /** GitHub flow: install a specific release asset chosen by the user (cached per tag). */
    suspend fun installGithubRelease(
        app: AppMetadata,
        tag: String,
        assetUrl: String,
        onProgress: (Int, String) -> Unit,
        onReadyToInstall: (File) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val downloadedApk = File(context.cacheDir, "${app.appId}_gh_$safeTag.apk")
        try {
            if (downloadedApk.exists() && downloadedApk.length() > 0 &&
                ApkValidator.validate(context, downloadedApk).valid
            ) {
                onProgress(85, "Using cached ${app.appName} $tag...")
            } else {
                downloadedApk.delete()
                onProgress(0, "Downloading ${app.appName} $tag...")
                downloadApk(assetUrl, downloadedApk) { progress ->
                    onProgress(progress, "Downloading ${app.appName} $tag... $progress%")
                }
                val validation = ApkValidator.validate(context, downloadedApk)
                if (!validation.valid) {
                    downloadedApk.delete()
                    onError(validation.error ?: "The file is not a valid APK")
                    return@withContext
                }
            }

            val apkToInstall = prepareForInstall(
                downloadedApk, app.packageName, app.appId, onProgress, AutoMode.fromAction(app.action)
            )
            // Remember which version is being installed; confirmed once the package is updated
            InstalledVersionTracker.setPending(context, app.appId, tag)
            onProgress(95, "Ready to install")
            onReadyToInstall(apkToInstall)
        } catch (e: Exception) {
            Log.e(TAG, "GitHub release install error", e)
            onError("Could not download ${app.appName} $tag. ${NetworkErrors.describe(e)}")
        }
    }

    /**
     * Self-update flow: downloads the new AAAI APK and hands it over AS IS.
     * It is NOT renamed or re-signed: Android only accepts an update of the installed app
     * if the package name and signing key are the original ones.
     */
    suspend fun installSelfUpdate(
        tag: String,
        assetUrl: String,
        forceDownload: Boolean = false,
        onProgress: (Int, String) -> Unit,
        onReadyToInstall: (File) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val apk = selfUpdateFile(tag)
        try {
            if (forceDownload) apk.delete()
            if (apk.exists() && apk.length() > 0) {
                onProgress(90, "Using downloaded AAAI $tag...")
            } else {
                // Remove copies of other versions before downloading
                context.cacheDir.listFiles { f -> f.name.startsWith(SELF_UPDATE_PREFIX) }
                    ?.forEach { it.delete() }
                onProgress(0, "Downloading AAAI $tag...")
                downloadApk(assetUrl, apk) { progress ->
                    onProgress(progress, "Downloading AAAI $tag... $progress%")
                }
            }
            onProgress(90, "Validating APK...")
            val validation = ApkValidator.validate(context, apk)
            if (!validation.valid) {
                apk.delete()
                onError(validation.error ?: "The file is not a valid APK")
                return@withContext
            }
            if (validation.packageName != context.packageName) {
                apk.delete()
                onError("The downloaded file is not an AAAI update (package ${validation.packageName}).")
                return@withContext
            }
            onProgress(95, "Ready to install")
            onReadyToInstall(apk)
        } catch (e: Exception) {
            Log.e(TAG, "Self-update error", e)
            apk.delete()
            onError("Could not download the update. ${NetworkErrors.describe(e)}")
        }
    }

    private fun selfUpdateFile(tag: String): File {
        val safeTag = tag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(context.cacheDir, "$SELF_UPDATE_PREFIX$safeTag.apk")
    }

    /** True if the APK of this AAAI release was already downloaded. */
    fun hasSelfUpdateDownloaded(tag: String): Boolean =
        selfUpdateFile(tag).let { it.exists() && it.length() > 0 }

    /** Custom URL flow: download, verify it is really an APK, then prepare. */
    suspend fun installFromUrl(
        url: String,
        onProgress: (Int, String) -> Unit,
        onReadyToInstall: (File) -> Unit,
        onError: (String) -> Unit,
        autoMode: AutoMode = AutoMode.SIGN_ONLY
    ) = withContext(Dispatchers.IO) {
        val downloadedApk = File(context.cacheDir, "$CUSTOM_URL_KEY.apk")
        try {
            val trimmed = url.trim()
            if (!(trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true))) {
                onError("Invalid URL. It must start with http:// or https://")
                return@withContext
            }

            downloadedApk.delete()
            onProgress(0, "Downloading...")
            downloadApk(trimmed, downloadedApk, rejectTextContent = true) { progress ->
                onProgress(progress, "Downloading... $progress%")
            }

            onProgress(85, "Validating APK...")
            val validation = ApkValidator.validate(context, downloadedApk)
            if (!validation.valid) {
                onError(validation.error ?: "The file is not a valid APK")
                return@withContext
            }

            val apkToInstall = prepareForInstall(
                downloadedApk, validation.packageName!!, CUSTOM_URL_KEY, onProgress, autoMode
            )
            onProgress(95, "Ready to install")
            onReadyToInstall(apkToInstall)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid URL", e)
            onError("Invalid URL: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Custom URL install error", e)
            onError("Could not download the APK. ${NetworkErrors.describe(e)}")
        } finally {
            downloadedApk.delete()
        }
    }

    /** Local file flow: copy the picked document, verify it is really an APK, then prepare. */
    suspend fun installFromFile(
        uri: Uri,
        onProgress: (Int, String) -> Unit,
        onReadyToInstall: (File) -> Unit,
        onError: (String) -> Unit,
        autoMode: AutoMode = AutoMode.SIGN_ONLY
    ) = withContext(Dispatchers.IO) {
        val localApk = File(context.cacheDir, "$CUSTOM_FILE_KEY.apk")
        try {
            localApk.delete()
            onProgress(10, "Reading file...")
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                onError("Could not open the selected file")
                return@withContext
            }
            input.use { src -> FileOutputStream(localApk).use { dst -> src.copyTo(dst) } }

            onProgress(60, "Validating APK...")
            val validation = ApkValidator.validate(context, localApk)
            if (!validation.valid) {
                onError(validation.error ?: "The file is not a valid APK")
                return@withContext
            }

            val apkToInstall = prepareForInstall(
                localApk, validation.packageName!!, CUSTOM_FILE_KEY, onProgress, autoMode
            )
            onProgress(95, "Ready to install")
            onReadyToInstall(apkToInstall)
        } catch (e: Exception) {
            Log.e(TAG, "Local file install error", e)
            onError("Error: ${e.message}")
        } finally {
            localApk.delete()
        }
    }

    /**
     * Shared pipeline: rename package (ARSCLib), stamp (stub), sign (apksig v2+v3).
     * Always returns a dedicated "<key>_signed.apk" file, independent of [sourceApk],
     * so callers can safely delete their source file afterwards.
     */
    private fun prepareForInstall(
        sourceApk: File,
        originalPackage: String,
        key: String,
        onProgress: (Int, String) -> Unit,
        autoMode: AutoMode = AutoMode.SIGN_ONLY
    ): File {
        val autoApk = File(context.cacheDir, "${key}_auto.apk")
        val renamedApk = File(context.cacheDir, "${key}_renamed.apk")
        val stampedApk = File(context.cacheDir, "${key}_stamped.apk")
        val signedApk = File(context.cacheDir, "${key}_signed.apk")

        try {
            // Rename package using ARSCLib (falls back to manifest byte replacement)
            onProgress(75, "Preparing APK...")
            // Optional Android Auto conversion (GAME / NAVIGATION); SIGN_ONLY leaves the source untouched
            val workApk = if (autoMode == AutoMode.SIGN_ONLY) sourceApk else {
                onProgress(70, "Preparing for Android Auto (${autoMode.label})...")
                AutoConverter.convert(context, sourceApk, autoApk, autoMode)
                autoApk
            }
            val newPkg = generateNewPackageName(originalPackage)
            val renameOk = ApkPackageRenamer.renamePackage(context, workApk, renamedApk, originalPackage, newPkg)
            val apkAfterRename = if (renameOk) {
                val verifyPkg = try {
                    context.packageManager.getPackageArchiveInfo(renamedApk.absolutePath, 0)?.packageName ?: "unknown"
                } catch (e: Exception) { "error: ${e.message}" }
                Log.d(TAG, "Package renamed: $originalPackage -> $newPkg (actual: $verifyPkg)")
                renamedApk
            } else {
                Log.w(TAG, "Rename failed — using original package")
                workApk
            }

            // Play Store stamps (currently a copy stub, same as AAAD without apktool)
            onProgress(82, "Injecting Play Store stamps...")
            val stampResult = ApkStampInjector.injectPlayStoreStamps(apkAfterRename, stampedApk)
            val apkAfterStamp = if (stampResult.success && stampedApk.exists()) stampedApk else apkAfterRename

            // Sign a dedicated copy in place (apksig v2+v3, v1 disabled)
            onProgress(92, "Signing APK (v2+v3)...")
            signedApk.delete()
            apkAfterStamp.copyTo(signedApk, overwrite = true)
            val signResult = ApkSignerV2.signApk(signedApk, context)
            return if (signResult.success && signResult.signedApk != null) {
                Log.d(TAG, "APK signed OK [${signResult.method}]: ${signResult.signedApk.length()} bytes")
                signResult.signedApk
            } else {
                Log.w(TAG, "Signing failed — using unsigned copy")
                signedApk
            }
        } finally {
            renamedApk.delete()
            stampedApk.delete()
            autoApk.delete()
            // signedApk is kept until the system installs it
        }
    }

    private fun generateNewPackageName(original: String): String {
        // Reuses the renamed package already installed (fixed or old random name) so it updates in
        // place (avoids -103 duplicate permission error); otherwise uses the fixed "<original>.aaai".
        val newPkg = PackageNaming.nameForInstall(context, original)
        Log.d(TAG, "Package name for install: $original → $newPkg")
        return newPkg
    }

    fun clearPackageMapping(originalPackage: String) {
        val prefs = context.getSharedPreferences("pkg_mappings", Context.MODE_PRIVATE)
        prefs.edit().remove(originalPackage).apply()
        Log.d(TAG, "Cleared package mapping for: $originalPackage")
    }

    private fun downloadApk(
        url: String,
        dest: File,
        rejectTextContent: Boolean = false,
        onProgress: (Int) -> Unit
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, NetworkErrors.httpMessage(response.code))
            }

            val body = response.body ?: throw Exception("Empty response from server")

            if (rejectTextContent) {
                val type = body.contentType()?.toString()?.lowercase() ?: ""
                if (type.startsWith("text/") || type.contains("html") || type.contains("json")) {
                    throw Exception("The link does not point to an APK (server returned $type)")
                }
            }

            val contentLength = body.contentLength()
            var downloaded = 0L
            val buffer = ByteArray(8192)

            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        output.write(buffer, 0, n)
                        downloaded += n
                        if (contentLength > 0) {
                            onProgress(((downloaded * 85) / contentLength).toInt())
                        }
                    }
                }
            }
        }
    }

    /**
     * Builds an install Intent for use with startActivityForResult.
     */
    fun buildInstallIntent(apkFile: File): Intent {
        val authority = "${context.packageName}.fileProvider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            // FLAG_ACTIVITY_NEW_TASK is incompatible with startActivityForResult.
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
    }
}
