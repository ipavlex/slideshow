package com.pzarubin.tvslideshow.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import kotlin.math.min

/**
 * Миниатюры для сетки браузера: уменьшенные копии фото (ImageDecoder) и кадры
 * видео (MediaMetadataRetriever). Результаты кэшируются в памяти по пути файла
 * и на диске (JPEG в cacheDir/thumbs, ключ — путь+размер+mtime).
 *
 * Тяжёлые декоды (особенно HEIC через libheif — полный программный декод кадра)
 * ограничены семафором: параллельные декоды всех видимых карточек забивают все
 * ядра и тормозят всю сетку. Все методы тяжёлые — вызывать вне UI-потока.
 */
object Thumbnails {

    private const val THUMB_SIZE = 512
    private const val DISK_CACHE_DIR = "thumbs"
    private const val JPEG_QUALITY = 85

    /** Одновременно не больше стольких декодов миниатюр. */
    private const val MAX_CONCURRENT_DECODES = 2

    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val decodePermits = Semaphore(MAX_CONCURRENT_DECODES)

    private var diskCacheDir: File? = null

    /** Вызвать один раз из Activity до использования миниатюр. */
    fun init(context: Context) {
        if (diskCacheDir == null) {
            diskCacheDir = File(context.cacheDir, DISK_CACHE_DIR).apply { mkdirs() }
        }
    }

    fun image(file: File): Bitmap? {
        val key = file.absolutePath
        cache.get(key)?.let { return it }
        diskFromCache(file)?.let {
            cache.put(key, it)
            return it
        }
        val bmp = withDecodePermit {
            // Пока ждали семафор, другую карточку могли уже декодировать.
            cache.get(key) ?: decodeImage(file, THUMB_SIZE)
        }
        if (bmp != null) {
            diskPut(file, bmp)
            cache.put(key, bmp)
        }
        return bmp
    }

    fun video(file: File): Bitmap? {
        val key = "v:${file.absolutePath}"
        cache.get(key)?.let { return it }
        val bmp = withDecodePermit {
            cache.get(key) ?: decodeVideo(file, THUMB_SIZE)
        }
        if (bmp != null) cache.put(key, bmp)
        return bmp
    }

    private inline fun <T> withDecodePermit(block: () -> T): T {
        decodePermits.acquire()
        try {
            return block()
        } finally {
            decodePermits.release()
        }
    }
    // --- Дисковый кэш (только фото) ---

    private fun diskKey(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val payload = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
            digest.digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    private fun diskFromCache(file: File): Bitmap? {
        val dir = diskCacheDir ?: return null
        val key = diskKey(file)
        if (key.isEmpty()) return null
        val f = File(dir, "$key.jpg")
        if (!f.isFile) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    private fun diskPut(file: File, bmp: Bitmap) {
        val dir = diskCacheDir ?: return
        val key = diskKey(file)
        if (key.isEmpty()) return
        try {
            val tmp = File(dir, "$key.tmp")
            tmp.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            tmp.renameTo(File(dir, "$key.jpg"))
        } catch (e: Exception) {
            // Кэш не критичен — молча пропускаем.
        }
    }

    // --- Декодирование ---

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
