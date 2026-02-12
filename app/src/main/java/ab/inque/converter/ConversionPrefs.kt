package ab.inque.converter

import android.content.Context
import android.content.SharedPreferences

private const val PREFS_NAME = "conversion_prefs"
private const val KEY_POINTS = "points"
private const val KEY_CONVERSIONS_LEFT = "conversions_left" // migration: used as initial points if KEY_POINTS missing
private const val KEY_UNLOCKED = "unlocked"
private const val DEFAULT_POINTS = 10

object ConversionPrefs {
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns current points balance, or -1 when unlocked (unlimited). */
    fun getPoints(context: Context): Int {
        if (isUnlocked(context)) return -1
        val p = prefs(context)
        if (!p.contains(KEY_POINTS)) {
            // Migrate: existing users keep their "conversions left" as points
            val migrated = p.getInt(KEY_CONVERSIONS_LEFT, DEFAULT_POINTS)
            p.edit().putInt(KEY_POINTS, migrated).apply()
            return migrated
        }
        return p.getInt(KEY_POINTS, DEFAULT_POINTS)
    }

    fun isUnlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNLOCKED, false)

    /** Spend points (e.g. 1 per file converted). No-op if unlocked. */
    fun spendPoints(context: Context, amount: Int) {
        if (isUnlocked(context)) return
        val current = getPoints(context)
        prefs(context).edit().putInt(KEY_POINTS, (current - amount).coerceAtLeast(0)).apply()
    }

    /** Add points (e.g. after watching a rewarded ad). No-op if unlocked. */
    fun addPoints(context: Context, count: Int) {
        if (isUnlocked(context)) return
        val current = getPoints(context)
        prefs(context).edit().putInt(KEY_POINTS, current + count).apply()
    }

    fun setUnlocked(context: Context, unlocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNLOCKED, unlocked).apply()
    }
}
