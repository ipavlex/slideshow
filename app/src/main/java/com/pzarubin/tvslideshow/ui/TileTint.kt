package com.pzarubin.tvslideshow.ui

import android.graphics.PorterDuff
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.pzarubin.tvslideshow.R

/**
 * Подсветка иконки плитки фокусом (ревью дизайна): в покое — приглушённая,
 * в фокусе — полностью белая и непрозрачная.
 *
 * Программный colorFilter (XML-tint не пере-применяется после
 * setImageResource и не всегда реагирует на состояние родителя).
 * Режим SRC_IN с непрозрачным цветом: штрих/заливка вектора становятся
 * целиком серыми/белыми, прозрачные пиксели не затрагиваются. Сами
 * векторы сделаны непрозрачными (бывшая альфа 0.6 в fillColor/strokeColor
 * давала серый даже при белом фильтре).
 * Цвет покоя — итоговый вид icon_secondary над surface (152,152,159).
 */
object TileTint {

    fun bind(tile: View, icon: ImageView) {
        val idle = ContextCompat.getColor(tile.context, R.color.tile_icon_idle)
        val active = ContextCompat.getColor(tile.context, R.color.text_primary)
        val apply: (Boolean) -> Unit = { hasFocus ->
            icon.setColorFilter(if (hasFocus) active else idle, PorterDuff.Mode.SRC_IN)
        }
        apply(tile.isFocused)
        tile.setOnFocusChangeListener { _, hasFocus -> apply(hasFocus) }
    }
}
