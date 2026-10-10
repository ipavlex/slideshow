package com.pzarubin.tvslideshow.playback

import android.content.ContentResolver
import android.graphics.Bitmap
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Фолбэк-декодер HEIC/HEIF через libheif (см. HEIC.md).
 * Нужен на устройствах без системного HEIF-декодера, где ImageDecoder возвращает null.
 */
object HeifDecoder {

    private val HEIF_EXTENSIONS = setOf("heic", "heif", "hif")

    /** Бренды ftyp-бокса, означающие HEIF-контейнер. */
    private val HEIF_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
        "mif1", "mif2", "msf1", "mfsm", "MiHE", "mhe1", "avif", "avis"
    )

    fun isHeif(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext in HEIF_EXTENSIONS) return true
        return hasHeifMagic(file)
    }

    fun isHeifUri(resolver: ContentResolver, uri: android.net.Uri): Boolean = try {
        resolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(32)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            hasHeifMagic(header, read)
        } ?: false
    } catch (e: Exception) {
        false
    }

    private fun hasHeifMagic(file: File): Boolean = try {
        file.inputStream().use { input ->
            val header = ByteArray(32)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            hasHeifMagic(header, read)
        }
    } catch (e: Exception) {
        false
    }

    private fun hasHeifMagic(header: ByteArray, len: Int): Boolean {
        if (len < 12) return false
        if (!(header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte())
        ) return false
        for (offset in intArrayOf(8, 12)) {
            if (len < offset + 4) break
            val brand = String(header, offset, 4, Charsets.US_ASCII)
            if (brand in HEIF_BRANDS) return true
        }
        return false
    }

    /** Декодирует HEIF-файл и уменьшает до [target] по большей стороне. */
    fun decode(file: File, target: Int): Bitmap? {
        val data = try {
            file.readBytes()
        } catch (e: Exception) {
            return null
        }
        val bmp = HeifDecoderNative.decode(data) ?: return null
        return scaleDown(bmp, target)
    }

    /** Декодирует HEIF-файл «cover» под targetW×targetH (пропорции сохранены). */
    fun decode(resolver: ContentResolver, uri: android.net.Uri, targetW: Int, targetH: Int): Bitmap? {
        val data = try {
            resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        } catch (e: Exception) {
            return null
        }
        val bmp = HeifDecoderNative.decode(data) ?: return null
        val w = bmp.width
        val h = bmp.height
        // Масштаб под способ показа: cover или letterbox (как решит KenBurnsView).
        val scale = if (SlideFit.useLetterbox(w, h, targetW, targetH)) {
            minOf(targetW.toFloat() / w, targetH.toFloat() / h)
        } else {
            maxOf(targetW.toFloat() / w, targetH.toFloat() / h)
        }
        if (scale <= 1f) return bmp
        return Bitmap.createScaledBitmap(
            bmp,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
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
