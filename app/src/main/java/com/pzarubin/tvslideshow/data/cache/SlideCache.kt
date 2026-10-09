package com.pzarubin.tvslideshow.data.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Файловый кэш скачанных с Яндекс.Диска файлов. При превышении
 * [maxSizeBytes] удаляет самые старые файлы.
 */
class SlideCache(
    context: Context,
    private val maxSizeBytes: Long = 2L * 1024 * 1024 * 1024
) {

    private val dir = File(context.cacheDir, "slides")
    private val client = OkHttpClient()

    init {
        dir.mkdirs()
    }

    fun file(key: String): File = File(dir, key)

    /** Скачивает [url] в кэш (если ещё нет) и возвращает файл. */
    suspend fun download(url: String, key: String): File = withContext(Dispatchers.IO) {
        val f = file(key)
        if (f.exists() && f.length() > 0L) return@withContext f

        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(f).use { output -> input.copyTo(output) }
            }
        }
        evictIfNeeded()
        f
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun evictIfNeeded() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxSizeBytes) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }
}
