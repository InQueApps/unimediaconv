package ab.inque.converter

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView

class PreviewPageAdapter(
    private val pages: List<Bitmap>
) : RecyclerView.Adapter<PreviewPageAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val photo: PhotoView = view as PhotoView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val pv = PhotoView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        return Holder(pv)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.photo.setImageBitmap(pages[position])
    }

    override fun getItemCount(): Int = pages.size
}
