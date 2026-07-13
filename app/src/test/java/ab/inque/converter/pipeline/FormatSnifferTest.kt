package ab.inque.converter.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FormatSnifferTest {

    @Test
    fun sniff_detectsPdf() {
        val f = Files.createTempFile("test", ".bin").toFile()
        try {
            f.writeBytes("%PDF-1.4\n".toByteArray())
            assertEquals(ab.inque.converter.FileFormat.PDF, FormatSniffer.sniff(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun sniff_detectsPng() {
        val f = Files.createTempFile("test", ".bin").toFile()
        try {
            f.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            assertEquals(ab.inque.converter.FileFormat.PNG, FormatSniffer.sniff(f))
        } finally {
            f.delete()
        }
    }
}
