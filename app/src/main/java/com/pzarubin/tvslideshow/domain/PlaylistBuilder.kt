package com.pzarubin.tvslideshow.domain

import com.pzarubin.tvslideshow.data.source.MediaItem

/**
 * Формирует плейлист из списка элементов: сортировка и перемешивание.
 */
object PlaylistBuilder {

    enum class Order { NAME, DATE, RANDOM }

    fun build(
        items: List<MediaItem>,
        order: Order = Order.NAME,
        shuffle: Boolean = false
    ): List<MediaItem> {
        val sorted = when (order) {
            Order.NAME -> items.sortedBy { it.name.lowercase() }
            Order.DATE -> items.sortedBy { it.modifiedAt ?: 0L }
            Order.RANDOM -> items.shuffled()
        }
        return if (shuffle) sorted.shuffled() else sorted
    }
}
