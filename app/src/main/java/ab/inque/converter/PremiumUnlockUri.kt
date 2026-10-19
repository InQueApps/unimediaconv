package ab.inque.converter

import android.net.Uri

/**
 * Unlock code that can unlock premium without a store purchase (e.g. promo, support).
 * Pasting the code on the upgrade screen, or opening the deep link, unlocks premium.
 */
object PremiumUnlockUri {
    /** The unlock code. Pasting this exact string on the upgrade screen unlocks premium. */
    const val UNLOCK_CODE = "FRIENDSOFMRROMAN+"

    private val deepLinkUri = Uri.parse("umc://unlock/$UNLOCK_CODE")

    /** Returns true if the given string is the valid unlock code or the unlock deep link. */
    fun isValidUnlockUri(input: String?): Boolean {
        if (input.isNullOrBlank()) return false
        val trimmed = input.trim()
        if (trimmed == UNLOCK_CODE) return true
        return try {
            val u = Uri.parse(trimmed)
            u.scheme.equals(deepLinkUri.scheme, ignoreCase = true) &&
                u.host.equals(deepLinkUri.host, ignoreCase = true) &&
                u.path?.trim('/').equals(deepLinkUri.path?.trim('/'), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}
