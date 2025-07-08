package ab.inque.converter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import ab.inque.converter.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: ConverterViewModel by viewModels()
    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var fileTypeAdapter: ArrayAdapter<String>? = null
    private var fromFormatAdapter: ArrayAdapter<String>? = null
    private var toFormatAdapter: ArrayAdapter<String>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openFilePicker()
        } else {
            Toast.makeText(this, "Storage permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedFileUri = it
            selectedFileName = getFileName(it) ?: "unknown_file"
            updateUI()
        }
    }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.state.value.convertedFile?.let { file ->
                    copyFileToLocation(file, uri)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFormatSpinners()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupClickListeners() {
        binding.selectFileButton.setOnClickListener {
            if (checkStoragePermission()) {
                openFilePicker()
            } else {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        binding.convertButton.setOnClickListener {
            selectedFileUri?.let { uri ->
                viewModel.selectFile(uri, selectedFileName)
                // Get selected target format
                val selectedToFormat = binding.toFormatSpinner.text.toString()
                val targetFormat = FileFormat.values().find { it.displayName == selectedToFormat }
                if (targetFormat != null) {
                    viewModel.setTargetFormat(targetFormat)
                    viewModel.convertFile(this)
                } else {
                    Toast.makeText(this, "Please select a target format", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.resetButton.setOnClickListener {
            resetUI()
        }

        binding.cancelButton.setOnClickListener {
            viewModel.cancelConversion()
        }

        binding.viewFileButton.setOnClickListener {
            viewModel.state.value.convertedFile?.let { file ->
                openFile(file)
            }
        }

        binding.shareFileButton.setOnClickListener {
            viewModel.state.value.convertedFile?.let { file ->
                shareFile(file)
            }
        }

        binding.saveToDownloadsButton.setOnClickListener {
            viewModel.state.value.convertedFile?.let { file ->
                saveToDownloads(file)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                updateUIFromState(state)
            }
        }
    }

    private fun updateUIFromState(state: ConversionState) {
        // Update file selection
        if (state.selectedFileName.isNotEmpty()) {
            binding.selectedFileName.text = "Selected: ${state.selectedFileName}"
            binding.selectedFileName.visibility = View.VISIBLE
            binding.selectFileButton.text = "Change File"
            
            // Show conversion section
            binding.conversionCard.visibility = View.VISIBLE
            
            // Update target format display
            val targetFormatText = state.targetFormat?.displayName ?: "Select target format"
            binding.targetFormat.text = "Convert to $targetFormatText"
        } else {
            binding.selectedFileName.visibility = View.GONE
            binding.selectFileButton.text = "Choose File"
            binding.conversionCard.visibility = View.GONE
        }

        // Update progress
        if (state.isConverting) {
            binding.progressCard.visibility = View.VISIBLE
            binding.progressBar.progress = (state.progress * 100).toInt()
            binding.progressText.text = "${(state.progress * 100).toInt()}%"
            
            val statusText = when {
                state.progress < 0.3f -> "Preparing file..."
                state.progress < 0.5f -> "Processing content..."
                state.progress < 0.8f -> "Generating output..."
                else -> "Finalizing..."
            }
            binding.statusText.text = statusText
        } else {
            binding.progressCard.visibility = View.GONE
        }

        // Update result
        if (state.success && state.convertedFile != null) {
            binding.resultCard.visibility = View.VISIBLE
            binding.convertedFileName.text = "Ready to save: ${state.convertedFile!!.name}"
            binding.fileSize.text = "Size: ${formatFileSize(state.convertedFile!!.length())}"
        } else {
            binding.resultCard.visibility = View.GONE
        }

        // Update error
        if (state.error != null) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = state.error
        } else {
            binding.errorCard.visibility = View.GONE
        }
    }

    private fun updateUI() {
        if (selectedFileName.isNotEmpty()) {
            binding.selectedFileName.text = "Selected: $selectedFileName"
            binding.selectedFileName.visibility = View.VISIBLE
            binding.selectFileButton.text = "Change File"
            
            binding.conversionCard.visibility = View.VISIBLE
            
            // Update target format display
            val targetFormatText = viewModel.state.value.targetFormat?.displayName ?: "Select target format"
            binding.targetFormat.text = "Convert to $targetFormatText"
        }
    }

    private fun resetUI() {
        selectedFileUri = null
        selectedFileName = ""
        binding.selectedFileName.visibility = View.GONE
        binding.selectFileButton.text = "Choose File"
        binding.fileSelectionCard.visibility = View.GONE
        binding.conversionCard.visibility = View.GONE
        binding.progressCard.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.errorCard.visibility = View.GONE
        
        // Reset spinners
        binding.fileTypeSpinner.setText("", false)
        binding.fromFormatSpinner.setText("", false)
        binding.toFormatSpinner.setText("", false)
        
        viewModel.resetState()
    }

    private fun checkStoragePermission(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupFormatSpinners() {
        // Setup file type category spinner
        val fileTypes = listOf("Document", "Image", "Audio", "Video")
        fileTypeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, fileTypes)
        binding.fileTypeSpinner.setAdapter(fileTypeAdapter)
        
        // Setup "From" format spinner (initially empty)
        fromFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList())
        binding.fromFormatSpinner.setAdapter(fromFormatAdapter)
        
        // Setup "To" format spinner (initially empty)
        toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList())
        binding.toFormatSpinner.setAdapter(toFormatAdapter)
        
        // Update format options when file type changes
        binding.fileTypeSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedCategory = when (position) {
                0 -> FormatCategory.DOCUMENT
                1 -> FormatCategory.IMAGE
                2 -> FormatCategory.AUDIO
                3 -> FormatCategory.VIDEO
                else -> FormatCategory.DOCUMENT
            }
            
            val formatsForCategory = FileFormat.getFormatsByCategory(selectedCategory)
            val formatNames = formatsForCategory.map { it.displayName }
            
            // Update "From" format spinner
            fromFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, formatNames)
            binding.fromFormatSpinner.setAdapter(fromFormatAdapter)
            binding.fromFormatSpinner.setText("", false)
            
            // Clear "To" format spinner
            toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList())
            binding.toFormatSpinner.setAdapter(toFormatAdapter)
            binding.toFormatSpinner.setText("", false)
            
            // Show file selection section
            binding.fileSelectionCard.visibility = View.VISIBLE
        }
        
        // Update "To" formats when "From" format changes
        binding.fromFormatSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedCategory = when (binding.fileTypeSpinner.text.toString()) {
                "Document" -> FormatCategory.DOCUMENT
                "Image" -> FormatCategory.IMAGE
                "Audio" -> FormatCategory.AUDIO
                "Video" -> FormatCategory.VIDEO
                else -> FormatCategory.DOCUMENT
            }
            
            val formatsForCategory = FileFormat.getFormatsByCategory(selectedCategory)
            if (position < formatsForCategory.size) {
                val selectedFromFormat = formatsForCategory[position]
                val compatibleFormats = FileFormat.getSupportedConversions(selectedFromFormat)
                val compatibleFormatNames = compatibleFormats.map { it.displayName }
                
                toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, compatibleFormatNames)
                binding.toFormatSpinner.setAdapter(toFormatAdapter)
                binding.toFormatSpinner.setText("", false)
            }
        }
    }

    private fun openFilePicker() {
        val selectedFileType = binding.fileTypeSpinner.text.toString()
        
        val mimeType = when (selectedFileType) {
            "Document" -> "*/*"
            "Image" -> "image/*"
            "Audio" -> "audio/*"
            "Video" -> "video/*"
            else -> "*/*"
        }
        
        filePickerLauncher.launch(mimeType)
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".epub", ignoreCase = true) -> "application/epub+zip"
            fileName.endsWith(".html", ignoreCase = true) -> "text/html"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${(bytes / 1024.0).toInt()} KB"
            bytes < 1024 * 1024 * 1024 -> "${(bytes / (1024.0 * 1024.0)).toInt()} MB"
            else -> "${(bytes / (1024.0 * 1024.0 * 1024.0)).toInt()} GB"
        }
    }

    private fun openFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
            shareFile(file)
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.provider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = getMimeType(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Converted file: ${file.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share converted file"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToDownloads(file: File) {
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = getMimeType(file.name)
                putExtra(Intent.EXTRA_TITLE, file.name)
            }
            saveFileLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
            shareFile(file)
        }
    }

    private fun copyFileToLocation(sourceFile: File, destinationUri: Uri) {
        try {
            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            // Get the filename from the destination URI for better feedback
            val fileName = getFileNameFromUri(destinationUri) ?: sourceFile.name
            Toast.makeText(this, "File saved as: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }
}