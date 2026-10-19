package ab.inque.converter

import ab.inque.converter.preview.ConversionPreviewPlanner
import ab.inque.converter.preview.PreviewStrategy
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversionPreviewPlannerTest {

    @Test
    fun videoToMp4_usesMediaClip() {
        val plan = ConversionPreviewPlanner.plan(
            FileFormat.MP4, FileFormat.MKV, MediaToolOptions(), durationMs = 10_000L
        )
        assertEquals(PreviewStrategy.MEDIA_CLIP, plan.strategy)
    }

    @Test
    fun videoToJpg_usesVideoFrame() {
        val plan = ConversionPreviewPlanner.plan(
            FileFormat.MP4,
            FileFormat.JPG,
            MediaToolOptions(extractFrame = true, extractFrameMs = 2_000L),
            durationMs = 10_000L
        )
        assertEquals(PreviewStrategy.VIDEO_FRAME, plan.strategy)
    }

    @Test
    fun imageToPng_usesStillImage() {
        val plan = ConversionPreviewPlanner.plan(
            FileFormat.PNG, FileFormat.JPG, MediaToolOptions()
        )
        assertEquals(PreviewStrategy.STILL_IMAGE, plan.strategy)
    }

    @Test
    fun pdfToTxt_usesTextSample() {
        val plan = ConversionPreviewPlanner.plan(
            FileFormat.PDF, FileFormat.TXT, MediaToolOptions()
        )
        assertEquals(PreviewStrategy.TEXT_SAMPLE, plan.strategy)
    }

    @Test
    fun mediaWindow_respectsTrim() {
        val (start, end) = ConversionPreviewPlanner.mediaWindow(
            MediaToolOptions(trimStartMs = 1_000L, trimEndMs = 5_000L),
            durationMs = 10_000L
        )
        assertEquals(1_000L, start)
        assertEquals(5_000L, end)
    }

    @Test
    fun videoToTxt_extractFrame_usesOcrText() {
        val plan = ConversionPreviewPlanner.plan(
            FileFormat.MP4,
            FileFormat.TXT,
            MediaToolOptions(extractFrame = true, extractFrameMs = 1_000L),
            durationMs = 10_000L
        )
        assertEquals(PreviewStrategy.OCR_TEXT, plan.strategy)
    }

    @Test
    fun videoToPdf_extractFrame_usesStillImage() {
        val plan = ConversionPreviewPlanner.plan(
            FileFormat.MP4,
            FileFormat.PDF,
            MediaToolOptions(extractFrame = true, extractFrameMs = 1_000L),
            durationMs = 10_000L
        )
        assertEquals(PreviewStrategy.STILL_IMAGE, plan.strategy)
    }
}
