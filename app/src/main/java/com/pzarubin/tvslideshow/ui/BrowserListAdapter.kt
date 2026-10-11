package com.pzarubin.tvslideshow.ui

import android.content.res.ColorStateList
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
import java.util.Locale

/**
 * Адаптер списка (строки вместо сетки) для файл-браузера: миниатюра/иконка на
 * подложке surface_elevated слева, название целиком (до двух строк), справа
 * метаданные. У видео — бейдж ▶ в углу миниатюры. Данные — те же [GridCard],
 * что и в сетке; используется локальным браузером и Яндекс.Диском.
 */
class BrowserListAdapter(
    private val scope: CoroutineScope,
    cards: List<GridCard>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<BrowserListAdapter.Holder>() {

    private val cards = cards.toMutableList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbWrap: View = view.findViewById(R.id.browser_thumb_wrap)
        val thumb: ImageView = view.findViewById(R.id.browser_thumbnail)
        val badge: ImageView = view.findViewById(R.id.browser_play_badge)
        val name: TextView = view.findViewById(R.id.name)
        val subtitle: TextView = view.findViewById(R.id.browser_subtitle)

        /** Заглушка «загружается» для превью (прототип 04, .thumb-loading.shimmer). */
        val shimmer = ShimmerDrawable()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_browser_row, parent, false)
        return Holder(view).also { holder ->
            // Скругление миниатюры: контур — у подложки row_thumb_bg.
            holder.thumbWrap.clipToOutline = true
        }
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
        holder.badge.visibility = if (card.thumbIsVideo) View.VISIBLE else View.GONE
        // Кликабельны только папки (в прототипе клик по фото/видео отсутствует).
        holder.itemView.isClickable = card.isDir
        if (card.isDir) {
            holder.itemView.setOnClickListener { onClick(position) }
        } else {
            holder.itemView.setOnClickListener(null)
        }

        val file = card.thumbFile
        if (file != null) {
            holder.thumb.contentDescription = card.label
            loadThumbnail(holder, card, file)
        } else {
            holder.thumb.contentDescription = null
            bindIcon(holder, card)
        }
    }

    override fun getItemCount() = cards.size

    /** Холдер ушёл в переиспользование — глушим shimmer-анимацию. */
    override fun onViewRecycled(holder: Holder) {
        holder.shimmer.stop()
    }

    /**
     * Заменяет карточку (например, когда доехала обложка альбома) и
     * перерисовывает строку. Позиция должна быть валидна.
     */
    fun updateCard(position: Int, card: GridCard) {
        if (position !in cards.indices) return
        cards[position] = card
        notifyItemChanged(position)
    }

    /**
     * Иконка без миниатюры (папка или заглушка): по центру подложки,
     * размером 20dp внутри 44dp (прототип 04); папка — tint primary.
     */
    private fun bindIcon(holder: Holder, card: GridCard) {
        holder.shimmer.stop()
        holder.thumb.tag = null
        holder.thumb.scaleType = ImageView.ScaleType.FIT_CENTER
        val res = holder.thumb.resources
        val pad = ((res.getDimensionPixelSize(R.dimen.browser_thumb) -
            res.getDimensionPixelSize(R.dimen.browser_thumb_icon)) / 2).coerceAtLeast(0)
        holder.thumb.setPadding(pad, pad, pad, pad)
        holder.thumb.setImageResource(card.iconRes)
        holder.thumb.imageTintList =
            if (card.iconRes == R.drawable.ic_folder) {
                ColorStateList.valueOf(
                    ContextCompat.getColor(holder.itemView.context, R.color.primary)
                )
            } else {
                null
            }
    }

    private fun loadThumbnail(holder: Holder, card: GridCard, file: File) {
        val key = file.absolutePath
        holder.thumb.tag = key
        holder.thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        holder.thumb.imageTintList = null
        // Превью занимает всю подложку — пады иконки-заглушки снимаем.
        holder.thumb.setPadding(0, 0, 0, 0)
        // Пока превью грузится — shimmer-заглушка (один ShimmerDrawable на
        // холдер; при переиспользовании холдера анимацию возобновляем).
        holder.shimmer.start()
        holder.thumb.setImageDrawable(holder.shimmer)

        scope.launch(Dispatchers.IO) {
            val bmp = if (card.thumbIsVideo) Thumbnails.video(file) else Thumbnails.image(file)
            withContext(Dispatchers.Main) {
                if (holder.thumb.tag == key) {
                    if (bmp != null) {
                        holder.shimmer.stop()
                        holder.thumb.imageTintList = null
                        holder.thumb.setImageBitmap(bmp)
                    } else {
                        bindIcon(holder, card)
                    }
                }
            }
        }
    }

    companion object {
        private const val BYTES_PER_MB = 1024L * 1024L

        /**
         * Человекочитаемый размер файла справа в строке (прототип 04: «24,8 МБ»).
         * От 1 МБ — «x,x МБ» (одна десятичная, запятая — Locale("ru")); меньше,
         * но больше нуля — «%d КБ»; null — если считать не из чего (файла нет/пуст).
         */
        fun formatSize(bytes: Long): String? = when {
            bytes <= 0L -> null
            bytes >= BYTES_PER_MB ->
                String.format(Locale("ru"), "%.1f МБ", bytes.toDouble() / BYTES_PER_MB)
            else -> String.format(Locale("ru"), "%d КБ", bytes / 1024L)
        }
    }
}
