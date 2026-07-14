package ab.inque.converter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.media.ExifInterface
import android.os.Bundle
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
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
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchasesParams
import ab.inque.converter.pipeline.FormatSniffer
import ab.inque.converter.pipeline.FormatResolver
import ab.inque.converter.R
import ab.inque.converter.databinding.ActivityMainBinding
import ab.inque.converter.databinding.DialogConverterInfoBinding
import ab.inque.converter.preview.FloatingPreviewOverlay
import ab.inque.converter.preview.PreviewRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
        private const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-1522396353318857/2027629318"
        private const val REWARD_POINTS = 3
        private val SPEED_VALUES = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
        private val SPEED_LABEL_IDS = intArrayOf(
            R.string.speed_025, R.string.speed_05, R.string.speed_075, R.string.speed_1,
            R.string.speed_15, R.string.speed_2, R.string.speed_3, R.string.speed_4
        )
        private const val PREMIUM_PRODUCT_ID = "premium_upgrade"
    }

    private var premiumRestoreAttempted = false

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ConverterViewModel by viewModels()
    private var toFormatAdapter: ArrayAdapter<String>? = null
    private var infoDialog: AlertDialog? = null
    private var fileToSaveOnResult: File? = null
    private var conversionDecrementedForCurrentSuccess = false
    private var conversionCostForCurrentSuccess = 0
    private var mediaDurationMs: Long = 0L
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

    private var pendingConversionTargetFormat: FileFormat? = null
    private var currentSourceFormat: FileFormat? = null
    private var ttsVoiceLoader: android.speech.tts.TextToSpeech? = null
    private var availableTtsVoices: List<android.speech.tts.Voice> = emptyList()
    private var floatingPreviewOverlay: FloatingPreviewOverlay? = null
    private var suppressLivePreviewSwitchCallback = false
    private val previewReloadRunnable = Runnable { reloadFloatingPreviewIfActive() }
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val target = pendingConversionTargetFormat
        pendingConversionTargetFormat = null
        if (granted && target != null) {
            startConversion(target)
        } else if (!granted) {
            Toast.makeText(this, getString(R.string.error_record_audio_permission), Toast.LENGTH_LONG).show()
        }
    }

    private fun onFilesPicked(uris: List<Uri>) {
        val items = uris.map { uri ->
            SelectedFileItem(uri, getFileName(uri) ?: "file_${uri.hashCode()}")
        }
        viewModel.selectFiles(items)
        warnIfMixedCategories(items)
        applySelectionUI(items)
    }

    private fun resolveSourceFormat(item: SelectedFileItem): FileFormat? {
        FormatResolver.fromExtension(item.fileName)?.let { return it }
        return try {
            contentResolver.openInputStream(item.uri)?.use { FormatSniffer.sniffStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun warnIfMixedCategories(items: List<SelectedFileItem>) {
        if (items.size < 2) return
        val categories = items.mapNotNull { resolveSourceFormat(it)?.category }.distinct()
        if (categories.size > 1) {
            Toast.makeText(this, getString(R.string.warn_mixed_file_types), Toast.LENGTH_LONG).show()
        }
    }

    /** Updates conversion card, format spinner, quality/media tools based on selected files. */
    private fun applySelectionUI(items: List<SelectedFileItem>) {
        if (items.isEmpty()) return
        val firstFormat = resolveSourceFormat(items.first())
        if (firstFormat != null) {
            currentSourceFormat = firstFormat
            if (items.size == 1 && firstFormat.category == FormatCategory.IMAGE) {
                loadImageDimensions(items.first().uri)
                binding.imageScalingCard.visibility = View.VISIBLE
            } else {
                binding.imageScalingCard.visibility = View.GONE
            }
            val showQuality = firstFormat.category == FormatCategory.IMAGE ||
                    firstFormat.category == FormatCategory.VIDEO
            binding.qualityContainer.visibility = if (showQuality) View.VISIBLE else View.GONE
            if (!showQuality) binding.qualityMedium.isChecked = true

            val isVideoOrAudio = firstFormat.category == FormatCategory.VIDEO ||
                    firstFormat.category == FormatCategory.AUDIO
            val isVideoOrImage = firstFormat.category == FormatCategory.VIDEO ||
                    firstFormat.category == FormatCategory.IMAGE
            binding.mediaToolsCard.visibility = if (isVideoOrAudio || isVideoOrImage) View.VISIBLE else View.GONE
            if (isVideoOrAudio || isVideoOrImage) {
                binding.trimSection.visibility = if (isVideoOrAudio) View.VISIBLE else View.GONE
                binding.speedSection.visibility = if (isVideoOrAudio) View.VISIBLE else View.GONE
                binding.flipSection.visibility = if (isVideoOrImage) View.VISIBLE else View.GONE
                binding.rotateSection.visibility = if (isVideoOrImage) View.VISIBLE else View.GONE
                binding.exportFramesSection.visibility =
                    if (firstFormat.category == FormatCategory.VIDEO) View.VISIBLE else View.GONE

                if (items.size == 1 && isVideoOrAudio) {
                    configureTrimSlider(items.first().uri)
                }
            }

            binding.conversionCard.visibility = View.VISIBLE
            populateToFormats(firstFormat)
            updateVideoConversionToolsVisibility()
            scrollToView(binding.convertToCard)
        } else {
            val ext = items.first().fileName.substringAfterLast('.', "")
            Toast.makeText(this, getString(R.string.error_unsupported_format, ext), Toast.LENGTH_SHORT).show()
        }
    }

    /** Kicks off the actual conversion once any required permissions are satisfied. */
    private fun startConversion(targetFormat: FileFormat) {
        val files = viewModel.state.value.selectedFiles
        if (files.isEmpty()) return
        conversionDecrementedForCurrentSuccess = false
        conversionCostForCurrentSuccess = files.size
        viewModel.setTargetFormat(targetFormat)
        val scale = getImageScale()
        val quality = getSelectedQuality()
        val toolOptions = getMediaToolOptions()
        viewModel.convertFile(this, scale, quality, toolOptions)
    }

    private fun buildPreviewRequest(): PreviewRequest? {
        val files = viewModel.state.value.selectedFiles
        if (files.isEmpty()) return null
        val targetFormat = resolveSelectedTargetFormat() ?: return null
        val sourceFormat = currentSourceFormat ?: resolveSourceFormat(files.first()) ?: return null
        val item = files.first()
        return PreviewRequest(
            uri = item.uri,
            fileName = item.fileName,
            sourceFormat = sourceFormat,
            targetFormat = targetFormat,
            mimeType = getMimeType(item.fileName),
            durationMs = mediaDurationMs,
            toolOptions = getMediaToolOptions(),
            scale = getImageScale()
        )
    }

    private fun reloadFloatingPreviewIfActive() {
        if (!binding.livePreviewSwitch.isChecked) return
        val request = buildPreviewRequest() ?: run {
            setLivePreviewChecked(false)
            return
        }
        floatingPreviewOverlay?.show(request)
    }

    private fun schedulePreviewReload() {
        if (!binding.livePreviewSwitch.isChecked) return
        binding.root.removeCallbacks(previewReloadRunnable)
        binding.root.postDelayed(previewReloadRunnable, 400L)
    }

    private fun setLivePreviewChecked(checked: Boolean) {
        suppressLivePreviewSwitchCallback = true
        binding.livePreviewSwitch.isChecked = checked
        suppressLivePreviewSwitchCallback = false
        if (checked) {
            reloadFloatingPreviewIfActive()
        } else {
            floatingPreviewOverlay?.hide()
        }
    }

    private fun setupFloatingPreview() {
        floatingPreviewOverlay = FloatingPreviewOverlay(
            host = binding.floatingPreviewHost,
            lifecycleOwner = this,
            context = this,
            onCloseRequested = { setLivePreviewChecked(false) }
        )
        binding.livePreviewSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressLivePreviewSwitchCallback) return@setOnCheckedChangeListener
            if (isChecked) {
                reloadFloatingPreviewIfActive()
            } else {
                floatingPreviewOverlay?.hide()
            }
        }
    }

    /** True when the selected (audio) files are being transcribed to a document. */
    private fun requiresMicPermission(files: List<SelectedFileItem>, targetFormat: FileFormat): Boolean {
        if (files.isEmpty()) return false
        val sourceFormat = resolveSourceFormat(files.first()) ?: return false
        if (targetFormat.category != FormatCategory.DOCUMENT) return false
        return when (sourceFormat.category) {
            FormatCategory.AUDIO -> true
            FormatCategory.VIDEO -> targetFormat != FileFormat.PDF
            else -> false
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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
        setupFloatingPreview()
        setupClickListeners()
        setupTtsSettings()
        observeViewModel()
        initializeRewardedAds()
        updateConversionsBar()
    }

    override fun onPause() {
        floatingPreviewOverlay?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        updateConversionsBar()
        tryRestorePremiumFromPlayIfNeeded()
    }

    /**
     * Restores premium status from Google Play (no app login). Runs once per process when user
     * is not unlocked, so reinstalls or new devices get premium back via Play's purchase record.
     */
    private fun tryRestorePremiumFromPlayIfNeeded() {
        if (ConversionPrefs.isUnlocked(this)) return
        if (premiumRestoreAttempted) return
        premiumRestoreAttempted = true

        val billingClient = BillingClient.newBuilder(this)
            .setListener(PurchasesUpdatedListener { _, _ -> /* restore-only: handled via queryPurchasesAsync */ })
            .enablePendingPurchases()
            .build()
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    billingClient.endConnection()
                    return
                }
                fun checkAndUnlock(purchases: List<Purchase>): Boolean =
                    purchases.any { p ->
                        p.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            p.products.contains(PREMIUM_PRODUCT_ID)
                    }
                fun applyRestoreIfFound(hasPremium: Boolean) {
                    if (hasPremium && !isFinishing) {
                        ConversionPrefs.setUnlocked(this@MainActivity, true)
                        runOnUiThread { updateConversionsBar() }
                    }
                    billingClient.endConnection()
                }
                val inappParams = QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
                billingClient.queryPurchasesAsync(inappParams) { result, purchases ->
                    if (result.responseCode == BillingClient.BillingResponseCode.OK && checkAndUnlock(purchases)) {
                        applyRestoreIfFound(true)
                        return@queryPurchasesAsync
                    }
                    val subsParams = QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                    billingClient.queryPurchasesAsync(subsParams) { subsResult, subsPurchases ->
                        applyRestoreIfFound(
                            subsResult.responseCode == BillingClient.BillingResponseCode.OK && checkAndUnlock(subsPurchases)
                        )
                    }
                }
            }
            override fun onBillingServiceDisconnected() {
                billingClient.endConnection()
            }
        })
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
        if (ConversionPrefs.isUnlocked(this)) {
            binding.conversionCardTitle.text = getString(R.string.title_convert_section)
            return
        }
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

        binding.linkMediaButton.setOnClickListener {
            showLinkMediaDialog()
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
            if (targetFormat == null) {
                Toast.makeText(this, getString(R.string.error_select_target_format), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Audio → Document transcription needs the microphone permission for on-device speech recognition.
            if (requiresMicPermission(files, targetFormat) && !hasRecordAudioPermission()) {
                pendingConversionTargetFormat = targetFormat
                recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }
            startConversion(targetFormat)
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

        binding.debugTogglePremiumText.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.debugTogglePremiumText.setOnClickListener {
            val currentlyUnlocked = ConversionPrefs.isUnlocked(this)
            ConversionPrefs.setUnlocked(this, !currentlyUnlocked)
            updateConversionsBar()
            updateConversionCardTitle(viewModel.state.value.selectedFiles.size)
            Toast.makeText(
                this,
                getString(if (currentlyUnlocked) R.string.toast_debug_premium_off else R.string.toast_debug_premium_on),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.resetButton.setOnClickListener {
            resetUI()
        }

        binding.errorStartOverButton.setOnClickListener {
            resetUI()
        }

        // Media tools toggle listeners
        binding.clipModeGroup.setOnCheckedChangeListener { _, _ ->
            updateClipModePanels()
            updateVideoConversionToolsVisibility()
            schedulePreviewReload()
        }
        binding.trimRangeSlider.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            if (values.size >= 2) {
                binding.trimStartLabel.text = formatMs(values[0].toLong() * 1000L)
                binding.trimEndLabel.text = formatMs(values[1].toLong() * 1000L)
            }
        }
        binding.trimRangeSlider.addOnSliderTouchListener(object : com.google.android.material.slider.RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.RangeSlider) {
                schedulePreviewReload()
            }
        })
        binding.extractFrameSlider.addOnChangeListener { _, value, _ ->
            binding.extractFrameTimeLabel.text = formatMs(value.toLong() * 1000L)
        }
        binding.extractFrameSlider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                schedulePreviewReload()
            }
        })
        binding.exportFramesCheckBox.setOnCheckedChangeListener { _, checked ->
            if (checked && binding.clipModeExtractFrame.isChecked) {
                binding.clipModeGroup.check(R.id.clipModeTrim)
            }
            schedulePreviewReload()
        }
        binding.speedCheckBox.setOnCheckedChangeListener { _, checked ->
            binding.speedOptions.visibility = if (checked) View.VISIBLE else View.GONE
            schedulePreviewReload()
        }
        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val index = value.toInt().coerceIn(0, SPEED_LABEL_IDS.lastIndex)
                binding.speedValueLabel.text = getString(SPEED_LABEL_IDS[index])
                schedulePreviewReload()
            }
        }
        binding.flipHorizontalCheckBox.setOnCheckedChangeListener { _, _ -> schedulePreviewReload() }
        binding.flipVerticalCheckBox.setOnCheckedChangeListener { _, _ -> schedulePreviewReload() }
        binding.rotateGroup.setOnCheckedChangeListener { _, _ -> schedulePreviewReload() }

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
            scrollToView(binding.resultCardMulti)
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
            scrollToView(binding.resultCard)
        } else {
            conversionDecrementedForCurrentSuccess = false
            binding.resultCard.visibility = View.GONE
            binding.resultCardMulti.visibility = View.GONE
        }

        // Update error
        if (state.error != null) {
            binding.errorCard.visibility = View.VISIBLE
            binding.errorText.text = state.error
            scrollToView(binding.errorCard)
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
        binding.qualityContainer.visibility = View.GONE
        binding.qualityMedium.isChecked = true
        binding.mediaToolsCard.visibility = View.GONE
        binding.ttsSettingsSection.visibility = View.GONE
        currentSourceFormat = null
        binding.clipModeGroup.clearCheck()
        updateClipModePanels()
        mediaDurationMs = 0L
        binding.trimRangeSlider.valueTo = 100f
        binding.trimRangeSlider.values = listOf(0f, 100f)
        binding.trimStartLabel.text = "0:00"
        binding.trimEndLabel.text = "0:00"
        binding.extractFrameSlider.valueTo = 100f
        binding.extractFrameSlider.value = 0f
        binding.extractFrameTimeLabel.text = "0:00"
        binding.exportFramesCheckBox.isChecked = false
        binding.speedCheckBox.isChecked = false
        binding.speedSlider.value = 3f
        binding.speedValueLabel.text = getString(R.string.speed_1)
        binding.flipHorizontalCheckBox.isChecked = false
        binding.flipVerticalCheckBox.isChecked = false
        binding.rotateNone.isChecked = true
        binding.progressCard.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.resultCardMulti.visibility = View.GONE
        binding.convertedFilesList.removeAllViews()
        binding.errorCard.visibility = View.GONE
        binding.toFormatSpinner.setText("", false)
        binding.convertButton.isEnabled = false
        binding.convertButton.alpha = 0.5f
        setLivePreviewChecked(false)
        binding.livePreviewSwitch.isEnabled = false
        viewModel.resetState()
    }

    private fun setupFormatSpinners() {
        toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, emptyList())
        binding.toFormatSpinner.setAdapter(toFormatAdapter)
        binding.fileSelectionCard.visibility = View.VISIBLE
        binding.convertButton.isEnabled = false
        binding.convertButton.alpha = 0.5f
        binding.livePreviewSwitch.isEnabled = false
        setLivePreviewChecked(false)

        binding.toFormatSpinner.setOnItemClickListener { _, _, _, _ ->
            val hasSelection = binding.toFormatSpinner.text.toString().isNotBlank()
            binding.convertButton.isEnabled = hasSelection
            binding.convertButton.alpha = if (hasSelection) 1.0f else 0.5f
            binding.livePreviewSwitch.isEnabled = hasSelection
            updateTtsSettingsVisibility(currentSourceFormat, binding.toFormatSpinner.text.toString())
            updateVideoConversionToolsVisibility()
            schedulePreviewReload()
        }
    }

    private fun setupTtsSettings() {
        val rate = ConversionPrefs.getTtsSpeechRate(this)
        val progress = (rate * 100).toInt().coerceIn(50, 200)
        binding.ttsSpeechRateSeekBar.progress = progress
        binding.ttsSpeechRateValue.text = String.format("%.1fx", progress / 100f)
        binding.ttsSpeechRateSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                val r = value / 100f
                binding.ttsSpeechRateValue.text = String.format("%.1fx", r)
                if (fromUser) ConversionPrefs.setTtsSpeechRate(this@MainActivity, r)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
        ttsVoiceLoader = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                runOnUiThread { populateTtsVoiceSpinner() }
            }
        }
    }

    private fun populateTtsVoiceSpinner() {
        val tts = ttsVoiceLoader ?: return
        val voices = tts.voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.sortedBy { it.name }
            ?: emptyList()
        availableTtsVoices = voices
        val labels = mutableListOf(getString(R.string.tts_voice_default))
        labels.addAll(voices.map { "${it.name} (${it.locale.displayName})" })
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.ttsVoiceSpinner.adapter = adapter
        val saved = ConversionPrefs.getTtsVoiceName(this)
        val selected = if (saved == null) {
            0
        } else {
            val idx = voices.indexOfFirst { it.name == saved }
            if (idx < 0) 0 else idx + 1
        }
        binding.ttsVoiceSpinner.setSelection(selected)
        binding.ttsVoiceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = if (position == 0) null else voices.getOrNull(position - 1)?.name
                ConversionPrefs.setTtsVoiceName(this@MainActivity, name)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateTtsSettingsVisibility(source: FileFormat?, targetName: String?) {
        if (source == null || targetName.isNullOrBlank()) {
            binding.ttsSettingsSection.visibility = View.GONE
            return
        }
        val target = FileFormat.entries.find { it.displayName == targetName }
        val show = source.category == FormatCategory.DOCUMENT && target?.category == FormatCategory.AUDIO
        binding.ttsSettingsSection.visibility = if (show) View.VISIBLE else View.GONE
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

    private fun showLinkMediaDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = getString(R.string.hint_link_media_url)
            setPadding(48, 48, 48, 48)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_link_media)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Clear focus and hide keyboard before dialog dismisses to avoid IME/InputChannel crash on some devices (e.g. OnePlus)
                editText.clearFocus()
                (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(editText.windowToken, 0)
                val urlStr = editText.text?.toString()?.trim() ?: return@setPositiveButton
                if (urlStr.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_link_media_fetching), Toast.LENGTH_SHORT).show()
                    try {
                        val item = withContext(Dispatchers.IO) { fetchMediaFromLink(urlStr) }
                        withContext(Dispatchers.Main) {
                            if (item != null) {
                                viewModel.selectFiles(listOf(item))
                                applySelectionUI(listOf(item))
                                val ext = item.fileName.substringAfterLast('.', "")
                                val isPage = ext.equals("html", ignoreCase = true)
                                Toast.makeText(
                                    this@MainActivity,
                                    if (isPage) getString(R.string.toast_link_media_page_saved) else getString(R.string.toast_link_media_success),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(this@MainActivity, getString(R.string.toast_link_media_error, getString(R.string.toast_link_media_no_media)), Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, getString(R.string.toast_link_media_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Fetches the webpage: if it contains direct video/audio/PDF, downloads that and returns it;
     * otherwise (or if download fails) saves the page as HTML so PDF is always available in "Convert to".
     * Returns null only when the page itself cannot be fetched.
     */
    private suspend fun fetchMediaFromLink(pageUrl: String): SelectedFileItem? = withContext(Dispatchers.IO) {
        val html = fetchPageHtml(pageUrl) ?: return@withContext null
        val mediaResult = extractMediaUrlFromPageContent(html, pageUrl)
        if (mediaResult != null) {
            val (mediaUrl, mimeType) = mediaResult
            val file = downloadToCache(mediaUrl, mimeType)
            if (file != null) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    this@MainActivity,
                    "${this@MainActivity.packageName}.provider",
                    file
                )
                return@withContext SelectedFileItem(uri, file.name)
            }
        }
        // No direct media or download failed: save page as HTML so PDF is always available in "Convert to"
        val file = saveHtmlToCache(html, pageUrl) ?: return@withContext null
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this@MainActivity,
            "${this@MainActivity.packageName}.provider",
            file
        )
        SelectedFileItem(uri, file.name)
    }

    /** Returns (url, mimeType) for the media to download, or null if none found. Uses already-fetched HTML. */
    private fun extractMediaUrlFromPageContent(html: String, pageUrl: String): Pair<String, String?>? {
        if (LinkPageMediaExtractor.isYouTubeWatchUrl(pageUrl)) {
            extractYouTubeStreamUrlAndMime(html)?.let { return it }
        }
        return LinkPageMediaExtractor.extractDirectMedia(html, pageUrl)
    }

    /** Saves HTML to cache with base URL so PDF conversion can resolve images and formatting. */
    private fun saveHtmlToCache(html: String, pageUrl: String): File? = try {
        val baseUrl = baseUrlFromPageUrl(pageUrl)
        val content = if (baseUrl != null) "<!-- BASE_URL:$baseUrl -->\n$html" else html
        val baseName = "page_${System.currentTimeMillis()}"
        val file = File(cacheDir, "$baseName.html")
        file.writeText(content, Charsets.UTF_8)
        file
    } catch (e: Exception) {
        Log.e(TAG, "saveHtmlToCache failed", e)
        null
    }

    /** Returns origin (scheme + host + /) for resolving relative URLs, or null. */
    private fun baseUrlFromPageUrl(pageUrl: String): String? = try {
        val u = URL(pageUrl)
        "${u.protocol}://${u.host}/"
    } catch (e: Exception) {
        null
    }

    /**
     * Extracts a direct stream URL and mime type from YouTube watch page HTML.
     * Tries ytInitialPlayerResponse first, then any script containing "streamingData".
     */
    private fun extractYouTubeStreamUrlAndMime(html: String): Pair<String, String?>? {
        extractYouTubeStreamFromPlayerResponse(html, "ytInitialPlayerResponse")?.let { return it }
        extractYouTubeStreamFromPlayerResponse(html, "ytInitialPlayerResponse =")?.let { return it }
        extractYouTubeStreamFromJsonObject(html, "streamingData")?.let { return it }
        return null
    }

    private fun extractYouTubeStreamFromPlayerResponse(html: String, startMarker: String): Pair<String, String?>? {
        val idx = html.indexOf(startMarker)
        if (idx < 0) return null
        val afterMarker = html.indexOf('=', idx + startMarker.length).let { if (it < 0) idx + startMarker.length else it + 1 }
        val braceStart = html.indexOf('{', afterMarker)
        if (braceStart < 0) return null
        val jsonStr = extractBalancedJson(html, braceStart) ?: return null
        return parseStreamingDataFromJson(jsonStr)
    }

    private fun extractYouTubeStreamFromJsonObject(html: String, key: String): Pair<String, String?>? {
        for (pattern in listOf("\"$key\"", key)) {
            var idx = 0
            var pos: Int
            while (html.indexOf(pattern, idx).also { pos = it } >= 0) {
                idx = pos
                val afterKey = idx + pattern.length
                val colon = html.indexOf(':', afterKey)
                if (colon >= 0 && colon - afterKey <= 3) {
                    val braceStart = html.indexOf('{', colon)
                    if (braceStart >= 0) {
                        extractBalancedJson(html, braceStart)?.let { jsonStr ->
                            parseStreamingDataFromJson("{\"streamingData\":$jsonStr}")?.let { return it }
                        }
                    }
                }
                idx++
            }
        }
        return null
    }

    private fun extractBalancedJson(html: String, start: Int): String? {
        val openChar = html[start]
        val closeChar = when (openChar) {
            '{' -> '}'
            '[' -> ']'
            else -> return null
        }
        var depth = 1
        var i = start + 1
        while (i < html.length && depth > 0) {
            when (html[i]) {
                openChar -> depth++
                closeChar -> depth--
                '"' -> {
                    i++
                    while (i < html.length) {
                        if (html[i] == '"' && html[i - 1] != '\\') break
                        i++
                    }
                }
            }
            i++
        }
        return if (depth == 0) html.substring(start, i) else null
    }

    private fun parseStreamingDataFromJson(jsonStr: String): Pair<String, String?>? {
        return try {
            val root = JSONObject(jsonStr)
            val streamingData = root.optJSONObject("streamingData") ?: return null
            fun fromFormat(fmt: JSONObject): Pair<String, String?>? {
                val url = fmt.optString("url", "")
                if (url.isBlank()) return null
                val mime = fmt.optString("mimeType", "").substringBefore(";").trim()
                return url to mime.ifBlank { null }
            }
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                for (j in 0 until formats.length()) {
                    fromFormat(formats.optJSONObject(j) ?: continue)?.let { return it }
                }
            }
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (j in 0 until adaptiveFormats.length()) {
                    fromFormat(adaptiveFormats.optJSONObject(j) ?: continue)?.let { return it }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchPageHtml(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            conn = url.openConnection() as? HttpURLConnection ?: return null
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { it.bufferedReader().readText() }
        } catch (e: Exception) {
            Log.e(TAG, "fetchPageHtml failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun mimeTypeToExtension(mime: String?): String? {
        if (mime.isNullOrBlank()) return null
        val lower = mime.lowercase().substringBefore(";").trim()
        return when {
            lower.startsWith("video/mp4") || lower == "video/mp4" -> "mp4"
            lower.startsWith("audio/mp4") || lower == "audio/mp4" -> "m4a"
            lower.startsWith("video/webm") -> "webm"
            lower.startsWith("audio/webm") -> "webm"
            lower.startsWith("audio/mpeg") -> "mp3"
            lower.startsWith("audio/ogg") -> "ogg"
            lower.startsWith("audio/flac") -> "flac"
            lower.startsWith("audio/wav") || lower.startsWith("audio/wave") -> "wav"
            lower.startsWith("video/x-msvideo") -> "avi"
            lower.startsWith("video/quicktime") -> "mov"
            lower.startsWith("video/3gpp") -> "3gp"
            lower.contains("pdf") -> "pdf"
            else -> null
        }
    }

    private fun downloadToCache(mediaUrl: String, mimeType: String? = null): File? {
        return try {
            val url = URL(mediaUrl)
            val conn = url.openConnection() as? URLConnection ?: return null
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // googlevideo.com often returns 403 without browser-like Referer/Origin
            if (mediaUrl.contains("googlevideo.com")) {
                conn.setRequestProperty("Referer", "https://www.youtube.com/")
                conn.setRequestProperty("Origin", "https://www.youtube.com")
            }
            var ext = mimeTypeToExtension(mimeType) ?: run {
                var e = mediaUrl.substringAfterLast('.', "").substringBefore('?').lowercase()
                    .takeIf { it.length in 1..5 } ?: ""
                if (e.isEmpty() && mediaUrl.contains("googlevideo.com")) e = "mp4"
                if (e.isEmpty()) e = "bin"
                e
            }
            val safeExt = if (ext in listOf("mp4", "webm", "mp3", "m4a", "wav", "ogg", "flac", "pdf", "avi", "mov", "3gp")) ext else "bin"
            val file = File(cacheDir, "link_media_${System.currentTimeMillis()}.$safeExt")
            conn.getInputStream().use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            if (file.length() > 0) file else null.also { file.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "downloadToCache failed", e)
            null
        }
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
            fileName.endsWith(".htm", ignoreCase = true) -> "text/html"
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
            fileName.endsWith(".3gp", ignoreCase = true) -> "video/3gpp"
            fileName.endsWith(".gif", ignoreCase = true) -> "image/gif"
            
            // Audio formats
            fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
            fileName.endsWith(".wav", ignoreCase = true) -> "audio/wav"
            fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
            fileName.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
            fileName.endsWith(".flac", ignoreCase = true) -> "audio/flac"
            
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
                schedulePreviewReload()
            }
        }
        
        binding.heightRadio.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                isWidthSelected = false
                updateDimensionInput()
                schedulePreviewReload()
            }
        }
        
        // Text input listener
        binding.newDimensionInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateNewSizePreview()
                schedulePreviewReload()
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
    
    private fun getSelectedQuality(): QualityPreset = when {
        binding.qualityHigh.isChecked -> QualityPreset.HIGH
        binding.qualityLow.isChecked  -> QualityPreset.LOW
        else                          -> QualityPreset.MEDIUM
    }

    private fun getMediaToolOptions(): MediaToolOptions {
        val sliderValues = binding.trimRangeSlider.values
        val trimMode = binding.clipModeTrim.isChecked
        val extractFrameMode = binding.clipModeExtractFrame.isChecked
        val trimStartMs = if (trimMode && sliderValues.size >= 2) sliderValues[0].toLong() * 1000L else 0L
        val trimEndMs = if (trimMode && sliderValues.size >= 2) sliderValues[1].toLong() * 1000L else 0L
        val extractFrameMs = if (extractFrameMode) binding.extractFrameSlider.value.toLong() * 1000L else 0L
        val speedFactor = if (binding.speedCheckBox.isChecked) {
            val index = binding.speedSlider.value.toInt().coerceIn(0, SPEED_VALUES.lastIndex)
            SPEED_VALUES[index]
        } else 1.0f
        val rotationDegrees = when (binding.rotateGroup.checkedRadioButtonId) {
            R.id.rotate90 -> 90
            R.id.rotate180 -> 180
            R.id.rotate270 -> 270
            else -> 0
        }
        return MediaToolOptions(
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            speedFactor = speedFactor,
            flipHorizontal = binding.flipHorizontalCheckBox.isChecked,
            flipVertical = binding.flipVerticalCheckBox.isChecked,
            rotationDegrees = rotationDegrees,
            exportAllVideoFrames = binding.exportFramesCheckBox.isChecked,
            extractFrame = extractFrameMode,
            extractFrameMs = extractFrameMs
        )
    }

    private fun updateClipModePanels() {
        val trimMode = binding.clipModeTrim.isChecked
        val extractFrameMode = binding.clipModeExtractFrame.isChecked
        binding.trimInputs.visibility = if (trimMode) View.VISIBLE else View.GONE
        binding.extractFrameInputs.visibility = if (extractFrameMode) View.VISIBLE else View.GONE
        if (extractFrameMode) {
            binding.exportFramesCheckBox.isChecked = false
        }
    }

    private fun resolveSelectedTargetFormat(): FileFormat? {
        val text = binding.toFormatSpinner.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return FileFormat.entries.find { it.displayName == text }
    }

    private fun targetSupportsExtractFrame(targetFormat: FileFormat): Boolean =
        targetFormat == FileFormat.JPG ||
            targetFormat == FileFormat.JPEG ||
            targetFormat.category == FormatCategory.DOCUMENT

    private fun updateExtractFrameVisibility() {
        val isVideo = currentSourceFormat?.category == FormatCategory.VIDEO
        val target = resolveSelectedTargetFormat()
        val show = isVideo && target != null && targetSupportsExtractFrame(target)
        binding.clipModeExtractFrame.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && binding.clipModeExtractFrame.isChecked) {
            binding.clipModeGroup.clearCheck()
            updateClipModePanels()
        }
    }

    private fun updateExportFramesVisibility() {
        val isVideo = currentSourceFormat?.category == FormatCategory.VIDEO
        val showExportAll = isVideo && !binding.clipModeExtractFrame.isChecked
        binding.exportFramesSection.visibility = if (showExportAll) View.VISIBLE else View.GONE
    }

    private fun updateVideoConversionToolsVisibility() {
        updateExtractFrameVisibility()
        updateExportFramesVisibility()
    }

    private fun configureTrimSlider(uri: android.net.Uri) {
        try {
            val retriever = android.media.MediaMetadataRetriever()
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            }
            val durationMs = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            retriever.release()

            mediaDurationMs = durationMs
            val totalSec = (durationMs / 1000f).coerceAtLeast(1f)
            val midSec = totalSec / 2f
            // Force LTR so touch position matches track (avoids RTL width-scale / inverted thumb)
            binding.trimRangeSlider.layoutDirection = View.LAYOUT_DIRECTION_LTR
            binding.trimRangeSlider.valueFrom = 0f
            binding.trimRangeSlider.valueTo = totalSec
            binding.trimRangeSlider.stepSize = if (totalSec > 600) 1f else 0f
            binding.trimRangeSlider.values = listOf(0f, totalSec)
            binding.trimStartLabel.text = formatMs(0)
            binding.trimEndLabel.text = formatMs(durationMs)

            binding.extractFrameSlider.layoutDirection = View.LAYOUT_DIRECTION_LTR
            binding.extractFrameSlider.valueFrom = 0f
            binding.extractFrameSlider.valueTo = totalSec
            binding.extractFrameSlider.stepSize = if (totalSec > 600) 1f else 0f
            binding.extractFrameSlider.value = midSec
            binding.extractFrameTimeLabel.text = formatMs((midSec * 1000f).toLong())
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Could not read media duration", e)
            mediaDurationMs = 0L
            binding.trimRangeSlider.valueFrom = 0f
            binding.trimRangeSlider.valueTo = 100f
            binding.trimRangeSlider.values = listOf(0f, 100f)
            binding.extractFrameSlider.valueFrom = 0f
            binding.extractFrameSlider.valueTo = 100f
            binding.extractFrameSlider.value = 0f
            binding.extractFrameTimeLabel.text = "0:00"
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
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
            val orientation = contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapGeometryUtil.readExifOrientation(inputStream)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val (displayW, displayH) = BitmapGeometryUtil.displayDimensions(
                options.outWidth,
                options.outHeight,
                orientation
            )
            originalImageWidth = displayW
            originalImageHeight = displayH
            binding.originalImageInfo.text = getString(R.string.label_original_size, originalImageWidth, originalImageHeight)
            binding.newDimensionInput.setText(originalImageWidth.toString())
            updateNewSizePreview()
        } catch (e: Exception) {
            originalImageWidth = 0
            originalImageHeight = 0
        }
    }
    
    private fun populateToFormats(sourceFormat: FileFormat) {
        currentSourceFormat = sourceFormat
        val compatibleFormats = FileFormat.getSupportedConversions(sourceFormat, this)
        val compatibleFormatNames = compatibleFormats.map { it.displayName }
        
        toFormatAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, compatibleFormatNames)
        binding.toFormatSpinner.setAdapter(toFormatAdapter)
        binding.toFormatSpinner.setText("", false)
        binding.convertButton.isEnabled = false
        binding.convertButton.alpha = 0.5f
        binding.livePreviewSwitch.isEnabled = false
        setLivePreviewChecked(false)
        updateTtsSettingsVisibility(sourceFormat, null)
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

    private fun scrollToView(target: View) {
        target.post {
            binding.mainScrollView.smoothScrollTo(0, target.top)
        }
    }

    override fun onDestroy() {
        binding.root.removeCallbacks(previewReloadRunnable)
        floatingPreviewOverlay?.hide()
        ttsVoiceLoader?.shutdown()
        ttsVoiceLoader = null
        infoDialog?.dismiss()
        infoDialog = null
        rewardedAd = null
        super.onDestroy()
    }
}