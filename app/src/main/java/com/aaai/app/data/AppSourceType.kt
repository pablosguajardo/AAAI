package com.aaai.app.data

enum class AppSourceType {
    /** APK downloaded directly from downloadUrl. */
    FILE,

    /** Versions listed from the GitHub Releases API (githubRepo). */
    GITHUB_RELEASE;

    companion object {
        fun fromString(value: String?): AppSourceType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FILE
    }
}
