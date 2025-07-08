package ab.inque.converter

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.io.FileOutputStream

data class ConversionState(
    val isConverting: Boolean = false,
    val progress: Float = 0f,
    val selectedFile: Uri? = null,
    val selectedFileName: String = "",
    val sourceFormat: FileFormat? = null,
    val targetFormat: FileFormat? = null,
    val convertedFile: File? = null,
    val error: String? = null,
    val success: Boolean = false
)

class ConverterViewModel : ViewModel() {
    private val _state = MutableStateFlow(ConversionState())
    val state: StateFlow<ConversionState> = _state.asStateFlow()

    fun selectFile(uri: Uri, fileName: String) {
        val sourceFormat = FileFormat.fromExtension(fileName.substringAfterLast('.', ""))
        _state.value = _state.value.copy(
            selectedFile = uri,
            selectedFileName = fileName,
            sourceFormat = sourceFormat,
            error = null,
            success = false,
            convertedFile = null
        )
    }

    fun setTargetFormat(targetFormat: FileFormat) {
        _state.value = _state.value.copy(targetFormat = targetFormat)
    }

    fun convertFile(context: Context) {
        val currentState = _state.value
        val selectedFile = currentState.selectedFile ?: return
        val targetFormat = currentState.targetFormat ?: return
        val sourceFormat = currentState.sourceFormat ?: return
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            withTimeout(60000) { // 60 second timeout for larger files
                try {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _state.value = currentState.copy(isConverting = true, progress = 0f, error = null)
                    }
                    
                    // Copy file to temp location
                    val inputStream = context.contentResolver.openInputStream(selectedFile)
                    val tempFile = createTempFile(context, currentState.selectedFileName)
                    
                    inputStream?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _state.value = _state.value.copy(progress = 0.2f)
                    }
                    
                    // Perform conversion using UniversalConverter
                    val universalConverter = UniversalConverter()
                    var lastProgress = 0f
                    val outputFile = universalConverter.convertFile(
                        tempFile,
                        targetFormat,
                        context
                    ) { progress ->
                        // Just update a local variable; we'll post to state in the IO coroutine
                        lastProgress = progress
                    }
                    // After conversion, update progress to 1.0
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _state.value = _state.value.copy(progress = lastProgress)
                    }
                    
                    // Clean up temp file
                    tempFile.delete()
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.util.Log.d("ConverterViewModel", "Conversion successful: ${outputFile.name}")
                        _state.value = _state.value.copy(
                            isConverting = false,
                            progress = 1f,
                            convertedFile = outputFile,
                            success = true
                        )
                    }
                    
                } catch (e: Exception) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            isConverting = false,
                            error = when (e) {
                                is TimeoutCancellationException -> "Conversion timed out. Please try with a smaller file."
                                else -> e.message ?: "Conversion failed"
                            },
                            success = false
                        )
                    }
                }
            }
        }
    }

    private fun createTempFile(context: Context, fileName: String): File {
        val tempDir = File(context.cacheDir, "converter")
        tempDir.mkdirs()
        return File(tempDir, fileName)
    }



    fun resetState() {
        _state.value = ConversionState()
    }
    
    fun cancelConversion() {
        // viewModelScope.cancel() // Not needed, just reset state
        _state.value = _state.value.copy(
            isConverting = false,
            error = "Conversion cancelled",
            success = false
        )
    }
} 