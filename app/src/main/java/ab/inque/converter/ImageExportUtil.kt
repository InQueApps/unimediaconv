package ab.inque.converter

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Shared helpers for writing bitmap to BMP/TIFF streams (used by UniversalConverter and PdfConverter).
 */
object ImageExportUtil {

    fun writeBmp(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val rowBytes = w * 3
        val rowPadding = (4 - (rowBytes % 4)) % 4
        val paddedRowBytes = rowBytes + rowPadding
        val imageSize = paddedRowBytes * h
        val fileSize = 14 + 40 + imageSize

        val buf = BufferedOutputStream(out)
        fun writeInt(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
            buf.write((v shr 16) and 0xFF)
            buf.write((v shr 24) and 0xFF)
        }
        fun writeShort(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
        }

        buf.write('B'.code)
        buf.write('M'.code)
        writeInt(fileSize)
        writeInt(0)
        writeInt(54)

        writeInt(40)
        writeInt(w)
        writeInt(h)
        writeShort(1)
        writeShort(24)
        writeInt(0)
        writeInt(imageSize)
        writeInt(2835)
        writeInt(2835)
        writeInt(0)
        writeInt(0)

        val pixels = IntArray(w)
        val pad = ByteArray(rowPadding)
        for (y in h - 1 downTo 0) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = pixels[x]
                buf.write(p and 0xFF)
                buf.write((p shr 8) and 0xFF)
                buf.write((p shr 16) and 0xFF)
            }
            if (rowPadding > 0) buf.write(pad)
        }
        buf.flush()
    }

    fun writeTiff(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        val samplesPerPixel = 3
        val bitsPerSample = 8
        val imageDataSize = w * h * samplesPerPixel

        val numTags = 11
        val ifdSize = 2 + numTags * 12 + 4
        val bpsArrayOffset = 8 + ifdSize
        val xresOffset = bpsArrayOffset + 6
        val yresOffset = xresOffset + 8
        val stripOffset = yresOffset + 8

        val buf = BufferedOutputStream(out)
        fun writeShort(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
        }
        fun writeInt(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v shr 8) and 0xFF)
            buf.write((v shr 16) and 0xFF)
            buf.write((v shr 24) and 0xFF)
        }
        fun ifdEntry(tag: Int, type: Int, count: Int, value: Int) {
            writeShort(tag)
            writeShort(type)
            writeInt(count)
            writeInt(value)
        }

        buf.write('I'.code)
        buf.write('I'.code)
        writeShort(42)
        writeInt(8)

        writeShort(numTags)
        ifdEntry(256, 3, 1, w)
        ifdEntry(257, 3, 1, h)
        ifdEntry(258, 3, 3, bpsArrayOffset)
        ifdEntry(259, 3, 1, 1)
        ifdEntry(262, 3, 1, 2)
        ifdEntry(273, 4, 1, stripOffset)
        ifdEntry(277, 3, 1, samplesPerPixel)
        ifdEntry(278, 3, 1, h)
        ifdEntry(279, 4, 1, imageDataSize)
        ifdEntry(282, 5, 1, xresOffset)
        ifdEntry(283, 5, 1, yresOffset)
        writeInt(0)

        writeShort(bitsPerSample)
        writeShort(bitsPerSample)
        writeShort(bitsPerSample)
        writeInt(72)
        writeInt(1)
        writeInt(72)
        writeInt(1)

        val pixels = IntArray(w)
        for (y in 0 until h) {
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = pixels[x]
                buf.write((p shr 16) and 0xFF)
                buf.write((p shr 8) and 0xFF)
                buf.write(p and 0xFF)
            }
        }
        buf.flush()
    }
}
