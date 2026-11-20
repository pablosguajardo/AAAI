package com.aaai.app.utils

import java.io.File

/**
 * Downloaded / prepared APK files live loose in the app's cache directory. This helper deletes only those
 * ".apk" files. GitHub release lists (.json), icon cache and app data are never touched.
 */
object ApkCache {

    data class Result(val files: Int, val bytes: Long)

    private fun apks(cacheDir: File): List<File> =
        cacheDir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) }?.toList() ?: emptyList()

    /** Number of files and total size of the APKs that [clearAll] would delete. */
    fun measure(cacheDir: File): Result {
        val list = apks(cacheDir)
        return Result(list.size, list.sumOf { it.length() })
    }

    fun clearAll(cacheDir: File): Result = delete(apks(cacheDir))

    /**
     * After an app was installed OK: removes its older downloads and the temporary files of the install.
     * Keeps the APK that was just installed ("<appId>.apk" for catalog files, "<appId>_gh_<tag>.apk" for [keepTag]).
     */
    fun cleanupAfterInstall(cacheDir: File, appId: String, keepTag: String?): Result {
        val keepGh = keepTag?.let { "${appId}_gh_${it.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk" }
        val temp = setOf("auto", "renamed", "stamped", "signed").map { "${appId}_$it.apk" }.toSet()
        val old = apks(cacheDir).filter { f ->
            val n = f.name
            when {
                n in temp -> true                                   // pipeline leftovers of this app
                n.startsWith("${appId}_gh_") -> n != keepGh         // other GitHub versions of this app
                else -> false                                       // "<appId>.apk" is the current one: keep
            }
        }
        return delete(old)
    }

    private fun delete(files: List<File>): Result {
        var count = 0
        var bytes = 0L
        for (f in files) {
            val size = f.length()
            if (f.delete()) { count++; bytes += size }
        }
        return Result(count, bytes)
    }

    fun format(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)
}
