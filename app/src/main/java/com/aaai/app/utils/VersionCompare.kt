package com.aaai.app.utils

/**
 * Version ordering following Semantic Versioning precedence (semver.org): the numeric core decides, a
 * pre-release ("-beta.1") ranks below its final version, build metadata ("+abc") is ignored. Publication
 * dates are NOT used here; callers use them only as a tie-breaker.
 *
 * Tolerant of real-world tags: leading "v", 1-4 numeric parts, free-text suffixes.
 */
object VersionCompare {

    private class Parsed(val core: List<Int>, val pre: List<String>)

    private fun parse(raw: String?): Parsed? {
        val s = raw?.trim()?.substringBefore('+') ?: return null
        val m = Regex("\\d+(?:\\.\\d+)*").find(s) ?: return null
        val core = m.value.split('.').map { it.toIntOrNull() ?: 0 }
        val rest = s.substring(m.range.last + 1).trimStart('-', '_', '.')
        val pre = if (rest.isEmpty()) emptyList() else rest.split('.', '-')
        return Parsed(core, pre)
    }

    /** True when [version] contains at least one number. */
    fun hasNumber(version: String?): Boolean = parse(version) != null

    /** True when the tag/version carries a pre-release part ("1.0.0-beta"). */
    fun isPreRelease(version: String?): Boolean = parse(version)?.pre?.isNotEmpty() == true

    /**
     * Compares two versions: positive if [a] is newer than [b]. Returns null if either has no number.
     * With [ignorePreRelease] only the numeric core is compared (for an APK versionName such as
     * "0.84.900-morckx", where the suffix is a build label, not a pre-release marker).
     */
    fun compare(a: String?, b: String?, ignorePreRelease: Boolean = false): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until maxOf(x.core.size, y.core.size)) {
            val c = x.core.getOrElse(i) { 0 }.compareTo(y.core.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        if (ignorePreRelease) return 0
        // Same core: a final version beats any pre-release
        if (x.pre.isEmpty() && y.pre.isEmpty()) return 0
        if (x.pre.isEmpty()) return 1
        if (y.pre.isEmpty()) return -1
        for (i in 0 until maxOf(x.pre.size, y.pre.size)) {
            val p = x.pre.getOrNull(i) ?: return -1
            val q = y.pre.getOrNull(i) ?: return 1
            val pn = p.toIntOrNull()
            val qn = q.toIntOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1 // numeric identifiers rank below alphanumeric ones
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return 0
    }
}
