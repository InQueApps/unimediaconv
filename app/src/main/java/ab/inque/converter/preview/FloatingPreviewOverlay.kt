package ab.inque.converter.preview

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.LifecycleOwner
import ab.inque.converter.databinding.FloatingPreviewPanelBinding
import kotlin.math.roundToInt

/**
 * Draggable, resizable floating preview window anchored in [host].
 */
class FloatingPreviewOverlay(
    private val host: FrameLayout,
    private val lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val onCloseRequested: () -> Unit
) {
    private val binding: FloatingPreviewPanelBinding = FloatingPreviewPanelBinding.inflate(
        LayoutInflater.from(context), host, false
    )
    private val panel: View = binding.root
    private val controller = PreviewPanelController(binding, lifecycleOwner, context)

    private var panelWidth = 0
    private var panelHeight = 0
    private var panelX = 0
    private var panelY = 0

    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragPanelStartX = 0
    private var dragPanelStartY = 0

    private var resizeStartX = 0f
    private var resizeStartY = 0f
    private var resizeStartW = 0
    private var resizeStartH = 0

    init {
        binding.previewCloseButton.setOnClickListener { onCloseRequested() }
        setupDrag(binding.previewDragHeader)
        setupResize(binding.previewResizeHandle)
        panel.visibility = View.GONE
        if (panel.parent == null) {
            host.addView(panel)
        }
        applyLayout()
    }

    fun isShowing(): Boolean = panel.visibility == View.VISIBLE

    fun show(request: PreviewRequest) {
        if (!isShowing()) {
            ensureDefaultBounds()
            panel.visibility = View.VISIBLE
        }
        binding.previewHeaderTitle.text = "${request.sourceFormat.displayName} → ${request.targetFormat.displayName}"
        controller.load(request)
    }

    fun hide() {
        controller.release()
        panel.visibility = View.GONE
    }

    fun pause() {
        controller.pause()
    }

    private fun ensureDefaultBounds() {
        if (panelWidth > 0 && panelHeight > 0) return
        host.post {
            val dm = context.resources.displayMetrics
            panelWidth = (dm.widthPixels * 0.88f).roundToInt()
            panelHeight = (dm.heightPixels * 0.42f).roundToInt()
            panelX = ((dm.widthPixels - panelWidth) / 2f).roundToInt()
            panelY = (dm.heightPixels * 0.08f).roundToInt()
            applyLayout()
        }
    }

    private fun applyLayout() {
        val params = FrameLayout.LayoutParams(panelWidth, panelHeight).apply {
            leftMargin = panelX
            topMargin = panelY
            gravity = Gravity.TOP or Gravity.START
        }
        panel.layoutParams = params
    }

    private fun setupDrag(header: View) {
        header.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.rawX
                    dragStartY = event.rawY
                    dragPanelStartX = panelX
                    dragPanelStartY = panelY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragStartX).roundToInt()
                    val dy = (event.rawY - dragStartY).roundToInt()
                    panelX = (dragPanelStartX + dx).coerceIn(0, (host.width - panelWidth).coerceAtLeast(0))
                    panelY = (dragPanelStartY + dy).coerceIn(0, (host.height - panelHeight).coerceAtLeast(0))
                    applyLayout()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupResize(handle: View) {
        val minW = dp(240)
        val minH = dp(180)
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartX = event.rawX
                    resizeStartY = event.rawY
                    resizeStartW = panelWidth
                    resizeStartH = panelHeight
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - resizeStartX).roundToInt()
                    val dy = (event.rawY - resizeStartY).roundToInt()
                    val maxW = (host.width - panelX).coerceAtLeast(minW)
                    val maxH = (host.height - panelY).coerceAtLeast(minH)
                    panelWidth = (resizeStartW + dx).coerceIn(minW, maxW)
                    panelHeight = (resizeStartH + dy).coerceIn(minH, maxH)
                    applyLayout()
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
