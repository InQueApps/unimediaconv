package ab.inque.converter.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device OCR via ML Kit (Latin script).
 */
object OcrHelper {

    fun recognizeTextFromFile(imageFile: File): String {
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: throw java.io.IOException("Could not decode image for OCR")
        return try {
            recognizeTextFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    fun recognizeTextFromBitmap(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val latch = CountDownLatch(1)
        val result = StringBuilder()
        var error: Exception? = null
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                result.append(visionText.text)
                latch.countDown()
            }
            .addOnFailureListener { e ->
                error = java.io.IOException("OCR failed: ${e.message}", e)
                latch.countDown()
            }
        if (!latch.await(120, TimeUnit.SECONDS)) {
            throw java.io.IOException("OCR timed out")
        }
        error?.let { throw it }
        return result.toString().trim()
    }
}
