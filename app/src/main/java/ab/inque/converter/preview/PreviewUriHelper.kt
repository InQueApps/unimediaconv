package ab.inque.converter.preview

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object PreviewUriHelper {

    fun materializeToCache(context: Context, uri: Uri, displayName: String): File {
        val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val out = File(context.cacheDir, "preview_${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: throw java.io.IOException("Cannot read selected file")
        return out
    }

    fun materializeIfNeeded(context: Context, uri: Uri, displayName: String): File =
        when (uri.scheme) {
            "file" -> File(requireNotNull(uri.path))
            else -> materializeToCache(context, uri, displayName)
        }
}
