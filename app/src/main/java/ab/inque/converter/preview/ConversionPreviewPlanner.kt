package ab.inque.converter.preview

import ab.inque.converter.FileFormat
import ab.inque.converter.FormatCategory
import ab.inque.converter.MediaToolOptions

/**
 * Chooses a fast preview strategy for a source→target pair (no full conversion).
 */
object ConversionPreviewPlanner {

    fun plan(
        source: FileFormat,
        target: FileFormat,
        toolOptions: MediaToolOptions,
        durationMs: Long = 0L
    ): PreviewPlan {
        val subtitle = "${source.displayName} → ${target.displayName}"
        val formatDisclaimer = isContainerOnlyChange(source, target)

        return when (target.category) {
            FormatCategory.VIDEO, FormatCategory.AUDIO -> planMediaOutput(
                source, target, toolOptions, subtitle, formatDisclaimer
            )
            FormatCategory.IMAGE -> planImageOutput(
                source, toolOptions, subtitle, formatDisclaimer
            )
            FormatCategory.DOCUMENT -> planDocumentOutput(
                source, target, toolOptions, subtitle, formatDisclaimer
            )
        }
    }

    fun mediaWindow(toolOptions: MediaToolOptions, durationMs: Long): Pair<Long, Long> {
        val start = if (toolOptions.hasTrim) toolOptions.trimStartMs else 0L
        val end = when {
            toolOptions.hasTrim -> toolOptions.trimEndMs.coerceAtLeast(start + 1)
            durationMs > 0 -> durationMs
            else -> Long.MAX_VALUE
        }
        return start to end
    }

    private fun planMediaOutput(
        source: FileFormat,
        target: FileFormat,
        toolOptions: MediaToolOptions,
        subtitle: String,
        formatDisclaimer: Boolean
    ): PreviewPlan {
        val disclaimer = disclaimerKey(formatDisclaimer, null)
        return when (source.category) {
            FormatCategory.VIDEO, FormatCategory.AUDIO -> PreviewPlan(
                strategy = PreviewStrategy.MEDIA_CLIP,
                subtitle = subtitle,
                disclaimer = disclaimer,
                audioOnly = target.category == FormatCategory.AUDIO,
                formatDisclaimer = formatDisclaimer
            )
            FormatCategory.IMAGE -> PreviewPlan(
                strategy = PreviewStrategy.SLIDESHOW_HINT,
                subtitle = subtitle,
                disclaimer = "slideshow"
            )
            FormatCategory.DOCUMENT -> PreviewPlan(
                strategy = PreviewStrategy.PAGED_DOCUMENT,
                subtitle = subtitle,
                disclaimer = disclaimer
            )
            else -> PreviewPlan(PreviewStrategy.UNAVAILABLE, subtitle)
        }
    }

    private fun planImageOutput(
        source: FileFormat,
        toolOptions: MediaToolOptions,
        subtitle: String,
        formatDisclaimer: Boolean
    ): PreviewPlan {
        val disclaimer = when {
            toolOptions.exportAllVideoFrames -> "sample"
            formatDisclaimer -> "format_disclaimer"
            else -> null
        }
        val strategy = when (source.category) {
            FormatCategory.IMAGE -> PreviewStrategy.STILL_IMAGE
            FormatCategory.VIDEO -> if (source == FileFormat.GIF) PreviewStrategy.STILL_IMAGE else PreviewStrategy.VIDEO_FRAME
            FormatCategory.DOCUMENT -> PreviewStrategy.PAGED_DOCUMENT
            FormatCategory.AUDIO -> PreviewStrategy.WAVEFORM_IMAGE
            else -> PreviewStrategy.UNAVAILABLE
        }
        return PreviewPlan(strategy, subtitle, disclaimer, formatDisclaimer = formatDisclaimer)
    }

    private fun planDocumentOutput(
        source: FileFormat,
        target: FileFormat,
        toolOptions: MediaToolOptions,
        subtitle: String,
        formatDisclaimer: Boolean
    ): PreviewPlan {
        val disclaimer = disclaimerKey(formatDisclaimer, null)
        val strategy = when {
            source.category == FormatCategory.VIDEO && toolOptions.hasExtractFrame && target == FileFormat.PDF ->
                PreviewStrategy.STILL_IMAGE
            source.category == FormatCategory.VIDEO && toolOptions.hasExtractFrame && target != FileFormat.PDF ->
                PreviewStrategy.OCR_TEXT
            source.category == FormatCategory.IMAGE && target != FileFormat.PDF ->
                PreviewStrategy.OCR_TEXT
            source.category == FormatCategory.VIDEO && target != FileFormat.PDF ->
                PreviewStrategy.TEXT_SAMPLE
            source.category == FormatCategory.AUDIO ->
                PreviewStrategy.TEXT_SAMPLE
            target == FileFormat.HTML || source == FileFormat.HTML ->
                PreviewStrategy.HTML_WEB
            target == FileFormat.PDF && source.category == FormatCategory.IMAGE ->
                PreviewStrategy.STILL_IMAGE
            source == FileFormat.PDF && target != FileFormat.PDF && target.category == FormatCategory.DOCUMENT ->
                PreviewStrategy.TEXT_SAMPLE
            source == FileFormat.PDF && target == FileFormat.PDF ->
                PreviewStrategy.PAGED_DOCUMENT
            source == FileFormat.PDF || target == FileFormat.PDF ->
                PreviewStrategy.PAGED_DOCUMENT
            else ->
                PreviewStrategy.TEXT_SAMPLE
        }
        return PreviewPlan(
            strategy = strategy,
            subtitle = subtitle,
            disclaimer = when (strategy) {
                PreviewStrategy.OCR_TEXT -> "ocr"
                PreviewStrategy.TEXT_SAMPLE -> "sample"
                else -> disclaimer
            },
            formatDisclaimer = formatDisclaimer
        )
    }

    private fun disclaimerKey(formatDisclaimer: Boolean, extra: String?): String? = when {
        extra != null -> extra
        formatDisclaimer -> "format_disclaimer"
        else -> null
    }

    private fun isContainerOnlyChange(source: FileFormat, target: FileFormat): Boolean =
        (source.category == FormatCategory.VIDEO && target.category == FormatCategory.VIDEO && source != target) ||
            (source.category == FormatCategory.AUDIO && target.category == FormatCategory.AUDIO && source != target)
}
