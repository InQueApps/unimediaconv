package ab.inque.converter.preview

import android.content.Context
import android.net.Uri
import ab.inque.converter.FileFormat
import ab.inque.converter.MediaToolOptions

object PreviewLoader {

    suspend fun load(
        context: Context,
        uri: Uri,
        fileName: String,
        sourceFormat: FileFormat,
        targetFormat: FileFormat,
        toolOptions: MediaToolOptions,
        scale: Float?,
        durationMs: Long,
        mimeType: String
    ): PreviewContent {
        val plan = ConversionPreviewPlanner.plan(sourceFormat, targetFormat, toolOptions, durationMs)
        val disclaimer = resolveDisclaimer(context, plan, targetFormat)

        return try {
            when (plan.strategy) {
                PreviewStrategy.MEDIA_CLIP -> {
                    val (start, end) = ConversionPreviewPlanner.mediaWindow(toolOptions, durationMs)
                    val endClamped = if (end == Long.MAX_VALUE && durationMs > 0) durationMs else end
                    PreviewContent.MediaClip(
                        uri = uri,
                        mimeType = mimeType,
                        startMs = start,
                        endMs = endClamped,
                        speed = if (toolOptions.hasSpeed) toolOptions.speedFactor else 1f,
                        audioOnly = plan.audioOnly,
                        subtitle = plan.subtitle,
                        disclaimer = disclaimer
                    )
                }
                PreviewStrategy.STILL_IMAGE, PreviewStrategy.SLIDESHOW_HINT -> {
                    val bmp = if (sourceFormat.category == ab.inque.converter.FormatCategory.VIDEO && sourceFormat != FileFormat.GIF) {
                        PreviewImageRenderer.renderVideoFrame(context, uri, fileName, toolOptions, durationMs)
                    } else {
                        PreviewImageRenderer.renderImage(context, uri, fileName, sourceFormat, toolOptions, scale)
                    }
                    PreviewContent.StillImage(
                        bitmaps = listOf(bmp),
                        subtitle = plan.subtitle,
                        disclaimer = disclaimer ?: if (plan.strategy == PreviewStrategy.SLIDESHOW_HINT) {
                            context.getString(ab.inque.converter.R.string.preview_slideshow_hint)
                        } else null
                    )
                }
                PreviewStrategy.VIDEO_FRAME -> {
                    val bmp = PreviewImageRenderer.renderVideoFrame(
                        context, uri, fileName, toolOptions, durationMs
                    )
                    PreviewContent.StillImage(listOf(bmp), plan.subtitle, disclaimer)
                }
                PreviewStrategy.WAVEFORM_IMAGE -> {
                    val bmp = PreviewImageRenderer.renderWaveform(context, uri, fileName)
                    PreviewContent.StillImage(listOf(bmp), plan.subtitle, disclaimer)
                }
                PreviewStrategy.PAGED_DOCUMENT -> {
                    val file = PreviewUriHelper.materializeIfNeeded(context, uri, fileName)
                    val pages = when (sourceFormat) {
                        FileFormat.PDF -> PreviewDocumentSampler.renderPdfPages(context, file)
                        else -> {
                            val bmp = PreviewImageRenderer.renderImage(
                                context, uri, fileName, sourceFormat, toolOptions, scale
                            )
                            listOf(bmp)
                        }
                    }
                    if (pages.size == 1) {
                        PreviewContent.StillImage(pages, plan.subtitle, disclaimer)
                    } else {
                        PreviewContent.PagedImages(pages, plan.subtitle, disclaimer)
                    }
                }
                PreviewStrategy.TEXT_SAMPLE -> {
                    val file = PreviewUriHelper.materializeIfNeeded(context, uri, fileName)
                    val body = if (sourceFormat.category == ab.inque.converter.FormatCategory.VIDEO ||
                        sourceFormat.category == ab.inque.converter.FormatCategory.AUDIO
                    ) {
                        context.getString(ab.inque.converter.R.string.preview_disclaimer_sample) +
                            "\n\n" + previewTranscriptionPlaceholder(sourceFormat)
                    } else {
                        PreviewDocumentSampler.sampleText(context, file, sourceFormat)
                    }
                    PreviewContent.TextSample(body, plan.subtitle, disclaimer)
                }
                PreviewStrategy.OCR_TEXT -> {
                    val text = PreviewDocumentSampler.ocrSample(context, uri, fileName)
                    PreviewContent.TextSample(text, plan.subtitle, disclaimer)
                }
                PreviewStrategy.HTML_WEB -> PreviewContent.HtmlPage(uri, plan.subtitle, disclaimer)
                PreviewStrategy.UNAVAILABLE -> PreviewContent.Unavailable(
                    context.getString(ab.inque.converter.R.string.preview_unavailable)
                )
            }
        } catch (e: Exception) {
            PreviewContent.Unavailable(
                context.getString(ab.inque.converter.R.string.preview_error) + " ${e.message}"
            )
        }
    }

    private fun resolveDisclaimer(
        context: Context,
        plan: PreviewPlan,
        target: FileFormat
    ): String? = when (plan.disclaimer) {
        "format_disclaimer" -> context.getString(
            ab.inque.converter.R.string.preview_disclaimer_format,
            target.displayName
        )
        "sample" -> context.getString(ab.inque.converter.R.string.preview_disclaimer_sample)
        "ocr" -> context.getString(ab.inque.converter.R.string.preview_disclaimer_ocr)
        else -> null
    }

    private fun previewTranscriptionPlaceholder(source: FileFormat): String =
        "[Transcription preview]\n\nFull ${source.displayName} will be transcribed on convert."
}
