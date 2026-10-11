package com.pzarubin.tvslideshow.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import java.io.File

/**
 * Карточка браузера/хаба: общие данные для плиток хаба ([GridCardAdapter])
 * и строк списка ([BrowserListAdapter]).
 */
data class GridCard(
    val label: String,
    val iconRes: Int,
    val nameColorRes: Int = R.color.text_primary,
    val subtitle: String? = null,
    val thumbFile: File? = null,
    val thumbIsVideo: Boolean = false,
    /** Строка — папка: кликабельна; медиа-файл — нет (ревью дизайна). */
    val isDir: Boolean = false
)

/**
 * Адаптер плиток (главная, хаб Яндекса — прототипы 01/02): горизонтальный ряд
 * плиток [R.layout.item_tile] — крупная иконка и название (без подписи,
 * ревью дизайна). Данные — те же [GridCard], что и в браузере папок.
 */
class GridCardAdapter(
    private val cards: List<GridCard>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<GridCardAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tile_icon)
        val title: TextView = view.findViewById(R.id.tile_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tile, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = cards[position]
        holder.icon.setImageResource(card.iconRes)
        holder.icon.contentDescription = card.label
        holder.title.text = card.label
        holder.itemView.setOnClickListener { onClick(position) }
        // Иконка белеет, когда плитка в фокусе (ревью дизайна).
        TileTint.bind(holder.itemView, holder.icon)

        // Зазор между плитками; у последней — без зазора, чтобы ряд
        // оставался по центру экрана. Переприсваиваем params — иначе
        // изменение при переиспользовании холдера не применится.
        val params = holder.itemView.layoutParams as ViewGroup.MarginLayoutParams
        val gap = holder.itemView.resources.getDimensionPixelSize(R.dimen.tile_gap)
        params.rightMargin = if (position == cards.size - 1) 0 else gap
        holder.itemView.layoutParams = params
    }

    override fun getItemCount() = cards.size
}
