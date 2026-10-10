package com.pzarubin.tvslideshow.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.playback.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Адаптер списка (строки вместо сетки) для файл-браузера: миниатюра/иконка
 * слева, название целиком справа. Данные — те же [GridCard], что и в сетке.
 */
class BrowserListAdapter(
    private val scope: CoroutineScope,
    private val cards: List<GridCard>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<BrowserListAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.browser_thumbnail)
        val name: TextView = view.findViewById(R.id.name)
        val subtitle: TextView = view.findViewById(R.id.browser_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browser_row, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = cards[position]
        holder.name.text = card.label
        holder.name.setTextColor(ContextCompat.getColor(holder.itemView.context, card.nameColorRes))
        if (card.subtitle != null) {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = card.subtitle
        } else {
            holder.subtitle.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(position) }

        val file = card.thumbFile
        if (file != null) {
            holder.thumb.contentDescription = card.label
            loadThumbnail(holder, card, file)
        } else {
            holder.thumb.contentDescription = null
            holder.thumb.tag = null
            holder.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            holder.thumb.setImageResource(card.iconRes)
        }
    }

    override fun getItemCount() = cards.size

    private fun loadThumbnail(holder: Holder, card: GridCard, file: File) {
        val key = file.absolutePath
        holder.thumb.tag = key
        holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.thumb.setImageResource(R.drawable.ic_photo)

        scope.launch(Dispatchers.IO) {
            val bmp = if (card.thumbIsVideo) Thumbnails.video(file) else Thumbnails.image(file)
            withContext(Dispatchers.Main) {
                if (holder.thumb.tag == key) {
                    if (bmp != null) {
                        holder.thumb.setImageBitmap(bmp)
                    } else {
                        holder.thumb.setImageResource(card.iconRes)
                    }
                }
            }
        }
    }
}
