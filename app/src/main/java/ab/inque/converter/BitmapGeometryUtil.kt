package ab.inque.converter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.min

/**
 * Bitmap geometry helpers — letterboxing and aspect-ratio checks for slideshow / GIF paths.
 */
object BitmapGeometryUtil {

    fun aspectRatio(width: Int, height: Int): Float =
        if (height == 0) 0f else width.toFloat() / height

    fun aspectRatiosClose(
        w1: Int, h1: Int,
        w2: Int, h2: Int,
        tolerance: Float = 0.05f
    ): Boolean {
        val r1 = aspectRatio(w1, h1)
        val r2 = aspectRatio(w2, h2)
        if (r1 == 0f || r2 == 0f) return false
        return abs(r1 - r2) / r1 <= tolerance
    }

    /**
     * Scale [bitmap] uniformly to fit inside [canvasW]×[canvasH], centered on [backgroundColor].
     * Returns a new exact-size bitmap; recycles [bitmap] if a new instance was created for scaling.
     */
    fun fitBitmapOnCanvas(
        bitmap: Bitmap,
        canvasW: Int,
        canvasH: Int,
        backgroundColor: Int = Color.WHITE
    ): Bitmap {
        require(canvasW > 0 && canvasH > 0) { "Canvas dimensions must be positive" }
        if (bitmap.width == canvasW && bitmap.height == canvasH) return bitmap

        val scale = min(
            canvasW.toFloat() / bitmap.width,
            canvasH.toFloat() / bitmap.height
        )
        val scaledW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val scaled = if (scaledW == bitmap.width && scaledH == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        }

        val canvas = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        c.drawColor(backgroundColor)
        val left = (canvasW - scaledW) / 2f
        val top = (canvasH - scaledH) / 2f
        c.drawBitmap(scaled, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== bitmap) scaled.recycle()
        return canvas
    }

    /**
     * Applies [matrix] to [bitmap], returning a new bitmap sized to the transformed bounds.
     * Unlike Bitmap.createBitmap with a fixed source size, rotations and flips are not cropped.
     */
    fun transformBitmap(bitmap: Bitmap, matrix: Matrix): Bitmap {
        if (matrix.isIdentity) return bitmap
        val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        matrix.mapRect(bounds)
        val outW = bounds.width().toInt().coerceAtLeast(1)
        val outH = bounds.height().toInt().coerceAtLeast(1)
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val drawMatrix = Matrix(matrix)
        drawMatrix.postTranslate(-bounds.left, -bounds.top)
        canvas.drawBitmap(bitmap, drawMatrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.setRotate(degrees.toFloat())
        return transformBitmap(bitmap, matrix)
    }

    fun flipBitmap(bitmap: Bitmap, horizontal: Boolean, vertical: Boolean): Bitmap {
        if (!horizontal && !vertical) return bitmap
        val matrix = Matrix()
        matrix.preScale(
            if (horizontal) -1f else 1f,
            if (vertical) -1f else 1f,
            bitmap.width / 2f,
            bitmap.height / 2f
        )
        return transformBitmap(bitmap, matrix)
    }

    fun readExifOrientation(sourceFile: File): Int = try {
        ExifInterface(sourceFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    fun readExifOrientation(input: InputStream): Int = try {
        ExifInterface(input).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Display width/height after EXIF orientation is applied to pixel data. */
    fun displayDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (orientationSwapsDimensions(orientation)) height to width else width to height

    fun orientationSwapsDimensions(orientation: Int): Boolean =
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE

    fun exifOrientationMatrix(orientation: Int): Matrix? {
        if (orientation == ExifInterface.ORIENTATION_NORMAL) return null
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return null
        }
        return matrix
    }

    /** Canvas size from first image, capped at [maxDim] on the longest side. */
    fun slideshowCanvasSize(firstWidth: Int, firstHeight: Int, maxDim: Int = 720): Pair<Int, Int> {
        val scale = minOf(
            maxDim.toFloat() / firstWidth,
            maxDim.toFloat() / firstHeight,
            1f
        )
        val w = (firstWidth * scale).toInt().coerceAtLeast(1)
        val h = (firstHeight * scale).toInt().coerceAtLeast(1)
        return w to h
    }
}
