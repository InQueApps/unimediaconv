package ab.inque.converter

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * User-requested transform tests — T-TOOL-02 from GEOMETRY_TEST_PLAN.md.
 */
@RunWith(AndroidJUnit4::class)
class GeometryToolTransformTest {

    @Test
    fun pngRotate90_swapsDimensions() {
        val cache = ConversionTestHelper.appContext().cacheDir
        val png = File(cache, "tool_rot_${System.currentTimeMillis()}.png")
        ConversionTestHelper.writeAspectPng(png, 400, 200, "WIDE")
        val (w1, h1) = ConversionTestHelper.decodeBounds(png)

        val rotated = ConversionTestHelper.convert(
            png,
            FileFormat.PNG,
            toolOptions = MediaToolOptions(rotationDegrees = 90)
        )
        val (w2, h2) = ConversionTestHelper.decodeBounds(rotated)

        assertEquals(h1, w2)
        assertEquals(w1, h2)

        png.delete()
        rotated.delete()
    }

    @Test
    fun pngFlipHorizontal_preservesAspectRatio() {
        val cache = ConversionTestHelper.appContext().cacheDir
        val png = File(cache, "tool_flip_${System.currentTimeMillis()}.png")
        ConversionTestHelper.writeSquarePng(png, 300)
        val (w1, h1) = ConversionTestHelper.decodeBounds(png)

        val flipped = ConversionTestHelper.convert(
            png,
            FileFormat.PNG,
            toolOptions = MediaToolOptions(flipHorizontal = true)
        )
        val (w2, h2) = ConversionTestHelper.decodeBounds(flipped)
        ConversionTestHelper.assertAspectRatioEquals(w1, h1, w2, h2, tolerance = 0.01f)

        png.delete()
        flipped.delete()
    }
}
