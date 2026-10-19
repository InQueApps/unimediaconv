package ab.inque.converter

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists premium status and free-user token (points) balance.
 * Stored in SharedPreferences; included in Android Auto Backup (backup_rules.xml)
 * so reinstalling + restoring from Google backup restores both premium and points.
 */
private const val PREFS_NAME = "conversion_prefs"
private const val KEY_POINTS = "points"
private const val KEY_CONVERSIONS_LEFT = "conversions_left" // migration: used as initial points if KEY_POINTS missing
private const val KEY_UNLOCKED = "unlocked"
private const val KEY_OVERRIDE_REGULAR = "debug_override_regular" // when true, force free tier (debug toggle or promo off)
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
            p.edit().putInt(KEY_POINTS, migrated).commit()
            return migrated
        }
        return p.getInt(KEY_POINTS, DEFAULT_POINTS)
    }

    /**
     * Premium / unlimited conversions.
     * - Debug builds: unlocked unless [KEY_OVERRIDE_REGULAR] (bottom "Toggle premium" control).
     * - Release builds: unlocked when [BuildConfig.PREMIUM_ENABLED_BY_DEFAULT] or purchased ([KEY_UNLOCKED]).
     *   Set PREMIUM_ENABLED_BY_DEFAULT to false in app/build.gradle.kts to require purchase.
     */
    fun isUnlocked(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(KEY_OVERRIDE_REGULAR, false)) return false
        return BuildConfig.DEBUG ||
            BuildConfig.PREMIUM_ENABLED_BY_DEFAULT ||
            p.getBoolean(KEY_UNLOCKED, false)
    }

    /** Spend points (e.g. 1 per file converted). No-op if unlocked. Persisted so tokens survive reinstall when backup restores. */
    fun spendPoints(context: Context, amount: Int) {
        if (isUnlocked(context)) return
        val current = getPoints(context)
        prefs(context).edit().putInt(KEY_POINTS, (current - amount).coerceAtLeast(0)).commit()
    }

    /** Add points (e.g. after watching a rewarded ad). No-op if unlocked. Persisted so tokens survive reinstall when backup restores. */
    fun addPoints(context: Context, count: Int) {
        if (isUnlocked(context)) return
        val current = getPoints(context)
        prefs(context).edit().putInt(KEY_POINTS, current + count).commit()
    }

    fun setUnlocked(context: Context, unlocked: Boolean) {
        val canOverrideToRegular = BuildConfig.DEBUG || BuildConfig.PREMIUM_ENABLED_BY_DEFAULT
        prefs(context).edit()
            .putBoolean(KEY_UNLOCKED, unlocked)
            .putBoolean(KEY_OVERRIDE_REGULAR, canOverrideToRegular && !unlocked)
            .commit()
    }

    private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
    private const val DEFAULT_TTS_SPEECH_RATE = 1.0f

    /** TTS speech rate (0.5–2.0). Applied when reading documents aloud. */
    fun getTtsSpeechRate(context: Context): Float =
        prefs(context).getFloat(KEY_TTS_SPEECH_RATE, DEFAULT_TTS_SPEECH_RATE).coerceIn(0.5f, 2.0f)

    fun setTtsSpeechRate(context: Context, rate: Float) {
        prefs(context).edit()
            .putFloat(KEY_TTS_SPEECH_RATE, rate.coerceIn(0.5f, 2.0f))
            .commit()
    }

    private const val KEY_TTS_VOICE_NAME = "tts_voice_name"

    /** Saved [android.speech.tts.Voice.name]; null = system default. */
    fun getTtsVoiceName(context: Context): String? =
        prefs(context).getString(KEY_TTS_VOICE_NAME, null)?.takeIf { it.isNotBlank() }

    fun setTtsVoiceName(context: Context, voiceName: String?) {
        prefs(context).edit()
            .putString(KEY_TTS_VOICE_NAME, voiceName?.takeIf { it.isNotBlank() })
            .commit()
    }

    private const val KEY_SPEECH_LOCALE_TAG = "speech_locale_tag"

    /** BCP-47 language tag (e.g. "en-US") for speech recognition and synthesis; null = system default. */
    fun getSpeechLocaleTag(context: Context): String? =
        prefs(context).getString(KEY_SPEECH_LOCALE_TAG, null)?.takeIf { it.isNotBlank() }

    fun setSpeechLocaleTag(context: Context, localeTag: String?) {
        prefs(context).edit()
            .putString(KEY_SPEECH_LOCALE_TAG, localeTag?.takeIf { it.isNotBlank() })
            .commit()
    }

    fun getSttTtsLocale(context: Context): String? = getSpeechLocaleTag(context)

    fun setSttTtsLocale(context: Context, languageTag: String?) = setSpeechLocaleTag(context, languageTag)
}
