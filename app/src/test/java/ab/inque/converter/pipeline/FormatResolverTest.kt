package ab.inque.converter.pipeline

import ab.inque.converter.FileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FormatResolverTest {

    @Test
    fun fromExtension_heif_mapsToHeic() {
        assertEquals(FileFormat.HEIC, FormatResolver.fromExtension("photo.heif"))
    }

    @Test
    fun fromFile_sniffsWhenExtensionWrong() {
        val f = Files.createTempFile("wrong", ".bin").toFile()
        try {
            f.writeBytes("%PDF-1.4\n".toByteArray())
            assertEquals(FileFormat.PDF, FormatResolver.fromFile(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun fromExtension_unknownReturnsNull() {
        assertNull(FormatResolver.fromExtension("file.xyz"))
    }

    @Test
    fun sniffBytes_detectsMp4Ftyp() {
        val header = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)
        assertEquals(FileFormat.MP4, FormatSniffer.sniffBytes(header, header.size))
    }
}
