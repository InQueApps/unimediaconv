package ab.inque.converter

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ab.inque.converter.databinding.ActivityPreviewBinding
import ab.inque.converter.preview.PreviewContent
import ab.inque.converter.preview.PreviewLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Studio-style in-app preview — shows conversion output without full encode.
 */
class PreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPreviewBinding
    private var player: ExoPlayer? = null
    private val previewBitmaps = mutableListOf<android.graphics.Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.previewToolbar.setNavigationOnClickListener { finish() }

        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        val source = intent.getStringExtra(EXTRA_SOURCE_FORMAT)?.let { FileFormat.valueOf(it) }
        val target = intent.getStringExtra(EXTRA_TARGET_FORMAT)?.let { FileFormat.valueOf(it) }
        val mime = intent.getStringExtra(EXTRA_MIME).orEmpty()

        if (uri == null || source == null || target == null) {
            showError(getString(R.string.preview_unavailable))
            return
        }

        val toolOptions = readToolOptions()
        val scale = intent.getFloatExtra(EXTRA_SCALE, -1f).takeIf { it > 0f }
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)

        showLoading(true)
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                PreviewLoader.load(
                    context = applicationContext,
                    uri = uri,
                    fileName = fileName,
                    sourceFormat = source,
                    targetFormat = target,
                    toolOptions = toolOptions,
                    scale = scale,
                    durationMs = durationMs,
                    mimeType = mime
                )
            }
            showLoading(false)
            renderContent(content)
        }
    }

    private fun readToolOptions(): MediaToolOptions = MediaToolOptions(
        trimStartMs = intent.getLongExtra(EXTRA_TRIM_START_MS, 0L),
        trimEndMs = intent.getLongExtra(EXTRA_TRIM_END_MS, 0L),
        speedFactor = intent.getFloatExtra(EXTRA_SPEED, 1f),
        flipHorizontal = intent.getBooleanExtra(EXTRA_FLIP_H, false),
        flipVertical = intent.getBooleanExtra(EXTRA_FLIP_V, false),
        rotationDegrees = intent.getIntExtra(EXTRA_ROTATION, 0),
        exportAllVideoFrames = intent.getBooleanExtra(EXTRA_EXPORT_FRAMES, false),
        extractFrame = intent.getBooleanExtra(EXTRA_EXTRACT_FRAME, false),
        extractFrameMs = intent.getLongExtra(EXTRA_EXTRACT_FRAME_MS, 0L)
    )

    private fun showLoading(loading: Boolean) {
        binding.previewLoading.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun hideAllContent() {
        binding.previewPlayerView.visibility = View.GONE
        binding.previewAudioPanel.visibility = View.GONE
        binding.previewPhotoView.visibility = View.GONE
        binding.previewPager.visibility = View.GONE
        binding.previewTextScroll.visibility = View.GONE
        binding.previewWebView.visibility = View.GONE
        binding.previewErrorPanel.visibility = View.GONE
    }

    private fun renderContent(content: PreviewContent) {
        hideAllContent()
        when (content) {
            is PreviewContent.MediaClip -> showMedia(content)
            is PreviewContent.StillImage -> showStillImage(content)
            is PreviewContent.PagedImages -> showPaged(content)
            is PreviewContent.TextSample -> showText(content)
            is PreviewContent.HtmlPage -> showHtml(content)
            is PreviewContent.Unavailable -> showError(content.message)
        }
    }

    private fun showMedia(content: PreviewContent.MediaClip) {
        binding.previewSubtitle.text = content.subtitle
        setDisclaimer(content.disclaimer)

        if (content.audioOnly) {
            binding.previewAudioPanel.visibility = View.VISIBLE
        }
        binding.previewPlayerView.visibility = View.VISIBLE

        releasePlayer()
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.previewPlayerView.player = exo
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(content.startMs)
                .apply {
                    if (content.endMs != Long.MAX_VALUE) {
                        setEndPositionMs(content.endMs)
                    }
                }
                .build()
            val item = MediaItem.Builder()
                .setUri(content.uri)
                .setMimeType(content.mimeType)
                .setClippingConfiguration(clipping)
                .build()
            exo.setMediaItem(item)
            exo.playbackParameters = exo.playbackParameters.withSpeed(content.speed)
            exo.repeatMode = Player.REPEAT_MODE_ONE
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    private fun showStillImage(content: PreviewContent.StillImage) {
        binding.previewSubtitle.text = content.subtitle
        setDisclaimer(content.disclaimer)
        val bmp = content.bitmaps.firstOrNull() ?: return showError(getString(R.string.preview_error))
        previewBitmaps.addAll(content.bitmaps)
        binding.previewPhotoView.visibility = View.VISIBLE
        binding.previewPhotoView.setImageBitmap(bmp)
    }

    private fun showPaged(content: PreviewContent.PagedImages) {
        binding.previewSubtitle.text = content.subtitle
        setDisclaimer(content.disclaimer)
        previewBitmaps.addAll(content.bitmaps)
        binding.previewPager.visibility = View.VISIBLE
        binding.previewPageIndicator.visibility = View.VISIBLE
        binding.previewPager.adapter = PreviewPageAdapter(content.bitmaps)
        binding.previewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.previewPageIndicator.text = getString(
                    R.string.preview_page_indicator,
                    position + 1,
                    content.bitmaps.size
                )
            }
        })
        binding.previewPageIndicator.text = getString(
            R.string.preview_page_indicator, 1, content.bitmaps.size
        )
        // ViewPager2 needs RecyclerView access for full bleed photos
        (binding.previewPager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun showText(content: PreviewContent.TextSample) {
        binding.previewSubtitle.text = content.subtitle
        setDisclaimer(content.disclaimer)
        binding.previewTextScroll.visibility = View.VISIBLE
        binding.previewTextBody.text = content.body
    }

    private fun showHtml(content: PreviewContent.HtmlPage) {
        binding.previewSubtitle.text = content.subtitle
        setDisclaimer(content.disclaimer)
        binding.previewWebView.visibility = View.VISIBLE
        binding.previewWebView.settings.apply {
            javaScriptEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        binding.previewWebView.webViewClient = WebViewClient()
        binding.previewWebView.loadUrl(content.uri.toString())
    }

    private fun setDisclaimer(text: String?) {
        if (text.isNullOrBlank()) {
            binding.previewDisclaimer.visibility = View.GONE
        } else {
            binding.previewDisclaimer.visibility = View.VISIBLE
            binding.previewDisclaimer.text = text
        }
    }

    private fun showError(message: String) {
        hideAllContent()
        binding.previewSubtitle.text = getString(R.string.preview_title)
        binding.previewErrorPanel.visibility = View.VISIBLE
        binding.previewErrorText.text = message
    }

    private fun releasePlayer() {
        binding.previewPlayerView.player = null
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        previewBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        previewBitmaps.clear()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URI = "preview_uri"
        private const val EXTRA_FILE_NAME = "preview_file_name"
        private const val EXTRA_SOURCE_FORMAT = "preview_source_format"
        private const val EXTRA_TARGET_FORMAT = "preview_target_format"
        private const val EXTRA_MIME = "preview_mime"
        private const val EXTRA_DURATION_MS = "preview_duration_ms"
        private const val EXTRA_SCALE = "preview_scale"
        private const val EXTRA_TRIM_START_MS = "preview_trim_start"
        private const val EXTRA_TRIM_END_MS = "preview_trim_end"
        private const val EXTRA_SPEED = "preview_speed"
        private const val EXTRA_FLIP_H = "preview_flip_h"
        private const val EXTRA_FLIP_V = "preview_flip_v"
        private const val EXTRA_ROTATION = "preview_rotation"
        private const val EXTRA_EXPORT_FRAMES = "preview_export_frames"
        private const val EXTRA_EXTRACT_FRAME = "preview_extract_frame"
        private const val EXTRA_EXTRACT_FRAME_MS = "preview_extract_frame_ms"

        fun intent(
            activity: MainActivity,
            uri: Uri,
            fileName: String,
            sourceFormat: FileFormat,
            targetFormat: FileFormat,
            mimeType: String,
            durationMs: Long,
            toolOptions: MediaToolOptions,
            scale: Float?
        ): android.content.Intent {
            return android.content.Intent(activity, PreviewActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_SOURCE_FORMAT, sourceFormat.name)
                putExtra(EXTRA_TARGET_FORMAT, targetFormat.name)
                putExtra(EXTRA_MIME, mimeType)
                putExtra(EXTRA_DURATION_MS, durationMs)
                scale?.let { putExtra(EXTRA_SCALE, it) }
                putExtra(EXTRA_TRIM_START_MS, toolOptions.trimStartMs)
                putExtra(EXTRA_TRIM_END_MS, toolOptions.trimEndMs)
                putExtra(EXTRA_SPEED, toolOptions.speedFactor)
                putExtra(EXTRA_FLIP_H, toolOptions.flipHorizontal)
                putExtra(EXTRA_FLIP_V, toolOptions.flipVertical)
                putExtra(EXTRA_ROTATION, toolOptions.rotationDegrees)
                putExtra(EXTRA_EXPORT_FRAMES, toolOptions.exportAllVideoFrames)
                putExtra(EXTRA_EXTRACT_FRAME, toolOptions.extractFrame)
                putExtra(EXTRA_EXTRACT_FRAME_MS, toolOptions.extractFrameMs)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
