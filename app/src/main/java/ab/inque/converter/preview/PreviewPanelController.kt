package ab.inque.converter.preview

import android.content.Context
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import ab.inque.converter.PreviewPageAdapter
import ab.inque.converter.R
import ab.inque.converter.databinding.FloatingPreviewPanelBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders [PreviewContent] into the floating preview panel (no full-screen activity).
 */
class PreviewPanelController(
    private val binding: FloatingPreviewPanelBinding,
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context
) {
    private var player: ExoPlayer? = null
    private val previewBitmaps = mutableListOf<android.graphics.Bitmap>()
    private var loadJob: Job? = null

    fun load(request: PreviewRequest) {
        loadJob?.cancel()
        showLoading(true)
        loadJob = lifecycleOwner.lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                PreviewLoader.load(
                    context = context.applicationContext,
                    uri = request.uri,
                    fileName = request.fileName,
                    sourceFormat = request.sourceFormat,
                    targetFormat = request.targetFormat,
                    toolOptions = request.toolOptions,
                    scale = request.scale,
                    durationMs = request.durationMs,
                    mimeType = request.mimeType
                )
            }
            showLoading(false)
            renderContent(content)
        }
    }

    fun release() {
        loadJob?.cancel()
        releasePlayer()
        previewBitmaps.forEach { if (!it.isRecycled) it.recycle() }
        previewBitmaps.clear()
        binding.previewWebView.loadUrl("about:blank")
        hideAllContent()
    }

    fun pause() {
        player?.pause()
    }

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
        player = ExoPlayer.Builder(context).build().also { exo ->
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
        previewBitmaps.addAll(content.bitmaps)
        val bmp = content.bitmaps.firstOrNull() ?: return showError(context.getString(R.string.preview_error))
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
                binding.previewPageIndicator.text = context.getString(
                    R.string.preview_page_indicator,
                    position + 1,
                    content.bitmaps.size
                )
            }
        })
        binding.previewPageIndicator.text = context.getString(
            R.string.preview_page_indicator, 1, content.bitmaps.size
        )
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
        binding.previewSubtitle.text = context.getString(R.string.preview_title)
        binding.previewErrorPanel.visibility = View.VISIBLE
        binding.previewErrorText.text = message
    }

    private fun releasePlayer() {
        binding.previewPlayerView.player = null
        player?.release()
        player = null
    }
}

data class PreviewRequest(
    val uri: android.net.Uri,
    val fileName: String,
    val sourceFormat: ab.inque.converter.FileFormat,
    val targetFormat: ab.inque.converter.FileFormat,
    val mimeType: String,
    val durationMs: Long,
    val toolOptions: ab.inque.converter.MediaToolOptions,
    val scale: Float?
)
