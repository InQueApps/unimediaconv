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
    
    // Image scaling variables
    private var originalImageWidth: Int = 0
    private var originalImageHeight: Int = 0
    private var isWidthSelected: Boolean = true

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
            
            // Detect file type and update UI accordingly
            val fileExtension = selectedFileName.substringAfterLast('.', "")
            android.util.Log.d("MainActivity", "File extension: $fileExtension")
            val detectedFormat = FileFormat.fromExtension(fileExtension)
            android.util.Log.d("MainActivity", "Detected format: ${detectedFormat?.displayName}")
            
            if (detectedFormat != null) {
                android.util.Log.d("MainActivity", "File category: ${detectedFormat.category}")
                
                // Load image dimensions if it's an image file
                if (detectedFormat.category == FormatCategory.IMAGE) {
                    android.util.Log.d("MainActivity", "Image file detected, loading dimensions")
                    loadImageDimensions(it)
                    binding.imageScalingCard.visibility = View.VISIBLE
                } else {
                    binding.imageScalingCard.visibility = View.GONE
                }
                
                // Show conversion format section and populate "To" formats
                binding.conversionCard.visibility = View.VISIBLE
                populateToFormats(detectedFormat)
                
                android.util.Log.d("MainActivity", "Conversion options populated for ${detectedFormat.displayName}")
            } else {
                android.util.Log.e("MainActivity", "Could not detect file format for extension: $fileExtension")
                Toast.makeText(this, "Unsupported file format: $fileExtension", Toast.LENGTH_SHORT).show()
            }
            
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
        // Setup expandable header
        binding.expandArrow.setOnClickListener {
            binding.expandableInfo.visibility = View.VISIBLE
            binding.expandArrow.visibility = View.GONE
        }
        
        binding.collapseArrow.setOnClickListener {
            binding.expandableInfo.visibility = View.GONE
            binding.expandArrow.visibility = View.VISIBLE
        }
        
        binding.selectFileButton.setOnClickListener {
            if (checkStoragePermission()) {
                openFilePicker()
            } else {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        // Setup image scaling listeners
        setupImageScalingListeners()

        binding.convertButton.setOnClickListener {
            selectedFileUri?.let { uri ->
                viewModel.selectFile(uri, selectedFileName)
                // Get selected target format
                val selectedToFormat = binding.toFormatSpinner.text.toString()
                val targetFormat = FileFormat.values().find { it.displayName == selectedToFormat }
                if (targetFormat != null) {
                    viewModel.setTargetFormat(targetFormat)
                    
                    // Get image scaling if applicable
                    val scale = getImageScale()
                    viewModel.convertFile(this, scale)
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
            
            // Update target format display
            val targetFormatText = state.targetFormat?.displayName ?: "Select target format"
        } else {
            binding.selectedFileName.visibility = View.GONE
            binding.selectFileButton.text = "Choose File"
            binding.conversionCard.visibility = View.GONE
            binding.imageScalingCard.visibility = View.GONE
        }

        // Update progress
        if (state.isConverting) {
            binding.progressCard.visibility = View.VISIBLE
            binding.progressBar.progress = (state.progress * 100).toInt()
            binding.progressText.text = "${(state.progress * 100).toInt()}%"
            
            val statusText = when {
                state.progress < 0.1f -> "Starting conversion..."
                state.progress < 0.2f -> "Validating file..."
                state.progress < 0.3f -> "Extracting content..."
                state.progress < 0.5f -> "Processing data..."
                state.progress < 0.7f -> "Converting format..."
                state.progress < 0.9f -> "Generating output..."
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
        }
    }

    private fun resetUI() {
        selectedFileUri = null
        selectedFileName = ""
        binding.selectedFileName.visibility = View.GONE
        binding.selectFileButton.text = "Choose File"
        binding.fileSelectionCard.visibility = View.VISIBLE
        binding.conversionCard.visibility = View.GONE
        binding.imageScalingCard.visibility = View.GONE
        binding.progressCard.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.errorCard.visibility = View.GONE
        
        // Reset spinners
        binding.toFormatSpinner.setText("", false)
        
        viewModel.resetState()
    }

    private fun checkStoragePermission(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupFormatSpinners() {
        // Setup "To" format spinner (initially empty)
        toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList())
        binding.toFormatSpinner.setAdapter(toFormatAdapter)
        
        // Show file selection section immediately
        binding.fileSelectionCard.visibility = View.VISIBLE
    }

    private fun openFilePicker() {
        // Allow all file types since we'll detect the type from the file extension
        filePickerLauncher.launch("*/*")
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
            // Document formats
            fileName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            fileName.endsWith(".epub", ignoreCase = true) -> "application/epub+zip"
            fileName.endsWith(".html", ignoreCase = true) -> "text/html"
            fileName.endsWith(".txt", ignoreCase = true) -> "text/plain"
            fileName.endsWith(".rtf", ignoreCase = true) -> "application/rtf"
            fileName.endsWith(".docx", ignoreCase = true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            
            // Image formats
            fileName.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            fileName.endsWith(".png", ignoreCase = true) -> "image/png"
            fileName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            fileName.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            fileName.endsWith(".tiff", ignoreCase = true) -> "image/tiff"
            fileName.endsWith(".tif", ignoreCase = true) -> "image/tiff"
            
            // Video formats
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            fileName.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            
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
            val mimeType = getMimeType(file.name)
            android.util.Log.d("MainActivity", "Opening file: ${file.name} with MIME type: $mimeType")
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            // Check if there's an app to handle this intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, "Open with"))
            } else {
                // No app found, try with a more generic MIME type
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                if (genericIntent.resolveActivity(packageManager) != null) {
                    startActivity(Intent.createChooser(genericIntent, "Open with"))
                } else {
                    Toast.makeText(this, "No app found to open this file type", Toast.LENGTH_SHORT).show()
                    shareFile(file)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error opening file: ${e.message}")
            Toast.makeText(this, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
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
    
    private fun setupImageScalingListeners() {
        // Radio button listeners
        binding.widthRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isWidthSelected = true
                updateDimensionInput()
            }
        }
        
        binding.heightRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isWidthSelected = false
                updateDimensionInput()
            }
        }
        
        // Text input listener
        binding.newDimensionInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateNewSizePreview()
            }
        })
    }
    
    private fun updateDimensionInput() {
        if (originalImageWidth > 0 && originalImageHeight > 0) {
            val currentValue = binding.newDimensionInput.text.toString().toIntOrNull() ?: 0
            val newValue = if (isWidthSelected) originalImageWidth else originalImageHeight
            binding.newDimensionInput.setText(newValue.toString())
            updateNewSizePreview()
        }
    }
    
    private fun updateNewSizePreview() {
        if (originalImageWidth > 0 && originalImageHeight > 0) {
            val inputValue = binding.newDimensionInput.text.toString().toIntOrNull() ?: 0
            if (inputValue > 0) {
                val newWidth: Int
                val newHeight: Int
                
                if (isWidthSelected) {
                    newWidth = inputValue
                    newHeight = (inputValue * originalImageHeight / originalImageWidth)
                } else {
                    newHeight = inputValue
                    newWidth = (inputValue * originalImageWidth / originalImageHeight)
                }
                
                binding.newSizePreview.text = "New size: ${newWidth} x ${newHeight}"
            }
        }
    }
    
    private fun getImageScale(): Float? {
        // Only return scale if image scaling card is visible (image conversion)
        if (binding.imageScalingCard.visibility == android.view.View.VISIBLE) {
            val inputValue = binding.newDimensionInput.text.toString().toIntOrNull() ?: 0
            if (inputValue > 0 && originalImageWidth > 0 && originalImageHeight > 0) {
                return if (isWidthSelected) {
                    inputValue.toFloat() / originalImageWidth
                } else {
                    inputValue.toFloat() / originalImageHeight
                }
            }
        }
        return null
    }
    
    private fun loadImageDimensions(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()
            
            originalImageWidth = options.outWidth
            originalImageHeight = options.outHeight
            
            // Update UI
            binding.originalImageInfo.text = "Original size: ${originalImageWidth} x ${originalImageHeight}"
            binding.newDimensionInput.setText(originalImageWidth.toString())
            updateNewSizePreview()
            
        } catch (e: Exception) {
            // Handle error silently
            originalImageWidth = 0
            originalImageHeight = 0
        }
    }
    
    private fun populateToFormats(sourceFormat: FileFormat) {
        val compatibleFormats = FileFormat.getSupportedConversions(sourceFormat)
        val compatibleFormatNames = compatibleFormats.map { it.displayName }
        
        toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, compatibleFormatNames)
        binding.toFormatSpinner.setAdapter(toFormatAdapter)
        binding.toFormatSpinner.setText("", false)
    }
}