package ab.inque.converter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ab.inque.converter.BuildConfig
import ab.inque.converter.R
import ab.inque.converter.databinding.ActivityMainBinding
import ab.inque.converter.databinding.DialogConverterInfoBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
        private const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-1522396353318857/2027629318"
        private const val REWARD_POINTS = 3
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ConverterViewModel by viewModels()
    private var toFormatAdapter: ArrayAdapter<String>? = null
    private var infoDialog: AlertDialog? = null
    private var fileToSaveOnResult: File? = null
    private var conversionDecrementedForCurrentSuccess = false
    private var conversionCostForCurrentSuccess = 0
    private var rewardedAd: RewardedAd? = null
    private var isLoadingRewardedAd = false
    private var rewardedInThisShow = false
    private var pendingRewardedAdShow = false

    // Image scaling variables
    private var originalImageWidth: Int = 0
    private var originalImageHeight: Int = 0
    private var isWidthSelected: Boolean = true

    private val filePickerMultipleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onFilesPicked(uris)
    }

    private fun onFilesPicked(uris: List<Uri>) {
        val items = uris.map { uri ->
            SelectedFileItem(uri, getFileName(uri) ?: "file_${uri.hashCode()}")
        }
        viewModel.selectFiles(items)
        val firstFormat = FileFormat.fromExtension(items.first().fileName.substringAfterLast('.', ""))
        if (firstFormat != null) {
            if (items.size == 1 && firstFormat.category == FormatCategory.IMAGE) {
                loadImageDimensions(items.first().uri)
                binding.imageScalingCard.visibility = View.VISIBLE
            } else {
                binding.imageScalingCard.visibility = View.GONE
            }
            binding.conversionCard.visibility = View.VISIBLE
            populateToFormats(firstFormat)
        } else {
            val ext = items.first().fileName.substringAfterLast('.', "")
            Toast.makeText(this, getString(R.string.error_unsupported_format, ext), Toast.LENGTH_SHORT).show()
        }
    }

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                (fileToSaveOnResult ?: viewModel.state.value.convertedFile)?.let { file ->
                    copyFileToLocation(file, uri)
                }
                fileToSaveOnResult = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupFormatSpinners()
        setupClickListeners()
        observeViewModel()
        initializeRewardedAds()
        updateConversionsBar()
    }

    override fun onResume() {
        super.onResume()
        updateConversionsBar()
    }

    private fun updateConversionsBar() {
        val unlocked = ConversionPrefs.isUnlocked(this)
        if (unlocked) {
            binding.conversionsBar.setBackgroundResource(R.drawable.bg_overlay_glass_gold)
            binding.conversionsBarSecondRow.visibility = View.GONE
        } else {
            binding.conversionsBar.setBackgroundResource(R.drawable.bg_overlay_glass)
            binding.conversionsBarSecondRow.visibility = View.VISIBLE
            val points = ConversionPrefs.getPoints(this)
            binding.conversionsLeftText.text = getString(R.string.label_points_emoji, points)
            binding.currencyIcon.visibility = View.GONE
            binding.watchVideosButton.visibility = View.VISIBLE
            binding.unlockFullButton.visibility = View.VISIBLE
        }
    }

    /** True if user can afford to convert [fileCount] files (1 point per file). */
    private fun canConvert(fileCount: Int): Boolean {
        if (ConversionPrefs.isUnlocked(this)) return true
        return ConversionPrefs.getPoints(this) >= fileCount
    }

    /** Sets the conversion card title to "3. Convert (X 💎)" with current cost; emoji at 70% size. */
    private fun updateConversionCardTitle(cost: Int) {
        val text = getString(R.string.title_convert_with_cost, cost)
        val span = SpannableString(text)
        val emoji = "💎"
        val start = text.indexOf(emoji)
        if (start >= 0) {
            span.setSpan(RelativeSizeSpan(0.7f), start, start + emoji.length, 0)
        }
        binding.conversionCardTitle.text = span
    }

    private fun setupClickListeners() {
        binding.selectFileButton.setOnClickListener {
            openFilePicker()
        }
        
        binding.infoButton.setOnClickListener {
            showInfoDialog()
        }

        // Setup image scaling listeners
        setupImageScalingListeners()

        binding.convertButton.setOnClickListener {
            val files = viewModel.state.value.selectedFiles
            if (files.isEmpty()) return@setOnClickListener
            val cost = files.size
            if (!canConvert(cost)) {
                Toast.makeText(this, getString(R.string.toast_not_enough_points, cost), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedToFormat = binding.toFormatSpinner.text.toString()
            val targetFormat = FileFormat.values().find { it.displayName == selectedToFormat }
            if (targetFormat != null) {
                conversionCostForCurrentSuccess = cost
                viewModel.setTargetFormat(targetFormat)
                val scale = getImageScale()
                viewModel.convertFile(this, scale)
            } else {
                Toast.makeText(this, getString(R.string.error_select_target_format), Toast.LENGTH_SHORT).show()
            }
        }

        binding.watchVideosButton.setOnClickListener {
            showRewardedAd()
        }
        binding.unlockFullButton.setOnClickListener {
            if (BuildConfig.DEBUG) {
                // Debug: upgrade immediately for testing
                ConversionPrefs.setUnlocked(this, true)
                Toast.makeText(this, getString(R.string.toast_purchase_success), Toast.LENGTH_SHORT).show()
                updateConversionsBar()
            } else {
                // Release: use in-app purchase flow (product id handled in PurchaseActivity)
                startActivity(Intent(this, PurchaseActivity::class.java))
            }
        }

        binding.contactUsText.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:inqueapps@gmail.com?subject=umc%20feedback")
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.contact_us_chooser)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.contact_us_no_app), Toast.LENGTH_SHORT).show()
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

        binding.selectedFileName.setOnClickListener {
            val files = viewModel.state.value.selectedFiles
            if (files.size == 1) {
                previewSelectedFile(files[0].uri, files[0].fileName)
            } else if (files.isNotEmpty()) {
                Toast.makeText(this, getString(R.string.hint_select_file_to_preview), Toast.LENGTH_SHORT).show()
            }
        }

        binding.shareFileButton.setOnClickListener {
            viewModel.state.value.convertedFile?.let { file ->
                shareFile(file)
            }
        }

        binding.saveToDownloadsButton.setOnClickListener {
            viewModel.state.value.convertedFile?.let { file ->
                fileToSaveOnResult = null
                saveToDownloads(file)
            }
        }

        binding.outputOptionZip.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.setOutputAsZip(true)
        }
        binding.outputOptionSeparate.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) viewModel.setOutputAsZip(false)
        }
    }

    private fun rewardedAdUnitId(): String {
        return if (BuildConfig.DEBUG) TEST_REWARDED_AD_UNIT_ID else PROD_REWARDED_AD_UNIT_ID
    }

    private fun initializeRewardedAds() {
        MobileAds.initialize(this) {
            loadRewardedAd()
        }
    }

    private fun loadRewardedAd() {
        if (isLoadingRewardedAd) return
        isLoadingRewardedAd = true
        rewardedAd = null

        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            this,
            rewardedAdUnitId(),
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    isLoadingRewardedAd = false
                    rewardedAd = ad
                    if (pendingRewardedAdShow) {
                        pendingRewardedAdShow = false
                        showRewardedAd()
                    }
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() {
                            rewardedAd = null
                            if (rewardedInThisShow) {
                                updateConversionsBar()
                            }
                            loadRewardedAd()
                        }

                        override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                            Log.w(TAG, "Rewarded ad failed to show: ${adError.code} ${adError.message}")
                            rewardedAd = null
                            loadRewardedAd()
                        }
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    isLoadingRewardedAd = false
                    rewardedAd = null
                    Log.w(TAG, "Rewarded ad failed to load: ${adError.code} ${adError.message}")
                    if (pendingRewardedAdShow) {
                        pendingRewardedAdShow = false
                        Toast.makeText(this@MainActivity, getString(R.string.toast_ad_load_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun showRewardedAd() {
        val ad = rewardedAd
        if (ad == null) {
            pendingRewardedAdShow = true
            Toast.makeText(this, getString(R.string.toast_ad_not_ready), Toast.LENGTH_SHORT).show()
            loadRewardedAd()
            return
        }

        rewardedInThisShow = false
        ad.show(this) { _: RewardItem ->
            rewardedInThisShow = true
            ConversionPrefs.addPoints(this, REWARD_POINTS)
            Toast.makeText(this, getString(R.string.toast_video_watched, REWARD_POINTS), Toast.LENGTH_SHORT).show()
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
        if (state.selectedFiles.isNotEmpty()) {
            binding.selectedFileName.visibility = View.VISIBLE
            binding.selectedFileName.isClickable = state.selectedFiles.size == 1
            binding.selectedFileName.isEnabled = state.selectedFiles.size == 1
            if (state.selectedFiles.size == 1) {
                binding.selectedFileName.text = getString(R.string.label_selected_file, state.selectedFileName)
                binding.selectedFilesListScroll.visibility = View.GONE
            } else {
                binding.selectedFileName.text = getString(R.string.label_files_selected_count, state.selectedFiles.size)
                binding.selectedFilesListScroll.visibility = View.VISIBLE
                populateSelectedFilesList(state.selectedFiles)
            }
            binding.selectFileButton.text = getString(R.string.button_add_or_change_files)
            binding.convertToCard.visibility = View.VISIBLE
            binding.outputOptionContainer.visibility = if (state.isMultiFile) View.VISIBLE else View.GONE
            if (state.isMultiFile) {
                binding.outputOptionZip.isChecked = state.outputAsZip
                binding.outputOptionSeparate.isChecked = !state.outputAsZip
            }
            updateConversionCardTitle(state.selectedFiles.size)
        } else {
            binding.selectedFileName.visibility = View.GONE
            binding.selectedFilesListScroll.visibility = View.GONE
            binding.outputOptionContainer.visibility = View.GONE
            binding.convertToCard.visibility = View.GONE
            binding.selectFileButton.text = getString(R.string.button_choose_files)
            binding.conversionCard.visibility = View.GONE
            binding.imageScalingCard.visibility = View.GONE
            binding.selectedFileName.isClickable = false
            binding.selectedFileName.isEnabled = false
        }

        // Update progress
        if (state.isConverting) {
            binding.progressCard.visibility = View.VISIBLE
            binding.progressBar.progress = (state.progress * 100).toInt()
            binding.progressText.text = "${(state.progress * 100).toInt()}%"
            val statusText = if (state.batchTotal > 1) {
                getString(R.string.status_converting_file_n_of_m, state.batchCurrentIndex, state.batchTotal)
            } else {
                when {
                    state.progress < 0.1f -> getString(R.string.status_starting)
                    state.progress < 0.2f -> getString(R.string.status_validating)
                    state.progress < 0.3f -> getString(R.string.status_extracting)
                    state.progress < 0.5f -> getString(R.string.status_processing)
                    state.progress < 0.7f -> getString(R.string.status_converting)
                    state.progress < 0.9f -> getString(R.string.status_generating)
                    else -> getString(R.string.status_finalizing)
                }
            }
            binding.statusText.text = statusText
        } else {
            binding.progressCard.visibility = View.GONE
        }

        // Update result: single file/zip vs multiple files list
        if (state.success && state.convertedFiles != null && state.convertedFiles.isNotEmpty()) {
            if (!conversionDecrementedForCurrentSuccess && !ConversionPrefs.isUnlocked(this) && conversionCostForCurrentSuccess > 0) {
                ConversionPrefs.spendPoints(this, conversionCostForCurrentSuccess)
                conversionDecrementedForCurrentSuccess = true
                updateConversionsBar()
            }
            binding.resultCard.visibility = View.GONE
            binding.resultCardMulti.visibility = View.VISIBLE
            populateConvertedFilesList(state.convertedFiles)
        } else if (state.success && state.convertedFile != null) {
            if (!conversionDecrementedForCurrentSuccess && !ConversionPrefs.isUnlocked(this) && conversionCostForCurrentSuccess > 0) {
                ConversionPrefs.spendPoints(this, conversionCostForCurrentSuccess)
                conversionDecrementedForCurrentSuccess = true
                updateConversionsBar()
            }
            binding.resultCard.visibility = View.VISIBLE
            binding.resultCardMulti.visibility = View.GONE
            binding.convertedFileName.text = getString(R.string.label_ready_to_save, state.convertedFile!!.name)
            binding.fileSize.text = getString(R.string.label_file_size, formatFileSize(state.convertedFile!!.length()))
        } else {
            conversionDecrementedForCurrentSuccess = false
            binding.resultCard.visibility = View.GONE
            binding.resultCardMulti.visibility = View.GONE
        }

        // Update error
        if (state.error != null) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = state.error
        } else {
            binding.errorCard.visibility = View.GONE
        }
    }

    private fun resetUI() {
        conversionDecrementedForCurrentSuccess = false
        conversionCostForCurrentSuccess = 0
        binding.selectedFileName.visibility = View.GONE
        binding.selectedFilesListScroll.visibility = View.GONE
        binding.selectedFilesList.removeAllViews()
        binding.selectedFileName.isClickable = false
        binding.selectedFileName.isEnabled = false
        binding.selectFileButton.text = getString(R.string.button_choose_files)
        binding.fileSelectionCard.visibility = View.VISIBLE
        binding.convertToCard.visibility = View.GONE
        binding.conversionCard.visibility = View.GONE
        binding.imageScalingCard.visibility = View.GONE
        binding.progressCard.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.resultCardMulti.visibility = View.GONE
        binding.convertedFilesList.removeAllViews()
        binding.errorCard.visibility = View.GONE
        binding.toFormatSpinner.setText("", false)
        viewModel.resetState()
    }

    private fun setupFormatSpinners() {
        toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList())
        binding.toFormatSpinner.setAdapter(toFormatAdapter)
        binding.fileSelectionCard.visibility = View.VISIBLE
    }

    private fun populateSelectedFilesList(files: List<SelectedFileItem>) {
        binding.selectedFilesList.removeAllViews()
        val dp = (resources.displayMetrics.density).toInt()
        files.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp * 4, 0, dp * 4)
            }
            val nameView = TextView(this).apply {
                text = item.fileName
                setTextColor(resources.getColor(R.color.btn_glass_text, theme))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setSingleLine()
            }
            val removeBtn = ImageButton(this).apply {
                setImageResource(R.drawable.ic_close_red)
                background = null
                setPadding(dp * 4, dp * 4, dp * 4, dp * 4)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { viewModel.removeFileAt(index) }
            }
            row.addView(nameView)
            row.addView(removeBtn)
            binding.selectedFilesList.addView(row)
        }
    }

    private fun populateConvertedFilesList(files: List<File>) {
        binding.convertedFilesList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        files.forEach { file ->
            val item = inflater.inflate(R.layout.item_converted_file, binding.convertedFilesList, false)
            item.findViewById<TextView>(R.id.itemFileName).text = file.name
            item.findViewById<TextView>(R.id.itemFileSize).text = formatFileSize(file.length())
            item.findViewById<View>(R.id.itemViewButton).setOnClickListener { openFile(file) }
            item.findViewById<View>(R.id.itemShareButton).setOnClickListener { shareFile(file) }
            item.findViewById<View>(R.id.itemSaveButton).setOnClickListener {
                fileToSaveOnResult = file
                saveToDownloads(file)
            }
            binding.convertedFilesList.addView(item)
        }
    }

    private fun openFilePicker() {
        filePickerMultipleLauncher.launch(arrayOf("*/*"))
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
            fileName.endsWith(".heic", ignoreCase = true) -> "image/heic"
            fileName.endsWith(".heif", ignoreCase = true) -> "image/heif"
            
            // Video formats
            fileName.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
            fileName.endsWith(".avi", ignoreCase = true) -> "video/x-msvideo"
            fileName.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
            fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
            fileName.endsWith(".webm", ignoreCase = true) -> "video/webm"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            
            // Audio formats
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            
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
                    Toast.makeText(this, getString(R.string.error_no_app_to_open), Toast.LENGTH_SHORT).show()
                    shareFile(file)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error opening file: ${e.message}")
            Toast.makeText(this, getString(R.string.error_opening_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
            shareFile(file)
        }
    }

    private fun previewSelectedFile(uri: Uri, fileName: String) {
        try {
            val mimeType = getMimeType(fileName)
            val previewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (previewIntent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(previewIntent, "Preview with"))
            } else {
                val genericIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                if (genericIntent.resolveActivity(packageManager) != null) {
                    startActivity(Intent.createChooser(genericIntent, "Preview with"))
                } else {
                    Toast.makeText(this, getString(R.string.error_no_app_to_preview), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error previewing file: ${e.message}")
            Toast.makeText(this, getString(R.string.error_unable_to_preview), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.error_sharing_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.error_saving_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.toast_file_saved, fileName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_saving_file, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
            }
            originalImageWidth = options.outWidth
            originalImageHeight = options.outHeight
            binding.originalImageInfo.text = getString(R.string.label_original_size, originalImageWidth, originalImageHeight)
            binding.newDimensionInput.setText(originalImageWidth.toString())
            updateNewSizePreview()
        } catch (e: Exception) {
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

    private fun showInfoDialog() {
        if (infoDialog?.isShowing == true) return
        val dialogBinding = DialogConverterInfoBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialogBinding.infoDialogClose.setOnClickListener { dialog.dismiss() }
        infoDialog = dialog
        dialog.setOnDismissListener { infoDialog = null }
        dialog.show()
    }

    override fun onDestroy() {
        infoDialog?.dismiss()
        infoDialog = null
        rewardedAd = null
        super.onDestroy()
    }
}