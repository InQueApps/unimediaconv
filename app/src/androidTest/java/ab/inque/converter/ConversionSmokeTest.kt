package ab.inque.converter

import ab.inque.converter.pipeline.FormatCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P0 conversion smoke tests — run on device/emulator via connectedDebugAndroidTest.
 */
@RunWith(AndroidJUnit4::class)
class ConversionSmokeTest {

    @Test
    fun txtToPdf_producesValidPdf() {
        val source = ConversionTestHelper.copyAssetToCache(
            "conversions/sample.txt",
            "smoke_txt_${System.currentTimeMillis()}.txt"
        )
        val output = ConversionTestHelper.convert(source, FileFormat.PDF)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "pdf")
        source.delete()
        output.delete()
    }

    @Test
    fun htmlToPdf_producesValidPdf() {
        val source = ConversionTestHelper.copyAssetToCache(
            "conversions/sample.html",
            "smoke_html_${System.currentTimeMillis()}.html"
        )
        val output = ConversionTestHelper.convert(source, FileFormat.PDF)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "pdf")
        source.delete()
        output.delete()
    }

    @Test
    fun htmlToRtf_producesRtf() {
        val source = ConversionTestHelper.copyAssetToCache(
            "conversions/sample.html",
            "smoke_html_rtf_${System.currentTimeMillis()}.html"
        )
        val output = ConversionTestHelper.convert(source, FileFormat.RTF)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "rtf")
        source.delete()
        output.delete()
    }

    @Test
    fun pngToPdf_producesPdf() {
        val source = File(
            ConversionTestHelper.appContext().cacheDir,
            "smoke_png_${System.currentTimeMillis()}.png"
        )
        ConversionTestHelper.writeMinimalPng(source)
        val output = ConversionTestHelper.convert(source, FileFormat.PDF)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "pdf")
        source.delete()
        output.delete()
    }

    @Test
    fun txtToDocx_producesDocx() {
        val source = ConversionTestHelper.copyAssetToCache(
            "conversions/sample.txt",
            "smoke_txt_docx_${System.currentTimeMillis()}.txt"
        )
        val output = ConversionTestHelper.convert(source, FileFormat.DOCX)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "docx")
        source.delete()
        output.delete()
    }

    @Test
    fun rtfToPdf_producesPdf() {
        val source = ConversionTestHelper.copyAssetToCache(
            "conversions/sample.rtf",
            "smoke_rtf_${System.currentTimeMillis()}.rtf"
        )
        val output = ConversionTestHelper.convert(source, FileFormat.PDF)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "pdf")
        source.delete()
        output.delete()
    }

    @Test
    fun getSupportedConversions_txt_includesPdf() {
        val caps = FormatCapabilities.from(ConversionTestHelper.appContext())
        val targets = FileFormat.getSupportedConversions(FileFormat.TXT, caps)
        assertTrue(FileFormat.PDF in targets)
    }

    @Test
    fun getSupportedConversions_mp4_includesMkv() {
        val caps = FormatCapabilities.from(ConversionTestHelper.appContext())
        val targets = FileFormat.getSupportedConversions(FileFormat.MP4, caps)
        assertTrue(FileFormat.MKV in targets)
    }

    @Test
    fun testMp4ToMp3_extractsAudio() {
        val source = ConversionTestHelper.copyTestMp4()
        val output = ConversionTestHelper.convert(source, FileFormat.MP3)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "mp3")
        source.delete()
        output.delete()
    }

    @Test
    fun testMp4ToJpg_extractsFrame() {
        val source = ConversionTestHelper.copyTestMp4()
        val output = ConversionTestHelper.convert(source, FileFormat.JPG)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "jpg")
        val (w, h) = ConversionTestHelper.decodeBounds(output)
        assertTrue(w > 0 && h > 0)
        source.delete()
        output.delete()
    }
}
