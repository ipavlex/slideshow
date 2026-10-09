package com.pzarubin.tvslideshow.playback

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri

/**
 * Декодирует изображение из [Uri] с уменьшением до целевого размера
 * (защита от OOM на больших фото) и учётом EXIF-ориентации.
 *
 * ImageDecoder доступен с API 28 (наш minSdk) и сам применяет EXIF-поворот.
 * ALLOCATOR_SOFTWARE нужен, чтобы битмап можно было рисовать через Canvas.
 */
object BitmapLoader {

    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? = try {
        val source = ImageDecoder.createSource(resolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (info.size.width > targetW || info.size.height > targetH) {
                decoder.setTargetSize(targetW, targetH)
            }
        }
    } catch (e: Exception) {
        null
    }
}
