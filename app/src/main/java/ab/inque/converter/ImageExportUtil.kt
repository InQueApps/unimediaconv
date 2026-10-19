package ab.inque.converter

import android.graphics.Bitmap
import androidx.heifwriter.HeifWriter
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import kotlin.math.abs

/**
 * Shared helpers for reading and writing BMP/TIFF/HEIC images (used by UniversalConverter, PdfConverter, and MainActivity).
 */
object ImageExportUtil {

    data class RawImageData(
        val width: Int,
        val height: Int,
        val argbPixels: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawImageData) return false
            return width == other.width &&
                height == other.height &&
                argbPixels.contentEquals(other.argbPixels)
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + argbPixels.contentHashCode()
            return result
        }
    }

    fun writeHeic(bitmap: Bitmap, file: File, quality: Int = 85) {
        if (file.exists()) file.delete()
        val writer = HeifWriter.Builder(
            file.absolutePath,
            bitmap.width.coerceAtLeast(1),
            bitmap.height.coerceAtLeast(1),
            HeifWriter.INPUT_MODE_BITMAP
        )
            .setMaxImages(1)
            .setQuality(quality.coerceIn(0, 100))
            .build()
        try {
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(10_000)
        } finally {
            writer.close()
        }
    }

    fun writeHeic(bitmap: Bitmap, quality: Int, out: OutputStream, tempDir: File) {
        val tempFile = File(tempDir, "temp_heic_${System.nanoTime()}.heic")
        try {
            writeHeic(bitmap, tempFile, quality)
            FileInputStream(tempFile).use { input ->
                input.copyTo(out)
            }
            out.flush()
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    fun writeHeicToStream(bitmap: Bitmap, out: OutputStream, cacheDir: File, quality: Int = 85) {
        writeHeic(bitmap, quality, out, cacheDir)
    }

    fun writeBmp(bitmap: Bitmap, out: OutputStream) {
        val w = bitmap.width
        val h = bitmap.height
        writeBmpFromPixels(
            width = w,
            height = h,
            getRowPixels = { y, rowOut -> bitmap.getPixels(rowOut, 0, w, 0, y, w, 1) },
            out = out
        )
    }

    fun writeBmpFromPixels(
        width: Int,
        height: Int,
        getRowPixels: (y: Int, rowOut: IntArray) -> Unit,
        out: OutputStream
    ) {
        val rowBytes = width * 3
        val rowPadding = (4 - (rowBytes % 4)) % 4
        val paddedRowBytes = rowBytes + rowPadding
        val imageSize = paddedRowBytes * height
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
        writeInt(width)
        writeInt(height)
        writeShort(1)
        writeShort(24)
        writeInt(0)
        writeInt(imageSize)
        writeInt(2835)
        writeInt(2835)
        writeInt(0)
        writeInt(0)

        val pixels = IntArray(width)
        val pad = ByteArray(rowPadding)
        for (y in height - 1 downTo 0) {
            getRowPixels(y, pixels)
            for (x in 0 until width) {
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
        writeTiffFromPixels(
            width = w,
            height = h,
            getRowPixels = { y, rowOut -> bitmap.getPixels(rowOut, 0, w, 0, y, w, 1) },
            out = out
        )
    }

    fun writeTiffFromPixels(
        width: Int,
        height: Int,
        getRowPixels: (y: Int, rowOut: IntArray) -> Unit,
        out: OutputStream
    ) {
        val samplesPerPixel = 3
        val bitsPerSample = 8
        val imageDataSize = width * height * samplesPerPixel

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
        ifdEntry(256, 3, 1, width)
        ifdEntry(257, 3, 1, height)
        ifdEntry(258, 3, 3, bpsArrayOffset)
        ifdEntry(259, 3, 1, 1)
        ifdEntry(262, 3, 1, 2)
        ifdEntry(273, 4, 1, stripOffset)
        ifdEntry(277, 3, 1, samplesPerPixel)
        ifdEntry(278, 3, 1, height)
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

        val pixels = IntArray(width)
        for (y in 0 until height) {
            getRowPixels(y, pixels)
            for (x in 0 until width) {
                val p = pixels[x]
                buf.write((p shr 16) and 0xFF)
                buf.write((p shr 8) and 0xFF)
                buf.write(p and 0xFF)
            }
        }
        buf.flush()
    }

    fun readBmpPixels(bytes: ByteArray): RawImageData? {
        if (bytes.size < 54) return null
        if (bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) return null

        fun leU16(off: Int): Int =
            (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

        fun le32(off: Int): Int =
            (bytes[off].toInt() and 0xFF) or
                ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((bytes[off + 2].toInt() and 0xFF) shl 16) or
                ((bytes[off + 3].toInt() and 0xFF) shl 24)

        val pixelDataOffset = le32(10)
        val dibHeaderSize = le32(14)
        if (dibHeaderSize < 40) return null

        val width = le32(18)
        val rawHeight = le32(22)
        val topDown = rawHeight < 0
        val height = abs(rawHeight)
        val bitsPerPixel = leU16(28)
        val compression = le32(30)

        if (width <= 0 || height <= 0) return null
        if (bitsPerPixel != 24 && bitsPerPixel != 32) return null
        if (compression != 0 && !(compression == 3 && bitsPerPixel == 32)) return null

        val bytesPerPixel = bitsPerPixel / 8
        val rowBytes = width * bytesPerPixel
        val paddedRowBytes = (rowBytes + 3) and 3.inv()
        if (pixelDataOffset < 0 || pixelDataOffset.toLong() + paddedRowBytes.toLong() * (height - 1) + rowBytes > bytes.size) {
            return null
        }

        val argb = IntArray(width * height)
        for (rowIdx in 0 until height) {
            val y = if (topDown) rowIdx else (height - 1 - rowIdx)
            val rowStart = pixelDataOffset + rowIdx * paddedRowBytes
            val dstRowOffset = y * width
            for (x in 0 until width) {
                val pOff = rowStart + x * bytesPerPixel
                val b = bytes[pOff].toInt() and 0xFF
                val g = bytes[pOff + 1].toInt() and 0xFF
                val r = bytes[pOff + 2].toInt() and 0xFF
                val a = if (bytesPerPixel == 4) (bytes[pOff + 3].toInt() and 0xFF) else 0xFF
                argb[dstRowOffset + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RawImageData(width, height, argb)
    }

    fun readBmp(file: File): Bitmap? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val raw = readBmpPixels(file.readBytes()) ?: return null
            Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(raw.argbPixels, 0, raw.width, 0, 0, raw.width, raw.height)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readTiffPixels(bytes: ByteArray): RawImageData? {
        if (bytes.size < 8) return null
        val littleEndian = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> return null
        }

        fun u16(off: Int): Int {
            if (off < 0 || off + 2 > bytes.size) return 0
            val b0 = bytes[off].toInt() and 0xFF
            val b1 = bytes[off + 1].toInt() and 0xFF
            return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }

        fun u32(off: Int): Int {
            if (off < 0 || off + 4 > bytes.size) return 0
            val b0 = bytes[off].toInt() and 0xFF
            val b1 = bytes[off + 1].toInt() and 0xFF
            val b2 = bytes[off + 2].toInt() and 0xFF
            val b3 = bytes[off + 3].toInt() and 0xFF
            return if (littleEndian) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }

        if (u16(2) != 42) return null
        val ifdOffset = u32(4)
        if (ifdOffset < 8 || ifdOffset + 2 > bytes.size) return null
        val numEntries = u16(ifdOffset)
        if (numEntries <= 0 || ifdOffset + 2 + numEntries * 12 > bytes.size) return null

        fun readTagValues(entryOffset: Int): IntArray {
            val type = u16(entryOffset + 2)
            val count = u32(entryOffset + 4)
            if (count <= 0 || count > 1_000_000) return IntArray(0)
            val elemSize = when (type) {
                1, 2, 6, 7 -> 1
                3, 8 -> 2
                4, 9 -> 4
                5, 10 -> 8
                else -> return IntArray(0)
            }
            val totalBytes = count * elemSize
            val valueFieldOffset = entryOffset + 8
            val dataOffset = if (totalBytes <= 4) valueFieldOffset else u32(valueFieldOffset)
            if (dataOffset < 0 || dataOffset + totalBytes > bytes.size) return IntArray(0)
            return IntArray(count) { idx ->
                val pos = dataOffset + idx * elemSize
                when (elemSize) {
                    1 -> bytes[pos].toInt() and 0xFF
                    2 -> u16(pos)
                    4 -> u32(pos)
                    8 -> {
                        val num = u32(pos)
                        val den = u32(pos + 4)
                        if (den != 0) num / den else 0
                    }
                    else -> 0
                }
            }
        }

        var width = 0
        var height = 0
        var bitsPerSample = intArrayOf(8)
        var compression = 1
        var photometric = 2
        var stripOffsets = IntArray(0)
        var samplesPerPixel = 1
        var rowsPerStrip = 0
        var stripByteCounts = IntArray(0)

        for (i in 0 until numEntries) {
            val entryOff = ifdOffset + 2 + i * 12
            val tag = u16(entryOff)
            val values = readTagValues(entryOff)
            if (values.isEmpty()) continue
            when (tag) {
                256 -> width = values[0]
                257 -> height = values[0]
                258 -> bitsPerSample = values
                259 -> compression = values[0]
                262 -> photometric = values[0]
                273 -> stripOffsets = values
                277 -> samplesPerPixel = values[0]
                278 -> rowsPerStrip = values[0]
                279 -> stripByteCounts = values
            }
        }

        if (width <= 0 || height <= 0) return null
        if (compression != 1) return null
        if (stripOffsets.isEmpty()) return null
        if (bitsPerSample.any { it != 8 }) return null
        if (samplesPerPixel <= 0) {
            samplesPerPixel = bitsPerSample.size.coerceAtLeast(1)
        }

        val effectiveRowsPerStrip = if (rowsPerStrip <= 0 || rowsPerStrip > height) height else rowsPerStrip
        val bytesPerRow = width * samplesPerPixel
        val argb = IntArray(width * height)

        for (stripIdx in stripOffsets.indices) {
            val startRow = stripIdx * effectiveRowsPerStrip
            if (startRow >= height) break
            val rowsInStrip = minOf(effectiveRowsPerStrip, height - startRow)
            val stripOffset = stripOffsets[stripIdx]
            if (stripByteCounts.isNotEmpty() && stripIdx < stripByteCounts.size) {
                val expectedBytes = rowsInStrip * bytesPerRow
                if (stripByteCounts[stripIdx] < expectedBytes) return null
            }
            for (r in 0 until rowsInStrip) {
                val y = startRow + r
                val rowOffset = stripOffset + r * bytesPerRow
                if (rowOffset < 0 || rowOffset + bytesPerRow > bytes.size) return null
                val dstRow = y * width
                for (x in 0 until width) {
                    val pOff = rowOffset + x * samplesPerPixel
                    val pixel = if (samplesPerPixel >= 3) {
                        val red = bytes[pOff].toInt() and 0xFF
                        val green = bytes[pOff + 1].toInt() and 0xFF
                        val blue = bytes[pOff + 2].toInt() and 0xFF
                        val alpha = if (samplesPerPixel >= 4) (bytes[pOff + 3].toInt() and 0xFF) else 0xFF
                        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                    } else {
                        val rawGray = bytes[pOff].toInt() and 0xFF
                        val gray = if (photometric == 0) (255 - rawGray) else rawGray
                        val alpha = if (samplesPerPixel == 2) (bytes[pOff + 1].toInt() and 0xFF) else 0xFF
                        (alpha shl 24) or (gray shl 16) or (gray shl 8) or gray
                    }
                    argb[dstRow + x] = pixel
                }
            }
        }

        return RawImageData(width, height, argb)
    }

    fun readTiff(file: File): Bitmap? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val raw = readTiffPixels(file.readBytes()) ?: return null
            Bitmap.createBitmap(raw.width, raw.height, Bitmap.Config.ARGB_8888).apply {
                setPixels(raw.argbPixels, 0, raw.width, 0, 0, raw.width, raw.height)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun readTiffBounds(file: File): Pair<Int, Int>? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val raw = readTiffPixels(file.readBytes()) ?: return null
            raw.width to raw.height
        } catch (_: Exception) {
            null
        }
    }

    fun readTiffBounds(bytes: ByteArray): Pair<Int, Int>? {
        val raw = readTiffPixels(bytes) ?: return null
        return raw.width to raw.height
    }
}
