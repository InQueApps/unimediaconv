package ab.inque.converter

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Animated GIF89a encoder.
 *
 * Each frame is independently color-quantized to a 256-color local palette
 * and LZW-compressed. The encoder streams data directly to the output so
 * callers can process one frame at a time without keeping all frames in memory.
 */
class GifEncoder(private val out: OutputStream) {

    private var width = 0
    private var height = 0

    fun start(w: Int, h: Int, loopCount: Int = 0) {
        width = w
        height = h
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeScreenDescriptor()
        if (loopCount >= 0) writeNetscapeExtension(loopCount)
    }

    fun addFrame(bitmap: Bitmap, delayMs: Int) {
        val pixels = IntArray(width * height)
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            BitmapGeometryUtil.fitBitmapOnCanvas(bitmap, width, height, Color.WHITE)
        } else bitmap
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()

        val (palette, indices) = quantize(pixels)
        writeGraphicControl(delayMs / 10)
        writeImageDescriptor(palette)
        lzwEncode(indices)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    // ── GIF structure writers ────────────────────────────────────────────

    private fun writeScreenDescriptor() {
        writeShort(width)
        writeShort(height)
        out.write(0x70) // no GCT, color resolution = 8 bits
        out.write(0)    // background color index
        out.write(0)    // pixel aspect ratio
    }

    private fun writeNetscapeExtension(loopCount: Int) {
        out.write(0x21) // extension introducer
        out.write(0xFF) // application extension
        out.write(11)   // block size
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)    // sub-block size
        out.write(1)    // sub-block ID
        writeShort(loopCount)
        out.write(0)    // block terminator
    }

    private fun writeGraphicControl(delayCentisec: Int) {
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(0x04) // disposal = restore to background, no transparency
        writeShort(delayCentisec.coerceAtLeast(2))
        out.write(0)    // transparent color index (unused)
        out.write(0)    // block terminator
    }

    private fun writeImageDescriptor(palette: ByteArray) {
        out.write(0x2C)
        writeShort(0)     // left
        writeShort(0)     // top
        writeShort(width)
        writeShort(height)
        out.write(0x87)   // local color table present, 256 entries (2^(7+1))
        out.write(palette) // 768 bytes
    }

    // ── Color quantization ──────────────────────────────────────────────

    /**
     * Median-cut quantization: divides the color space into 256 buckets
     * by repeatedly splitting the box with the largest range along its
     * widest channel.
     */
    private fun quantize(pixels: IntArray): Pair<ByteArray, ByteArray> {
        data class Box(
            val colors: MutableList<Int>,
            var rMin: Int, var rMax: Int,
            var gMin: Int, var gMax: Int,
            var bMin: Int, var bMax: Int
        ) {
            val volume: Int get() = (rMax - rMin + 1) * (gMax - gMin + 1) * (bMax - bMin + 1)
            val longestAxis: Int
                get() {
                    val rRange = rMax - rMin
                    val gRange = gMax - gMin
                    val bRange = bMax - bMin
                    return when {
                        rRange >= gRange && rRange >= bRange -> 0
                        gRange >= bRange -> 1
                        else -> 2
                    }
                }

            fun recomputeBounds() {
                rMin = 255; rMax = 0; gMin = 255; gMax = 0; bMin = 255; bMax = 0
                for (c in colors) {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r < rMin) rMin = r; if (r > rMax) rMax = r
                    if (g < gMin) gMin = g; if (g > gMax) gMax = g
                    if (b < bMin) bMin = b; if (b > bMax) bMax = b
                }
            }

            fun averageColor(): Int {
                var rSum = 0L; var gSum = 0L; var bSum = 0L
                for (c in colors) {
                    rSum += (c shr 16) and 0xFF
                    gSum += (c shr 8) and 0xFF
                    bSum += c and 0xFF
                }
                val n = colors.size.coerceAtLeast(1)
                return ((rSum / n).toInt() shl 16) or ((gSum / n).toInt() shl 8) or (bSum / n).toInt()
            }
        }

        val uniqueColors = mutableListOf<Int>()
        val seen = HashSet<Int>(pixels.size / 2)
        for (p in pixels) {
            val rgb = p and 0x00FFFFFF
            if (seen.add(rgb)) uniqueColors.add(rgb)
        }

        if (uniqueColors.size <= 256) {
            return buildDirectPalette(uniqueColors, pixels)
        }

        val initial = Box(uniqueColors, 0, 255, 0, 255, 0, 255)
        initial.recomputeBounds()
        val boxes = mutableListOf(initial)

        while (boxes.size < 256) {
            val box = boxes.maxByOrNull { it.colors.size * it.volume } ?: break
            if (box.colors.size < 2) break

            val axis = box.longestAxis
            box.colors.sortBy { color ->
                when (axis) {
                    0 -> (color shr 16) and 0xFF
                    1 -> (color shr 8) and 0xFF
                    else -> color and 0xFF
                }
            }
            val mid = box.colors.size / 2
            val left = Box(box.colors.subList(0, mid).toMutableList(), 0, 0, 0, 0, 0, 0)
            val right = Box(box.colors.subList(mid, box.colors.size).toMutableList(), 0, 0, 0, 0, 0, 0)
            left.recomputeBounds()
            right.recomputeBounds()
            boxes.remove(box)
            boxes.add(left)
            boxes.add(right)
        }

        val paletteRgb = IntArray(256)
        for (i in boxes.indices.take(256)) {
            paletteRgb[i] = boxes[i].averageColor()
        }
        val palette = ByteArray(768)
        for (i in 0 until 256) {
            palette[i * 3] = ((paletteRgb[i] shr 16) and 0xFF).toByte()
            palette[i * 3 + 1] = ((paletteRgb[i] shr 8) and 0xFF).toByte()
            palette[i * 3 + 2] = (paletteRgb[i] and 0xFF).toByte()
        }

        val indices = mapPixels(pixels, paletteRgb)
        return palette to indices
    }

    private fun buildDirectPalette(
        uniqueColors: List<Int>,
        pixels: IntArray
    ): Pair<ByteArray, ByteArray> {
        val palette = ByteArray(768)
        val colorToIndex = HashMap<Int, Int>(uniqueColors.size * 2)
        for (i in uniqueColors.indices) {
            val c = uniqueColors[i]
            palette[i * 3] = ((c shr 16) and 0xFF).toByte()
            palette[i * 3 + 1] = ((c shr 8) and 0xFF).toByte()
            palette[i * 3 + 2] = (c and 0xFF).toByte()
            colorToIndex[c] = i
        }
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            indices[i] = (colorToIndex[pixels[i] and 0x00FFFFFF] ?: 0).toByte()
        }
        return palette to indices
    }

    private fun mapPixels(pixels: IntArray, palette: IntArray): ByteArray {
        val indices = ByteArray(pixels.size)
        val cache = HashMap<Int, Int>(4096)
        for (i in pixels.indices) {
            val rgb = pixels[i] and 0x00FFFFFF
            indices[i] = cache.getOrPut(rgb) { nearestColor(rgb, palette) }.toByte()
        }
        return indices
    }

    private fun nearestColor(rgb: Int, palette: IntArray): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            if (palette[i] == 0 && i > 0 && palette[i - 1] == 0) continue
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val d = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (d < bestDist) {
                bestDist = d
                best = i
                if (d == 0) break
            }
        }
        return best
    }

    // ── LZW encoder ─────────────────────────────────────────────────────

    private fun lzwEncode(indices: ByteArray) {
        val minCodeSize = 8
        out.write(minCodeSize)

        val clearCode = 1 shl minCodeSize      // 256
        val eoiCode = clearCode + 1             // 257

        val compressed = ByteArrayOutputStream(indices.size / 2)
        val bits = BitPacker(compressed)

        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1
        val table = HashMap<Long, Int>(8192)

        fun resetTable() {
            table.clear()
            codeSize = minCodeSize + 1
            nextCode = eoiCode + 1
        }

        bits.write(clearCode, codeSize)
        resetTable()

        if (indices.isEmpty()) {
            bits.write(eoiCode, codeSize)
            bits.flush()
            writeSubBlocks(compressed.toByteArray())
            return
        }

        var w = indices[0].toInt() and 0xFF

        for (i in 1 until indices.size) {
            val c = indices[i].toInt() and 0xFF
            val key = (w.toLong() shl 8) or c.toLong()

            val existing = table[key]
            if (existing != null) {
                w = existing
            } else {
                bits.write(w, codeSize)
                if (nextCode < 4096) {
                    table[key] = nextCode++
                    if (nextCode > (1 shl codeSize) && codeSize < 12) {
                        codeSize++
                    }
                } else {
                    bits.write(clearCode, codeSize)
                    resetTable()
                }
                w = c
            }
        }

        bits.write(w, codeSize)
        bits.write(eoiCode, codeSize)
        bits.flush()

        writeSubBlocks(compressed.toByteArray())
    }

    private fun writeSubBlocks(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val blockSize = minOf(255, data.size - offset)
            out.write(blockSize)
            out.write(data, offset, blockSize)
            offset += blockSize
        }
        out.write(0) // block terminator
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private class BitPacker(private val out: ByteArrayOutputStream) {
        private var buffer = 0
        private var bits = 0

        fun write(code: Int, numBits: Int) {
            buffer = buffer or (code shl bits)
            bits += numBits
            while (bits >= 8) {
                out.write(buffer and 0xFF)
                buffer = buffer ushr 8
                bits -= 8
            }
        }

        fun flush() {
            if (bits > 0) {
                out.write(buffer and 0xFF)
                buffer = 0
                bits = 0
            }
        }
    }
}
