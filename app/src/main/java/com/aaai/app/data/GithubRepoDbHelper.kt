package com.aaai.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Opens the pre-built github_repos.db from assets as a read-only SQLiteDatabase.
 *
 * The DB is copied to filesDir on first use (or when the asset has a newer user_version).
 * All queries run on the calling thread — always wrap calls in withContext(Dispatchers.IO).
 */
class GithubRepoDbHelper(private val context: Context) {

    companion object {
        private const val TAG = "com.aaai.app-GithubRepoDbHelper"
        private const val ASSET_NAME = "github_repos.db"
        private const val PAGE_SIZE = 20
    }

    private fun dbFile(): File = File(context.filesDir, ASSET_NAME)

    fun isAvailable(): Boolean = try {
        context.assets.list("")?.contains(ASSET_NAME) == true
    } catch (e: Exception) { false }

    fun openReadable(): SQLiteDatabase? {
        if (!isAvailable()) {
            Log.w(TAG, "github_repos.db not found in assets")
            return null
        }
        val dest = dbFile()
        try {
            val assetVersion = readAssetUserVersion()
            val installedVersion = if (dest.exists()) readFileUserVersion(dest) else -1
            if (installedVersion < assetVersion) {
                Log.d(TAG, "Copying github_repos.db from assets (v$assetVersion > v$installedVersion)")
                context.assets.open(ASSET_NAME).use { src ->
                    dest.outputStream().use { dst -> src.copyTo(dst) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy github_repos.db", e)
            return null
        }
        return try {
            SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open github_repos.db", e)
            null
        }
    }

    private fun readAssetUserVersion(): Int = try {
        val tmp = File(context.cacheDir, "github_repos_check.db")
        context.assets.open(ASSET_NAME).use { src -> tmp.outputStream().use { src.copyTo(it) } }
        val v = readFileUserVersion(tmp)
        tmp.delete()
        v
    } catch (e: Exception) { 0 }

    private fun readFileUserVersion(file: File): Int = try {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    } catch (e: Exception) { 0 }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Searches repos. group=null returns all groups.
     * is_verified repos are always returned when group="verified".
     */
    fun search(
        db: SQLiteDatabase,
        query: String,
        group: String?,
        topicId: Long?,
        sortBy: String = "stars",
        page: Int = 0
    ): List<GithubRepo> {
        val args = mutableListOf<String>()
        val where = buildString {
            append("1=1")
            if (query.isNotBlank()) {
                append(" AND (r.name LIKE ? OR r.app_name LIKE ? OR r.description LIKE ?)")
                val like = "%${query.trim()}%"
                args += like; args += like; args += like
            }
            if (group != null) {
                append(" AND r.group_name = ?")
                args += group
            }
            if (topicId != null) {
                append(" AND EXISTS (SELECT 1 FROM repo_topics rt WHERE rt.repo_id=r.id AND rt.topic_id=?)")
                args += topicId.toString()
            }
        }
        // Verified always first, then by stars or date
        val orderBy = when {
            sortBy == "newest" -> "r.is_verified DESC, r.pushed_at DESC"
            else               -> "r.is_verified DESC, r.stars DESC"
        }
        val offset = page * PAGE_SIZE
        val sql = """
            SELECT r.id, r.full_name, r.owner, r.name, r.description, r.stars,
                   r.package_name, r.avatar_url, r.last_tag, r.group_name, r.pushed_at,
                   r.source_type, r.download_url, r.app_action, r.app_name, r.is_verified
            FROM repos r
            WHERE $where
            ORDER BY $orderBy
            LIMIT $PAGE_SIZE OFFSET $offset
        """.trimIndent()

        val result = mutableListOf<GithubRepo>()
        db.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val repoId = c.getLong(0)
                result += GithubRepo(
                    id          = repoId,
                    fullName    = c.getString(1),
                    owner       = c.getString(2),
                    name        = c.getString(3),
                    description = c.getString(4),
                    stars       = c.getInt(5),
                    packageName = c.getString(6) ?: "",
                    avatarUrl   = c.getString(7),
                    lastTag     = c.getString(8),
                    groupName   = c.getString(9),
                    pushedAt    = c.getString(10),
                    topics      = loadTopicsForRepo(db, repoId),
                    sourceType  = c.getString(11) ?: "github",
                    downloadUrl = c.getString(12),
                    appAction   = c.getString(13),
                    appName     = c.getString(14),
                    isVerified  = c.getInt(15) == 1
                )
            }
        }
        return result
    }

    private fun loadTopicsForRepo(db: SQLiteDatabase, repoId: Long): List<String> {
        val topics = mutableListOf<String>()
        db.rawQuery(
            """SELECT t.name FROM topics t
               JOIN repo_topics rt ON rt.topic_id = t.id
               WHERE rt.repo_id = ? ORDER BY t.name""",
            arrayOf(repoId.toString())
        ).use { c -> while (c.moveToNext()) topics += c.getString(0) }
        return topics
    }

    /**
     * Returns topics filtered by group, minimum count, and optionally only verified repos.
     */
    fun allTopics(
        db: SQLiteDatabase,
        group: String? = null,
        minCount: Int = 1
    ): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        if (group == null) {
            db.rawQuery("SELECT id, name FROM topics ORDER BY name", null).use { c ->
                while (c.moveToNext()) result += c.getLong(0) to c.getString(1)
            }
            Log.d(TAG, "allTopics(null): ${result.size} topics total")
        } else {
            // Debug: check how many repos exist for this group
            val repoCount = db.rawQuery("SELECT COUNT(*) FROM repos WHERE group_name=?",
                arrayOf(group)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val topicCount = db.rawQuery("SELECT COUNT(*) FROM topics", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0 }
            val rtCount = db.rawQuery("SELECT COUNT(*) FROM repo_topics", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0 }
            // Sample: check if any repo_topics row has a repo_id that actually exists
            val matchCount = db.rawQuery(
                "SELECT COUNT(*) FROM repo_topics rt JOIN repos r ON r.id=rt.repo_id WHERE r.group_name=?",
                arrayOf(group)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            Log.d(TAG, "allTopics(group='$group', minCount=$minCount): " +
                "repos_in_group=$repoCount, total_topics=$topicCount, " +
                "repo_topics_total=$rtCount, repo_topics_matching_group=$matchCount")

            // Use COUNT() directly in HAVING — alias in HAVING can fail with bound params in Android SQLite
            val sql = """
                SELECT t.id, t.name
                FROM topics t
                JOIN repo_topics rt ON rt.topic_id = t.id
                JOIN repos r ON r.id = rt.repo_id
                WHERE r.group_name = ?
                GROUP BY t.id, t.name
                HAVING COUNT(r.id) >= $minCount
                ORDER BY t.name
            """.trimIndent()
            db.rawQuery(sql, arrayOf(group)).use { c ->
                while (c.moveToNext()) result += c.getLong(0) to c.getString(1)
            }
            Log.d(TAG, "allTopics(group='$group'): ${result.size} topics with cnt>=$minCount")
        }
        return result
    }

    /**
     * Every repo that has a package name (no paging, no topics). Used by the Android Auto
     * update notifier so it sees exactly the same apps the main screen shows.
     */
    fun allWithPackage(db: SQLiteDatabase): List<GithubRepo> {
        val sql = """
            SELECT r.id, r.full_name, r.owner, r.name, r.package_name, r.last_tag,
                   r.source_type, r.app_name
            FROM repos r
            WHERE r.package_name IS NOT NULL AND r.package_name != ''
        """.trimIndent()
        val result = mutableListOf<GithubRepo>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                result += GithubRepo(
                    id          = c.getLong(0),
                    fullName    = c.getString(1),
                    owner       = c.getString(2),
                    name        = c.getString(3),
                    description = null,
                    stars       = 0,
                    packageName = c.getString(4) ?: "",
                    avatarUrl   = null,
                    lastTag     = c.getString(5),
                    groupName   = null,
                    pushedAt    = null,
                    topics      = emptyList(),
                    sourceType  = c.getString(6) ?: "github",
                    appName     = c.getString(7)
                )
            }
        }
        return result
    }

    fun count(
        db: SQLiteDatabase,
        query: String,
        group: String?,
        topicId: Long?
    ): Int {
        val args = mutableListOf<String>()
        val where = buildString {
            append("1=1")
            if (query.isNotBlank()) {
                append(" AND (r.name LIKE ? OR r.app_name LIKE ? OR r.description LIKE ?)")
                val like = "%${query.trim()}%"
                args += like; args += like; args += like
            }
            if (group != null) {
                append(" AND r.group_name = ?")
                args += group
            }
            if (topicId != null) {
                append(" AND EXISTS (SELECT 1 FROM repo_topics rt WHERE rt.repo_id=r.id AND rt.topic_id=?)")
                args += topicId.toString()
            }
        }
        return db.rawQuery("SELECT COUNT(*) FROM repos r WHERE $where",
            args.toTypedArray()).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
