package ab.inque.converter

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        else -> "" // multi: use label_files_selected_count in UI
    }
    val isMultiFile: Boolean get() = selectedFiles.size > 1
}

class ConverterViewModel : ViewModel() {
    private val _state = MutableStateFlow(ConversionState())
    val state: StateFlow<ConversionState> = _state.asStateFlow()
    private var conversionJob: Job? = null

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

    fun convertFile(context: Context, scale: Float? = null) {
        val currentState = _state.value
        val targetFormat = currentState.targetFormat ?: return
        val files = currentState.selectedFiles
        if (files.isEmpty()) return

        conversionJob?.cancel()
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            val job = conversionJob!!
            val totalFiles = files.size
            val timeoutMs = (60000L * totalFiles).coerceAtMost(300_000L)
            withTimeout(timeoutMs) {
                val tempFiles = mutableListOf<File>()
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

                    for ((i, item) in files.withIndex()) {
                        if (!job.isActive) throw CancellationException()
                        val temp = createTempFile(context, "${i}_${item.fileName}")
                        tempFiles.add(temp)
                        context.contentResolver.openInputStream(item.uri)?.use { input ->
                            FileOutputStream(temp).use { output -> input.copyTo(output) }
                        }
                        withContext(Dispatchers.Main) {
                            _state.value = _state.value.copy(
                                progress = (i + 1).toFloat() / (totalFiles * 2)
                            )
                        }
                    }

                    val universalConverter = UniversalConverter()
                    val progressCallback: (Float) -> Unit = { p ->
                        if (!job.isActive) throw CancellationException()
                        viewModelScope.launch(Dispatchers.Main) {
                            _state.value = _state.value.copy(progress = p)
                        }
                    }

                    val result = if (totalFiles == 1) {
                        val sourceFormat = FileFormat.fromExtension(tempFiles[0].extension) ?: currentState.sourceFormat
                            ?: throw IllegalArgumentException("Unsupported format")
                        ConvertResult.Single(universalConverter.convertFile(tempFiles[0], targetFormat, context, progressCallback, scale))
                    } else {
                        convertMultiple(context, tempFiles, targetFormat, currentState.sourceFormat, currentState.outputAsZip, universalConverter, job, progressCallback, scale)
                    }

                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            isConverting = false,
                            progress = 1f,
                            batchCurrentIndex = totalFiles,
                            convertedFile = if (result is ConvertResult.Single) result.file else null,
                            convertedFiles = if (result is ConvertResult.Multiple) result.files else null,
                            success = true
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            isConverting = false,
                            error = when (e) {
                                is TimeoutCancellationException -> "Conversion timed out."
                                else -> e.message ?: "Conversion failed"
                            },
                            success = false
                        )
                    }
                } finally {
                    tempFiles.forEach { if (it.exists()) it.delete() }
                }
            }
        }
    }

    private suspend fun convertMultiple(
        context: Context,
        tempFiles: List<File>,
        targetFormat: FileFormat,
        firstSourceFormat: FileFormat?,
        outputAsZip: Boolean,
        universalConverter: UniversalConverter,
        job: Job,
        progressCallback: (Float) -> Unit,
        scale: Float?
    ): ConvertResult {
        val total = tempFiles.size
        val allImages = tempFiles.all { f ->
            FileFormat.fromExtension(f.extension)?.category == FormatCategory.IMAGE
        }
        val singlePdf = allImages && targetFormat == FileFormat.PDF
        if (singlePdf) {
            val file = universalConverter.convertImagesToPdf(tempFiles, context) { p ->
                if (!job.isActive) throw CancellationException()
                viewModelScope.launch(Dispatchers.Main) {
                    _state.value = _state.value.copy(progress = 0.2f + 0.8f * p, batchCurrentIndex = total, batchTotal = total)
                }
            }
            return ConvertResult.Single(file)
        }
        val outputs = mutableListOf<File>()
        for ((i, file) in tempFiles.withIndex()) {
            if (!job.isActive) throw CancellationException()
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(batchCurrentIndex = i + 1, batchTotal = total)
            }
            val srcFormat = FileFormat.fromExtension(file.extension) ?: firstSourceFormat
                ?: throw IllegalArgumentException("Unsupported format: ${file.name}")
            val p0 = i.toFloat() / total
            val p1 = (i + 1).toFloat() / total
            val out = universalConverter.convertFile(file, targetFormat, context, { p -> progressCallback(p0 + (p1 - p0) * p) }, scale)
            outputs.add(out)
        }
        if (outputs.size == 1) return ConvertResult.Single(outputs[0])
        if (!outputAsZip) return ConvertResult.Multiple(outputs.toList())
        val zipFile = File(context.cacheDir, "converted_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            outputs.forEachIndexed { _, file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        outputs.forEach { it.delete() }
        return ConvertResult.Single(zipFile)
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
        conversionJob?.cancel()
        conversionJob = null
        _state.value = _state.value.copy(
            isConverting = false,
            error = "Conversion cancelled",
            success = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        conversionJob?.cancel()
        conversionJob = null
    }
} 