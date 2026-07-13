package ab.inque.converter.pipeline

import ab.inque.converter.FileFormat
import ab.inque.converter.MediaToolOptions
import ab.inque.converter.QualityPreset
import ab.inque.converter.UniversalConverter
import android.content.Context
import java.io.File

/**
 * Executes a multi-hop conversion plan by chaining [UniversalConverter.convertFile] calls.
 */
object ConversionPipeline {

    fun execute(
        converter: UniversalConverter,
        sourceFile: File,
        path: List<FileFormat>,
        context: Context,
        progressCallback: (Float) -> Unit,
        scale: Float?,
        quality: QualityPreset,
        toolOptions: MediaToolOptions
    ): File {
        require(path.size >= 2) { "Path must include source and target" }
        var current = sourceFile
        val hops = path.size - 1
        for (i in 0 until hops) {
            val target = path[i + 1]
            val hopStart = i.toFloat() / hops
            val hopEnd = (i + 1).toFloat() / hops
            current = converter.convertFile(
                sourceFile = current,
                targetFormat = target,
                context = context,
                progressCallback = { p ->
                    progressCallback((hopStart + p * (hopEnd - hopStart)).coerceIn(0f, 1f))
                },
                scale = scale,
                quality = quality,
                toolOptions = toolOptions,
                allowMultiHop = false
            )
        }
        progressCallback(1f)
        return current
    }
}
