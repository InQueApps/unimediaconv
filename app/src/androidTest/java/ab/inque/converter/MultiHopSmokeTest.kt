package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPipeline
import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Multi-hop conversion smokes — exercises [ConversionPipeline] on device.
 */
@RunWith(AndroidJUnit4::class)
class MultiHopSmokeTest {

    @Test
    fun htmlToDocx_viaPdfChain() {
        val source = ConversionTestHelper.copyAssetToCache(
            "conversions/sample.html",
            "mh_html_${System.currentTimeMillis()}.html"
        )
        val path = ConversionPlanner.findPath(FileFormat.HTML, FileFormat.DOCX, FormatCapabilities.ALL)
        assertNotNull(path)
        assertTrue(path!!.size >= 3)

        val converter = UniversalConverter()
        val output = ConversionPipeline.execute(
            converter = converter,
            sourceFile = source,
            path = path,
            context = ConversionTestHelper.appContext(),
            progressCallback = {},
            scale = null,
            quality = QualityPreset.MEDIUM,
            toolOptions = MediaToolOptions()
        )
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "docx")
        source.delete()
        output.delete()
    }

    @Test
    fun txtToMp3_viaTtsChain() {
        val source = ConversionTestHelper.copyAssetToCache(
            "conversions/sample.txt",
            "mh_txt_${System.currentTimeMillis()}.txt"
        )
        val path = ConversionPlanner.findPath(FileFormat.TXT, FileFormat.MP3, FormatCapabilities.ALL)
        assertNotNull(path)
        assertTrue(path!!.size >= 2)

        val converter = UniversalConverter()
        val output = ConversionPipeline.execute(
            converter = converter,
            sourceFile = source,
            path = path,
            context = ConversionTestHelper.appContext(),
            progressCallback = {},
            scale = null,
            quality = QualityPreset.MEDIUM,
            toolOptions = MediaToolOptions()
        )
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "mp3")
        source.delete()
        output.delete()
    }
}
