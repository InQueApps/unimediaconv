package ab.inque.converter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Extract-frame routing: video → image/PDF uses timestamp, not full clip.
 */
@RunWith(AndroidJUnit4::class)
class ExtractFrameTest {

    @Test
    fun mp4ToJpg_extractFrameAtStart_producesImage() {
        val source = ConversionTestHelper.copyTestMp4()
        val tools = MediaToolOptions(extractFrame = true, extractFrameMs = 0L)
        val output = ConversionTestHelper.convert(source, FileFormat.JPG, toolOptions = tools)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "jpg")
        val (w, h) = ConversionTestHelper.decodeBounds(output)
        assertTrue(w > 0 && h > 0)
        source.delete()
        output.delete()
    }

    @Test
    fun mp4ToPdf_extractFrame_producesSinglePagePdf() {
        val source = ConversionTestHelper.copyTestMp4()
        val tools = MediaToolOptions(extractFrame = true, extractFrameMs = 500L)
        val output = ConversionTestHelper.convert(source, FileFormat.PDF, toolOptions = tools)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "pdf")
        assertTrue("PDF should be smaller than multi-frame export", output.length() < 500_000)
        source.delete()
        output.delete()
    }

    @Test
    fun mp4ToTxt_extractFrame_producesDocument() {
        val source = ConversionTestHelper.copyTestMp4()
        val tools = MediaToolOptions(extractFrame = true, extractFrameMs = 0L)
        val output = ConversionTestHelper.convert(source, FileFormat.TXT, toolOptions = tools)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "txt")
        source.delete()
        output.delete()
    }
}
