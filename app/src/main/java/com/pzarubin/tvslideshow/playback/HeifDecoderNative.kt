package com.pzarubin.tvslideshow.playback

import android.graphics.Bitmap

/**
 * JNI-мост к нативному декодеру HEIC/HEIF (libheif + libde265, см. src/main/cpp).
 * Загружается лениво: если нативная библиотека недоступна, decode() вернёт null.
 */
object HeifDecoderNative {

    @Volatile
    private var loadFailed = false

    @Synchronized
    private fun load() {
        if (loadFailed) throw UnsatisfiedLinkError("heif_jni")
        try {
            System.loadLibrary("heif_jni")
        } catch (e: Throwable) {
            loadFailed = true
            throw e
        }
    }

    /** Возвращает [width, height] или null при ошибке. */
    external fun getSize(data: ByteArray): IntArray?

    /** Декодирует в переданный Bitmap (ARGB_8888, размер = размер изображения). */
    external fun decodeInto(data: ByteArray, bitmap: Bitmap): Boolean

    fun decode(data: ByteArray): Bitmap? {
        return try {
            load()
            val size = getSize(data) ?: return null
            val bmp = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
            if (decodeInto(data, bmp)) bmp else null
        } catch (e: Throwable) {
            null
        }
    }
}
