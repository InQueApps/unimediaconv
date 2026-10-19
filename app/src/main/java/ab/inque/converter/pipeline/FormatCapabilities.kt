package ab.inque.converter.pipeline

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer

/**
 * Device-dependent conversion capabilities used to filter reachable targets in the UI.
 */
data class FormatCapabilities(
    val canEncodeHeic: Boolean,
    val canEncodeOgg: Boolean,
    val canEncodeFlac: Boolean,
    val canTranscribe: Boolean,
    val canOcr: Boolean
) {
    companion object {
        fun from(context: Context): FormatCapabilities {
            val sdk = Build.VERSION.SDK_INT
            val canStt = sdk >= Build.VERSION_CODES.TIRAMISU &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            return FormatCapabilities(
                canEncodeHeic = sdk >= Build.VERSION_CODES.P,
                canEncodeOgg = sdk >= Build.VERSION_CODES.Q,
                canEncodeFlac = sdk >= Build.VERSION_CODES.Q,
                canTranscribe = canStt,
                canOcr = true
            )
        }

        /** Used in unit tests without a Context. */
        val ALL = FormatCapabilities(
            canEncodeHeic = true,
            canEncodeOgg = true,
            canEncodeFlac = true,
            canTranscribe = true,
            canOcr = true
        )
    }
}
