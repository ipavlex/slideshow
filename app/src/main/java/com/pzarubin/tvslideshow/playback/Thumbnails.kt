package com.pzarubin.tvslideshow.playback

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import kotlin.math.min

/**
 * Миниатюры для сетки браузера: уменьшенные копии фото (ImageDecoder) и кадры
 * видео (MediaMetadataRetriever). Результаты кэшируются в памяти по пути файла.
 * Все методы тяжёлые — вызывать вне UI-потока.
 */
object Thumbnails {

    private const val THUMB_SIZE = 512

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun image(file: File): Bitmap? {
        val key = file.absolutePath
        cache.get(key)?.let { return it }
        val bmp = decodeImage(file, THUMB_SIZE)
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    fun video(file: File): Bitmap? {
        val key = "v:${file.absolutePath}"
        cache.get(key)?.let { return it }
        val bmp = decodeVideo(file, THUMB_SIZE)
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private fun decodeImage(file: File, target: Int): Bitmap? {
        // Сначала системный декодер; на устройствах без HEIF-поддержки — libheif.
        decodeViaSystem(file, target)?.let { return it }
        if (HeifDecoder.isHeif(file)) return HeifDecoder.decode(file, target)
        return null
    }

    private fun decodeViaSystem(file: File, target: Int): Bitmap? = try {
        val source = ImageDecoder.createSource(file)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = min(target.toFloat() / w, target.toFloat() / h)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeVideo(file: File, target: Int): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(1_000_000L)
        retriever.release()
        frame?.let { scaleDown(it, target) }
    } catch (e: Exception) {
        null
    }

    private fun scaleDown(bmp: Bitmap, target: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val scale = min(target.toFloat() / w, target.toFloat() / h)
        if (scale >= 1f) return bmp
        return Bitmap.createScaledBitmap(
            bmp,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }
}
