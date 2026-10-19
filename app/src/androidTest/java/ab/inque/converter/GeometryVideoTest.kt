package ab.inque.converter

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Video geometry tests using bundled asset [ConversionTestHelper.TEST_MP4_ASSET]
 * (1920×1080 H.264 + AAC, ~1s).
 *
 * Covers T-G01, T-G02, T-G05, T-G08, T-NEG-01 from GEOMETRY_TEST_PLAN.md.
 */
@RunWith(AndroidJUnit4::class)
class GeometryVideoTest {

    @Test
    fun mp4ToJpg_producesValidFrame() {
        val mp4 = ConversionTestHelper.copyTestMp4()
        try {
            val jpg = ConversionTestHelper.convert(mp4, FileFormat.JPG)
            ConversionTestHelper.assertOutputExistsAndNonEmpty(jpg, "jpg")
            val (w, h) = ConversionTestHelper.decodeBounds(jpg)
            assertTrue("JPG frame should have non-zero size", w > 0 && h > 0)
            // Landscape source — display aspect should stay wide (not portrait)
            assertTrue("Expected landscape thumbnail", w >= h)
            jpg.delete()
        } finally {
            mp4.delete()
        }
    }

    @Test
    fun mp4ToMkv_remuxSucceeds() {
        ConversionTestHelper.assumeFfmpegAvailable()
        val mp4 = ConversionTestHelper.copyTestMp4()
        try {
            val mkv = ConversionTestHelper.convert(mp4, FileFormat.MKV)
            ConversionTestHelper.assertOutputExistsAndNonEmpty(mkv, "mkv")
            mkv.delete()
        } finally {
            mp4.delete()
        }
    }

    @Test
    fun mp4ToWebm_includesAudio() {
        ConversionTestHelper.assumeFfmpegAvailable()
        val mp4 = ConversionTestHelper.copyTestMp4()
        try {
            val webm = ConversionTestHelper.convert(mp4, FileFormat.WEBM)
            ConversionTestHelper.assertOutputExistsAndNonEmpty(webm, "webm")
            assertTrue(
                "WEBM output should include an audio track (G-08)",
                ConversionTestHelper.hasAudioTrack(webm)
            )
            webm.delete()
        } finally {
            mp4.delete()
        }
    }

    @Test
    fun mp4ToMp4_rotate90_thumbnailSwapsAspect() {
        val mp4 = ConversionTestHelper.copyTestMp4()
        try {
            val rotated = ConversionTestHelper.convert(
                mp4,
                FileFormat.MP4,
                toolOptions = MediaToolOptions(rotationDegrees = 90)
            )
            ConversionTestHelper.assertOutputExistsAndNonEmpty(rotated, "mp4")
            val thumb = ConversionTestHelper.convert(rotated, FileFormat.JPG)
            val (w, h) = ConversionTestHelper.decodeBounds(thumb)
            assertTrue("Rotated 90° should yield portrait thumbnail", h >= w)
            thumb.delete()
            rotated.delete()
        } finally {
            mp4.delete()
        }
    }

    @Test
    fun mp4Trim_remuxPreservesOrientation() {
        val mp4 = ConversionTestHelper.copyTestMp4()
        val sourceRotation = ConversionTestHelper.videoRotationDegrees(mp4)
        try {
            val trimmed = ConversionTestHelper.convert(
                mp4,
                FileFormat.MP4,
                toolOptions = MediaToolOptions(trimStartMs = 0, trimEndMs = 500)
            )
            ConversionTestHelper.assertOutputExistsAndNonEmpty(trimmed, "mp4")
            val outRotation = ConversionTestHelper.videoRotationDegrees(trimmed)
            assertTrue(
                "Trim remux should preserve rotation metadata (G-05); source=$sourceRotation out=$outRotation",
                outRotation == sourceRotation
            )
            trimmed.delete()
        } finally {
            mp4.delete()
        }
    }
}
