package ab.inque.converter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Round-trip fidelity tests — T-RT-01 through T-RT-03 from GEOMETRY_TEST_PLAN.md.
 */
@RunWith(AndroidJUnit4::class)
class GeometryRoundTripTest {

    @Test
    fun jpegGifJpeg_preservesAspectRatio() {
        val cache = ConversionTestHelper.appContext().cacheDir
        val png = File(cache, "rt_square_${System.currentTimeMillis()}.png")
        ConversionTestHelper.writeSquarePng(png, 512)
        val jpg = ConversionTestHelper.convert(png, FileFormat.JPG)
        png.delete()

        val (w1, h1) = ConversionTestHelper.decodeBounds(jpg)
        val gif = ConversionTestHelper.convert(jpg, FileFormat.GIF)
        val jpg2 = ConversionTestHelper.convert(gif, FileFormat.JPG)
        val (w2, h2) = ConversionTestHelper.decodeBounds(jpg2)

        ConversionTestHelper.assertAspectRatioEquals(w1, h1, w2, h2, tolerance = 0.05f)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(gif, "gif")
        ConversionTestHelper.assertOutputExistsAndNonEmpty(jpg2, "jpg")

        jpg.delete()
        gif.delete()
        jpg2.delete()
    }

    @Test
    fun pngGifPng_preservesAspectRatio() {
        val cache = ConversionTestHelper.appContext().cacheDir
        val png = File(cache, "rt_png_${System.currentTimeMillis()}.png")
        ConversionTestHelper.writeSquarePng(png, 256)
        val (w1, h1) = ConversionTestHelper.decodeBounds(png)

        val gif = ConversionTestHelper.convert(png, FileFormat.GIF)
        val png2 = ConversionTestHelper.convert(gif, FileFormat.PNG)
        val (w2, h2) = ConversionTestHelper.decodeBounds(png2)

        ConversionTestHelper.assertAspectRatioEquals(w1, h1, w2, h2, tolerance = 0.05f)

        png.delete()
        gif.delete()
        png2.delete()
    }

    @Test
    fun pngJpgPng_preservesAspectRatio() {
        val cache = ConversionTestHelper.appContext().cacheDir
        val png = File(cache, "rt_pjp_${System.currentTimeMillis()}.png")
        ConversionTestHelper.writeAspectPng(png, 640, 480, "LANDSCAPE")
        val (w1, h1) = ConversionTestHelper.decodeBounds(png)

        val jpg = ConversionTestHelper.convert(png, FileFormat.JPG)
        val png2 = ConversionTestHelper.convert(jpg, FileFormat.PNG)
        val (w2, h2) = ConversionTestHelper.decodeBounds(png2)

        ConversionTestHelper.assertAspectRatioEquals(w1, h1, w2, h2, tolerance = 0.05f)

        png.delete()
        jpg.delete()
        png2.delete()
    }
}
