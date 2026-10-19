package ab.inque.converter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Slideshow geometry — T-G03 from GEOMETRY_TEST_PLAN.md.
 */
@RunWith(AndroidJUnit4::class)
class GeometrySlideshowTest {

    @Test
    fun mixedAspectImagesToGif_usesFirstImageAspect() {
        val cache = ConversionTestHelper.appContext().cacheDir
        val landscape = File(cache, "slide_land_${System.currentTimeMillis()}.png")
        val portrait = File(cache, "slide_port_${System.currentTimeMillis()}.png")
        ConversionTestHelper.writeAspectPng(landscape, 320, 180, "LAND")
        ConversionTestHelper.writeAspectPng(portrait, 180, 320, "PORT")

        val gif = ConversionTestHelper.convertImagesToSlideshow(listOf(landscape, portrait), FileFormat.GIF)
        val (wLand, hLand) = ConversionTestHelper.decodeBounds(landscape)
        val (wGif, hGif) = ConversionTestHelper.decodeBounds(gif)
        ConversionTestHelper.assertAspectRatioEquals(wLand, hLand, wGif, hGif, tolerance = 0.05f)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(gif, "gif")

        landscape.delete()
        portrait.delete()
        gif.delete()
    }

    @Test
    fun slideshowCanvasSize_matchesFirstImageAspect() {
        val (w, h) = BitmapGeometryUtil.slideshowCanvasSize(1280, 720)
        assertTrue(BitmapGeometryUtil.aspectRatiosClose(1280, 720, w, h, 0.01f))
        val (w2, h2) = BitmapGeometryUtil.slideshowCanvasSize(720, 1280)
        assertTrue(BitmapGeometryUtil.aspectRatiosClose(720, 1280, w2, h2, 0.01f))
    }
}
