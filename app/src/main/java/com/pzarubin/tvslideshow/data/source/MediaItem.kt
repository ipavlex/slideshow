package com.pzarubin.tvslideshow.data.source

import android.net.Uri

/**
 * Элемент слайдшоу (фото или видео).
 *
 * Для локального источника заполняется [uri] (SAF-документ),
 * для Яндекс.Диск — [remotePath] (путь в облаке).
 */
data class MediaItem(
    val id: String,
    val kind: Kind,
    val name: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
    val uri: Uri? = null,
    val remotePath: String? = null
) {
    enum class Kind { PHOTO, VIDEO }
}
