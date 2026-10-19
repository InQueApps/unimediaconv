package ab.inque.converter.pipeline

import ab.inque.converter.FileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class FormatSnifferTest {

    private fun assertSniffedFormat(expected: FileFormat?, bytes: ByteArray) {
        val f = Files.createTempFile("sniff_test_", ".bin").toFile()
        try {
            f.writeBytes(bytes)
            assertEquals(expected, FormatSniffer.sniff(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun sniff_detectsPdf() {
        assertSniffedFormat(FileFormat.PDF, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".toByteArray(Charsets.ISO_8859_1))
    }

    @Test
    fun sniff_detectsPng() {
        assertSniffedFormat(
            FileFormat.PNG,
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        )
    }

    @Test
    fun sniff_detectsJpg() {
        assertSniffedFormat(
            FileFormat.JPG,
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46)
        )
    }

    @Test
    fun sniff_detectsGif87aAndGif89a() {
        assertSniffedFormat(FileFormat.GIF, "GIF87a\u0001\u0000\u0001\u0000".toByteArray(Charsets.US_ASCII))
        assertSniffedFormat(FileFormat.GIF, "GIF89a\u0001\u0000\u0001\u0000".toByteArray(Charsets.US_ASCII))
    }

    @Test
    fun sniff_detectsBmp() {
        assertSniffedFormat(
            FileFormat.BMP,
            byteArrayOf(0x42, 0x4D, 0x36, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x36, 0x00, 0x00, 0x00)
        )
    }

    @Test
    fun sniff_detectsTiffLittleAndBigEndian() {
        // Little-endian 'II' + 42
        assertSniffedFormat(
            FileFormat.TIFF,
            byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00)
        )
        // Big-endian 'MM' + 42
        assertSniffedFormat(
            FileFormat.TIFF,
            byteArrayOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08)
        )
    }

    @Test
    fun sniff_detectsWebp() {
        val header = "RIFF\u0024\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        assertSniffedFormat(FileFormat.WEBP, header)
    }

    @Test
    fun sniff_detectsWav() {
        val header = "RIFF\u0024\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)
        assertSniffedFormat(FileFormat.WAV, header)
    }

    @Test
    fun sniff_detectsAvi() {
        val header = "RIFF\u0024\u0000\u0000\u0000AVI LIST".toByteArray(Charsets.ISO_8859_1)
        assertSniffedFormat(FileFormat.AVI, header)
    }

    @Test
    fun sniff_detectsOgg() {
        val header = "OggS\u0000\u0002\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        assertSniffedFormat(FileFormat.OGG, header)
    }

    @Test
    fun sniff_detectsFlac() {
        val header = "fLaC\u0000\u0000\u0000\u0022\u0010\u0000\u0010\u0000".toByteArray(Charsets.ISO_8859_1)
        assertSniffedFormat(FileFormat.FLAC, header)
    }

    @Test
    fun sniff_detectsMp3Id3v2AndRawMpegSync() {
        // ID3v2 tag header
        val id3Header = "ID3\u0003\u0000\u0000\u0000\u0000\u0000\u0010".toByteArray(Charsets.ISO_8859_1)
        assertSniffedFormat(FileFormat.MP3, id3Header)

        // Raw MPEG-1 Layer 3 frame sync (0xFF 0xFB)
        val syncFrame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 0x00, 0x00, 0x00, 0x00)
        assertSniffedFormat(FileFormat.MP3, syncFrame)
    }

    @Test
    fun sniff_detectsMkvAndWebm() {
        val ebmlPrefix = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x9F.toByte(), 0x42, 0x82.toByte(), 0x88.toByte())
        val mkvBytes = ebmlPrefix + "matroska".toByteArray(Charsets.US_ASCII)
        assertSniffedFormat(FileFormat.MKV, mkvBytes)

        val webmBytes = ebmlPrefix + "webm".toByteArray(Charsets.US_ASCII)
        assertSniffedFormat(FileFormat.WEBM, webmBytes)
    }

    @Test
    fun sniff_detectsIsoBmffContainers_mp4MovM4aHeic3gp() {
        fun ftypBox(brand: String): ByteArray =
            byteArrayOf(0x00, 0x00, 0x00, 0x18) +
                "ftyp".toByteArray(Charsets.US_ASCII) +
                brand.toByteArray(Charsets.US_ASCII) +
                byteArrayOf(0x00, 0x00, 0x02, 0x00)

        assertSniffedFormat(FileFormat.MP4, ftypBox("isom"))
        assertSniffedFormat(FileFormat.MP4, ftypBox("mp42"))
        assertSniffedFormat(FileFormat.MOV, ftypBox("qt  "))
        assertSniffedFormat(FileFormat.M4A, ftypBox("M4A "))
        assertSniffedFormat(FileFormat.HEIC, ftypBox("heic"))
        assertSniffedFormat(FileFormat.HEIC, ftypBox("mif1"))
        assertSniffedFormat(FileFormat.THREEGP, ftypBox("3gp4"))
    }

    @Test
    fun sniff_detectsRtf() {
        val rtfBytes = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Arial;}}\\f0 Hello}".toByteArray(Charsets.US_ASCII)
        assertSniffedFormat(FileFormat.RTF, rtfBytes)
    }

    @Test
    fun sniff_detectsHtmlVariants() {
        val doctypeHtml = "<!DOCTYPE html><html><head><title>T</title></head><body></body></html>".toByteArray(Charsets.UTF_8)
        assertSniffedFormat(FileFormat.HTML, doctypeHtml)

        val directHtmlTag = "   <html><body><p>Hello</p></body></html>".toByteArray(Charsets.UTF_8)
        assertSniffedFormat(FileFormat.HTML, directHtmlTag)
    }

    @Test
    fun sniff_detectsDocxAndEpubZipSignatures() {
        val pkHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00)

        val docxBytes = pkHeader + "[Content_Types].xml".toByteArray(Charsets.US_ASCII)
        assertSniffedFormat(FileFormat.DOCX, docxBytes)

        val epubBytes = pkHeader + "mimetypeapplication/epub+zip".toByteArray(Charsets.US_ASCII)
        assertSniffedFormat(FileFormat.EPUB, epubBytes)
    }

    @Test
    fun sniff_returnsNullForTooShortOrUnknownBytes() {
        assertNull(FormatSniffer.sniffBytes(byteArrayOf(0x00), 1))
        assertSniffedFormat(null, "plain text without magic header".toByteArray(Charsets.UTF_8))
    }
}
