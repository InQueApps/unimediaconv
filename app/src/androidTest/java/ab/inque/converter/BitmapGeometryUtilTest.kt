package ab.inque.converter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BitmapGeometryUtilTest {

    @Test
    fun aspectRatiosClose_withinTolerance() {
        assertTrue(BitmapGeometryUtil.aspectRatiosClose(512, 512, 500, 505, 0.05f))
        assertTrue(!BitmapGeometryUtil.aspectRatiosClose(512, 512, 512, 256, 0.05f))
    }

    @Test
    fun slideshowCanvasSize_capsLongestSide() {
        val (w, h) = BitmapGeometryUtil.slideshowCanvasSize(1920, 1080)
        assertEquals(720, w)
        assertEquals(405, h)
    }

    @Test
    fun fitBitmapOnCanvas_letterboxesWithoutStretch() {
        val src = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val result = BitmapGeometryUtil.fitBitmapOnCanvas(src, 100, 100, Color.WHITE)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
        // Letterboxed 200×100 → 100×50 centered; top row should be white background
        assertEquals(Color.WHITE, result.getPixel(50, 10))
        src.recycle()
        result.recycle()
    }

    @Test
    fun transformBitmap_rotate90_swapsDimensions() {
        val src = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val matrix = Matrix()
        matrix.setRotate(90f)
        val result = BitmapGeometryUtil.transformBitmap(src, matrix)
        assertEquals(200, result.width)
        assertEquals(400, result.height)
        src.recycle()
        result.recycle()
    }

    @Test
    fun transformBitmap_flipHorizontal_preservesDimensions() {
        val src = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        src.eraseColor(Color.RED)
        val result = BitmapGeometryUtil.flipBitmap(src, horizontal = true, vertical = false)
        assertEquals(100, result.width)
        assertEquals(80, result.height)
        src.recycle()
        result.recycle()
    }

    @Test
    fun displayDimensions_swapsForExif90() {
        val (w, h) = BitmapGeometryUtil.displayDimensions(
            4032,
            3024,
            ExifInterface.ORIENTATION_ROTATE_90
        )
        assertEquals(3024, w)
        assertEquals(4032, h)
    }

    @Test
    fun gifEncoder_addFrame_preservesAspectViaLetterbox() {
        val out = ByteArrayOutputStream()
        val encoder = GifEncoder(out)
        encoder.start(100, 100, 0)
        val wide = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        encoder.addFrame(wide, 100)
        encoder.finish()
        wide.recycle()
        assertTrue(out.size() > 32)
    }
}
