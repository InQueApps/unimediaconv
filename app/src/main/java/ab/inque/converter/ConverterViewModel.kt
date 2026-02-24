package ab.inque.converter

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SelectedFileItem(val uri: Uri, val fileName: String)

sealed class ConvertResult {
    data class Single(val file: File) : ConvertResult()
    data class Multiple(val files: List<File>) : ConvertResult()
}

data class ConversionState(
    val isConverting: Boolean = false,
    val progress: Float = 0f,
    val batchCurrentIndex: Int = 0,
    val batchTotal: Int = 0,
    val selectedFiles: List<SelectedFileItem> = emptyList(),
    val outputAsZip: Boolean = true,
    val sourceFormat: FileFormat? = null,
    val targetFormat: FileFormat? = null,
    val convertedFile: File? = null,
    val convertedFiles: List<File>? = null,
    val error: String? = null,
    val success: Boolean = false
) {
    val selectedFile: Uri? get() = selectedFiles.singleOrNull()?.uri
    val selectedFileName: String get() = when (selectedFiles.size) {
        0 -> ""
        1 -> selectedFiles.first().fileName
        else -> ""
    }
    val isMultiFile: Boolean get() = selectedFiles.size > 1
}

class ConverterViewModel : ViewModel() {
    private val _state = MutableStateFlow(ConversionState())
    val state: StateFlow<ConversionState> = _state.asStateFlow()
    private var appContext: Context? = null

    init {
        viewModelScope.launch {
            ConversionForegroundService.result.collect { serviceResult ->
                when (serviceResult) {
                    is ServiceResult.Idle -> { /* no-op */ }
                    is ServiceResult.Running -> {
                        _state.value = _state.value.copy(
                            isConverting = true,
                            progress = serviceResult.progress,
                            batchCurrentIndex = serviceResult.batchIndex,
                            batchTotal = serviceResult.batchTotal,
                            error = null
                        )
                    }
                    is ServiceResult.Success -> {
                        _state.value = _state.value.copy(
                            isConverting = false,
                            progress = 1f,
                            batchCurrentIndex = serviceResult.totalFiles,
                            batchTotal = serviceResult.totalFiles,
                            convertedFile = serviceResult.file,
                            convertedFiles = serviceResult.files,
                            success = true,
                            error = null
                        )
                    }
                    is ServiceResult.Failure -> {
                        _state.value = _state.value.copy(
                            isConverting = false,
                            error = serviceResult.error,
                            success = false
                        )
                    }
                }
            }
        }
    }

    fun selectFile(uri: Uri, fileName: String) {
        selectFiles(listOf(SelectedFileItem(uri, fileName)))
    }

    fun selectFiles(items: List<SelectedFileItem>) {
        if (items.isEmpty()) {
            _state.value = _state.value.copy(
                selectedFiles = emptyList(),
                sourceFormat = null,
                error = null,
                success = false,
                convertedFile = null,
                convertedFiles = null
            )
            return
        }
        val firstFormat = FileFormat.fromExtension(items.first().fileName.substringAfterLast('.', ""))
        _state.value = _state.value.copy(
            selectedFiles = items,
            sourceFormat = firstFormat,
            error = null,
            success = false,
            convertedFile = null,
            convertedFiles = null
        )
    }

    fun removeFileAt(index: Int) {
        val list = _state.value.selectedFiles.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            val newFormat = list.firstOrNull()?.let { FileFormat.fromExtension(it.fileName.substringAfterLast('.', "")) }
            _state.value = _state.value.copy(
                selectedFiles = list,
                sourceFormat = newFormat,
                convertedFile = null,
                convertedFiles = null,
                success = false
            )
        }
    }

    fun setTargetFormat(targetFormat: FileFormat) {
        _state.value = _state.value.copy(targetFormat = targetFormat)
    }

    fun setOutputAsZip(asZip: Boolean) {
        _state.value = _state.value.copy(outputAsZip = asZip)
    }

    /**
     * Copies selected URIs to temp files (requires Activity context for URI permissions),
     * then hands the work off to [ConversionForegroundService] which runs the conversion
     * in its own coroutine scope — surviving Activity/ViewModel destruction.
     */
    fun convertFile(context: Context, scale: Float? = null, quality: QualityPreset = QualityPreset.MEDIUM, toolOptions: MediaToolOptions = MediaToolOptions()) {
        val currentState = _state.value
        val targetFormat = currentState.targetFormat ?: return
        val files = currentState.selectedFiles
        if (files.isEmpty()) return
        val totalFiles = files.size
        val ctx = context.applicationContext
        appContext = ctx

        ConversionForegroundService.resetResult()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _state.value = currentState.copy(
                        isConverting = true,
                        progress = 0f,
                        batchCurrentIndex = 0,
                        batchTotal = totalFiles,
                        error = null
                    )
                }

                val tempFiles = mutableListOf<File>()
                for ((i, item) in files.withIndex()) {
                    val temp = createTempFile(context, "${i}_${item.fileName}")
                    tempFiles.add(temp)
                    context.contentResolver.openInputStream(item.uri)?.use { input ->
                        FileOutputStream(temp).use { output -> input.copyTo(output) }
                    }
                }

                ConversionForegroundService.pendingWork = ConversionWork(
                    tempFiles = tempFiles,
                    targetFormat = targetFormat,
                    sourceFormat = currentState.sourceFormat,
                    outputAsZip = currentState.outputAsZip,
                    scale = scale,
                    quality = quality,
                    toolOptions = toolOptions
                )
                ConversionForegroundService.start(ctx, totalFiles)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        isConverting = false,
                        error = e.message ?: "Failed to prepare files",
                        success = false
                    )
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
        ConversionForegroundService.resetResult()
        _state.value = ConversionState()
    }

    fun cancelConversion() {
        appContext?.let { ConversionForegroundService.stop(it) }
        ConversionForegroundService.resetResult()
        _state.value = _state.value.copy(
            isConverting = false,
            error = "Conversion cancelled",
            success = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        // Intentionally NOT cancelling the service — conversion survives activity destruction.
    }
}
