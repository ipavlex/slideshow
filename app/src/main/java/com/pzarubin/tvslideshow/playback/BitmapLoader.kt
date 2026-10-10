package com.pzarubin.tvslideshow.playback

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.min

/**
 * Декодирует изображение из [Uri] под фактический размер на экране и учётом
 * EXIF-ориентации.
 *
 * Размер задаётся как «cover» (заполнить targetW×targetH с обрезкой излишка,
 * пропорции сохраняются) — именно так bitmap рисуется в KenBurnsView
 * (CENTER_CROP). Декодирование «fit within» приводило к апскейлу при отрисовке
 * (4:3-фото на 16:9-экране растягивалось в 1.33×) — видимая пикселизация.
 *
 * ImageDecoder доступен с API 28 (наш minSdk) и сам применяет EXIF-поворот.
 * ALLOCATOR_SOFTWARE нужен, чтобы битмап можно было рисовать через Canvas.
 */
object BitmapLoader {

    /** Защита от OOM на экстремальных панорамах. */
    private const val MAX_DIM = 4096

    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        // Сначала системный декодер; на устройствах без HEIF-поддержки — libheif.
        decodeViaSystem(resolver, uri, targetW, targetH)?.let { return it }
        if (HeifDecoder.isHeifUri(resolver, uri)) {
            return HeifDecoder.decode(resolver, uri, targetW, targetH)
        }
        return null
    }

    /**
     * Декодирует «cover»: результат не меньше targetW×targetH ни по одной
     * стороне, пропорции исходника сохранены (излишек обрезается при отрисовке).
     */
    private fun decodeViaSystem(
        resolver: ContentResolver,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? = try {
        val source = ImageDecoder.createSource(resolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            // Масштаб под способ показа: cover (KenBurnsView) или letterbox.
            var scale = if (SlideFit.useLetterbox(w, h, targetW, targetH)) {
                min(targetW.toFloat() / w, targetH.toFloat() / h)
            } else {
                max(targetW.toFloat() / w, targetH.toFloat() / h)
            }
            // Клампим панорамы, чтобы не раздуть память.
            if (w * scale > MAX_DIM) scale = MAX_DIM.toFloat() / w
            if (h * scale > MAX_DIM) scale = min(scale, MAX_DIM.toFloat() / h)
            decoder.setTargetSize(
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1)
            )
        }
    } catch (e: Exception) {
        null
    }
}
