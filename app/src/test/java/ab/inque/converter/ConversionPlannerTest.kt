package ab.inque.converter

import ab.inque.converter.pipeline.ConversionPlanner
import ab.inque.converter.pipeline.FormatCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFormatTest {

    @Test
    fun fromExtension_heif_mapsToHeic() {
        assertEquals(FileFormat.HEIC, FileFormat.fromExtension("heif"))
    }

    @Test
    fun fromExtension_htm_mapsToHtml() {
        assertEquals(FileFormat.HTML, FileFormat.fromExtension("htm"))
    }

    @Test
    fun fromExtension_tif_mapsToTiff() {
        assertEquals(FileFormat.TIFF, FileFormat.fromExtension("tif"))
    }

    @Test
    fun fromExtension_m4v_mapsToMp4() {
        assertEquals(FileFormat.MP4, FileFormat.fromExtension("m4v"))
    }

    @Test
    fun fromExtension_aac_mapsToM4a() {
        assertEquals(FileFormat.M4A, FileFormat.fromExtension("aac"))
    }

    @Test
    fun getSupportedConversions_txt_includesPdfAndMp3() {
        val targets = FileFormat.getSupportedConversions(FileFormat.TXT, FormatCapabilities.ALL)
        assertTrue(FileFormat.PDF in targets)
        assertTrue(FileFormat.MP3 in targets)
    }

    @Test
    fun getSupportedConversions_mp4_includesMkv() {
        val targets = FileFormat.getSupportedConversions(FileFormat.MP4, FormatCapabilities.ALL)
        assertTrue(FileFormat.MKV in targets)
    }
}

class ConversionPlannerTest {

    @Test
    fun findPath_epubToJpg_viaPdf() {
        val path = ConversionPlanner.findPath(FileFormat.EPUB, FileFormat.JPG, FormatCapabilities.ALL)
        assertNotNull(path)
        assertEquals(FileFormat.EPUB, path!![0])
        assertEquals(FileFormat.JPG, path.last())
        assertTrue(FileFormat.PDF in path)
    }

    @Test
    fun findPath_pngToDocx_directWhenOcrAvailable() {
        val path = ConversionPlanner.findPath(FileFormat.PNG, FileFormat.DOCX, FormatCapabilities.ALL)
        assertNotNull(path)
        assertEquals(2, path!!.size)
    }

    @Test
    fun findPath_mp4ToTxt_whenSttAvailable() {
        val path = ConversionPlanner.findPath(FileFormat.MP4, FileFormat.TXT, FormatCapabilities.ALL)
        assertNotNull(path)
        assertEquals(FileFormat.TXT, path!!.last())
    }

    @Test
    fun directTargets_video_excludesSttOnlyDocumentsWhenNoStt() {
        val caps = FormatCapabilities.ALL.copy(canTranscribe = false)
        val direct = ConversionPlanner.directTargets(FileFormat.MP4, caps)
        val sttOnly = listOf(
            FileFormat.TXT, FileFormat.DOCX, FileFormat.RTF,
            FileFormat.HTML, FileFormat.EPUB
        )
        assertTrue(sttOnly.none { it in direct })
        assertTrue(FileFormat.PDF in direct)
    }

    @Test
    fun directTargets_image_excludesOcrDocumentsWhenNoOcr() {
        val caps = FormatCapabilities.ALL.copy(canOcr = false)
        val direct = ConversionPlanner.directTargets(FileFormat.PNG, caps)
        val ocrDocs = listOf(
            FileFormat.TXT, FileFormat.DOCX, FileFormat.RTF,
            FileFormat.HTML, FileFormat.EPUB
        )
        assertTrue(ocrDocs.none { it in direct })
        assertTrue(FileFormat.PDF in direct)
    }

    @Test
    fun reachableTargets_imageIncludesPdfAndDocx() {
        val targets = ConversionPlanner.reachableTargets(FileFormat.PNG, FormatCapabilities.ALL)
        assertTrue(FileFormat.PDF in targets)
        assertTrue(FileFormat.DOCX in targets)
    }

    @Test
    fun reachableTargets_mp3_includesBmpWaveform() {
        val targets = ConversionPlanner.reachableTargets(FileFormat.MP3, FormatCapabilities.ALL)
        assertTrue(FileFormat.BMP in targets)
        assertTrue(FileFormat.TIFF in targets)
        assertTrue(FileFormat.HEIC in targets)
    }

    @Test
    fun directTargets_sameCategory_respectsEncodeCaps() {
        val caps = FormatCapabilities.ALL.copy(
            canEncodeHeic = false,
            canEncodeOgg = false,
            canEncodeFlac = false
        )
        assertTrue(FileFormat.HEIC !in ConversionPlanner.directTargets(FileFormat.PNG, caps))
        assertTrue(FileFormat.OGG !in ConversionPlanner.directTargets(FileFormat.MP3, caps))
        assertTrue(FileFormat.FLAC !in ConversionPlanner.directTargets(FileFormat.MP3, caps))
    }

    @Test
    fun findPath_mp3ToTxt_directWhenSttAvailable() {
        val path = ConversionPlanner.findPath(FileFormat.MP3, FileFormat.TXT, FormatCapabilities.ALL)
        assertNotNull(path)
        assertEquals(2, path!!.size)
        assertEquals(FileFormat.MP3, path[0])
        assertEquals(FileFormat.TXT, path[1])
    }

    @Test
    fun reachableTargets_mp3_excludesTextDocsWhenNoStt() {
        val caps = FormatCapabilities.ALL.copy(canTranscribe = false)
        val targets = ConversionPlanner.reachableTargets(FileFormat.MP3, caps)
        val textDocs = listOf(
            FileFormat.TXT, FileFormat.DOCX, FileFormat.RTF,
            FileFormat.HTML, FileFormat.EPUB, FileFormat.PDF
        )
        // Audio → text documents must use STT; no fake multi-hop (waveform → PDF → TXT).
        assertTrue(textDocs.none { it in targets })
    }

    @Test
    fun reachableTargets_mp3_includesTextDocsWhenSttAvailable() {
        val targets = ConversionPlanner.reachableTargets(FileFormat.MP3, FormatCapabilities.ALL)
        assertTrue(FileFormat.TXT in targets)
        assertTrue(FileFormat.DOCX in targets)
    }
}
