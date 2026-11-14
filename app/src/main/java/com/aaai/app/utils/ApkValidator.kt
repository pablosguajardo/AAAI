package com.aaai.app.utils

import android.content.Context
import android.util.Log
import com.reandroid.apk.ApkModule
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * Verifies that a file is a real, installable (single) APK and not some other kind of file.
 *
 * Checks:
 *  1. File exists and is not empty
 *  2. Starts with the ZIP local header magic ("PK\u0003\u0004")
 *  3. Opens as a ZIP and contains AndroidManifest.xml at the root
 *     (this rejects .xapk / .apks / .aab / .zip / .jar bundles)
 *  4. Contains at least one dex file (classes.dex) or resources.arsc
 *  5. The binary manifest can be parsed and yields a package name
 */
object ApkValidator {

    private const val TAG = "ApkValidator"

    data class Result(
        val valid: Boolean,
        val packageName: String? = null,
        val error: String? = null
    )

    fun validate(context: Context, file: File): Result {
        if (!file.exists() || file.length() == 0L) {
            return Result(false, error = "The file is empty or does not exist")
        }

        // 1. ZIP magic
        try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                val isZip = read == 4 &&
                        magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                        magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                if (!isZip) {
                    return Result(false, error = "It is not an APK (not a ZIP/APK file). Make sure the link points directly to an .apk file")
                }
            }
        } catch (e: Exception) {
            return Result(false, error = "Could not read the file: ${e.message}")
        }

        // 2-4. ZIP structure
        try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) {
                    val looksLikeBundle = zip.entries().asSequence().any {
                        it.name.endsWith(".apk", true) || it.name == "manifest.json" || it.name == "BundleConfig.pb"
                    }
                    return Result(
                        false,
                        error = if (looksLikeBundle) "Split/bundle packages (.xapk, .apks, .aab) are not supported. Use a single .apk"
                        else "It is not an APK (AndroidManifest.xml not found)"
                    )
                }
                val hasCode = zip.getEntry("classes.dex") != null || zip.getEntry("resources.arsc") != null
                if (!hasCode) {
                    return Result(false, error = "It is not a valid APK (no code or resources)")
                }
            }
        } catch (e: Exception) {
            return Result(false, error = "Corrupt or invalid APK: ${e.message}")
        }

        // 5. Package name
        var pkg: String? = null
        try {
            pkg = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
        } catch (e: Exception) {
            Log.w(TAG, "getPackageArchiveInfo failed: ${e.message}")
        }
        if (pkg.isNullOrEmpty()) {
            try {
                pkg = ApkModule.loadApkFile(file).androidManifest.packageName
            } catch (e: Throwable) {
                Log.w(TAG, "ARSCLib manifest parse failed: ${e.message}")
            }
        }
        if (pkg.isNullOrEmpty()) {
            return Result(false, error = "Could not read the package name from the APK")
        }

        Log.d(TAG, "Valid APK: $pkg (${file.length()} bytes)")
        return Result(true, packageName = pkg)
    }
}
