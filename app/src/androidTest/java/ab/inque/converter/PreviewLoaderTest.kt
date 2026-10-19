package ab.inque.converter

import ab.inque.converter.preview.PreviewContent
import ab.inque.converter.preview.PreviewLoader
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PreviewLoaderTest {

    @Test
    fun load_pngToJpg_returnsStillImage() = runBlocking {
        val ctx = ConversionTestHelper.appContext()
        val file = File(ctx.cacheDir, "preview_png_${System.currentTimeMillis()}.png")
        ConversionTestHelper.writeMinimalPng(file)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val content = PreviewLoader.load(
            context = ctx,
            uri = uri,
            fileName = file.name,
            sourceFormat = FileFormat.PNG,
            targetFormat = FileFormat.JPG,
            toolOptions = MediaToolOptions(),
            scale = null,
            durationMs = 0L,
            mimeType = "image/png"
        )
        try {
            assertTrue(content is PreviewContent.StillImage)
            val still = content as PreviewContent.StillImage
            assertTrue(still.bitmaps.isNotEmpty())
            assertTrue(still.bitmaps.first().width > 0)
        } finally {
            file.delete()
        }
    }

    @Test
    fun load_mp4ToMkv_returnsMediaClip() = runBlocking {
        val ctx = ConversionTestHelper.appContext()
        val file = ConversionTestHelper.copyTestMp4()
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val content = PreviewLoader.load(
            context = ctx,
            uri = uri,
            fileName = file.name,
            sourceFormat = FileFormat.MP4,
            targetFormat = FileFormat.MKV,
            toolOptions = MediaToolOptions(),
            scale = null,
            durationMs = 1_000L,
            mimeType = "video/mp4"
        )
        try {
            assertTrue(content is PreviewContent.MediaClip)
        } finally {
            file.delete()
        }
    }
}
