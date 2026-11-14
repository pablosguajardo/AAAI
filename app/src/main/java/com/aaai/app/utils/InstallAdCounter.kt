package com.aaai.app.utils

import android.content.Context

/**
 * Counts SUCCESSFUL installs and decides when a full-screen ad is due.
 *
 * Flow:
 *  1. When the user taps Install/Update, call [shouldShowAdNow]. It only checks, it does not count.
 *     It is true when the number of successful installs so far is a multiple of [INSTALLS_PER_AD]
 *     (e.g. 3 = after the 3rd, 6th, 9th... install, so the ad appears on the next tap)
 *     and the ad was not already shown for that count.
 *  2. If the ad is shown, call [markAdShown] so cancelling and tapping again does not repeat it.
 *  3. Only when the install finishes OK, call [registerSuccessfulInstall].
 *
 * The counter lives in its own SharedPreferences file ([PREFS_NAME]). That file is the only one
 * included in the Android backup rules (see res/xml/backup_rules.xml and data_extraction_rules.xml),
 * so it can be restored if the app is reinstalled. Restoring is best-effort and not guaranteed.
 */
object InstallAdCounter {

    /** Show an ad before every this many successful installs. Use 0 to disable the ads. */
    const val INSTALLS_PER_AD = 3

    const val PREFS_NAME = "aaai_install_counter"
    private const val KEY_COUNT = "install_count"
    private const val KEY_AD_SHOWN_FOR = "ad_shown_for_count"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True if enough installs were completed since the last ad. Does not count anything. */
    fun shouldShowAdNow(context: Context): Boolean {
        if (INSTALLS_PER_AD <= 0) return false
        val p = prefs(context)
        val count = p.getInt(KEY_COUNT, 0)
        val alreadyShown = p.getInt(KEY_AD_SHOWN_FOR, -1) == count
        return count > 0 && count % INSTALLS_PER_AD == 0 && !alreadyShown
    }

    /** Remembers that the ad for the current pending install was already shown. */
    fun markAdShown(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_AD_SHOWN_FOR, p.getInt(KEY_COUNT, 0)).apply()
    }

    /** Call only when an install finished successfully. */
    fun registerSuccessfulInstall(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1).apply()
    }
}
