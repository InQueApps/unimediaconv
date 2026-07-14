package ab.inque.converter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrSmokeTest {

    @Test
    fun pngToTxt_producesNonEmptyText() {
        val source = File(
            ConversionTestHelper.appContext().cacheDir,
            "ocr_label_${System.currentTimeMillis()}.png"
        )
        writeLabeledPng(source, "HELLO")
        val output = ConversionTestHelper.convert(source, FileFormat.TXT)
        ConversionTestHelper.assertOutputExistsAndNonEmpty(output, "txt")
        val text = output.readText()
        assertTrue("OCR should find label text", text.contains("HELLO", ignoreCase = true))
        source.delete()
        output.delete()
    }

    private fun writeLabeledPng(file: File, label: String) {
        val bitmap = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 48f
        }
        canvas.drawText(label, 24f, 72f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
