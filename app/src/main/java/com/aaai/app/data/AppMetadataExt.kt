package com.aaai.app.data

/**
 * True only for apps whose versions come from the GitHub Releases API.
 * Any other sourceType (FILE, FIREBASE_STORAGE, DIRECT_URL...) installs from [AppMetadata.downloadUrl].
 */
fun AppMetadata.isGithubRelease(): Boolean =
    sourceType == AppSourceType.GITHUB_RELEASE && !githubRepo.isNullOrEmpty()
