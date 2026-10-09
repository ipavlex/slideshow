package com.pzarubin.tvslideshow.data.source

import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.auth.YandexAuth
import com.pzarubin.tvslideshow.data.auth.YandexConfig
import com.pzarubin.tvslideshow.data.cache.SlideCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

/**
 * Низкоуровневый клиент REST API Яндекс.Диска: листинг, ссылка на скачивание,
 * скачивание в кэш. Автоматически обновляет access-токен при 401.
 */
class YandexClient(
    private val tokenStore: TokenStore,
    private val cache: SlideCache
) {

    data class Entry(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val mimeType: String?,
        val mediaType: String?,
        val size: Long?,
        val modified: Long?
    )

    private val client = OkHttpClient()
    private var accessToken: String = tokenStore.accessToken ?: ""

    suspend fun listEntries(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val json = getJson("$BASE?path=${enc(path)}&limit=200")
        val items = json.optJSONObject("_embedded")?.optJSONArray("items") ?: JSONArray()
        (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Entry(
                name = o.optString("name"),
                path = o.optString("path"),
                isDir = o.optString("type") == "dir",
                mimeType = o.optString("mime_type").ifBlank { null },
                mediaType = o.optString("media_type").ifBlank { null },
                size = o.optLong("size").takeIf { it > 0L },
                modified = parseModified(o.optString("modified"))
            )
        }
    }

    /** Возвращает локальный файл для пути на Диске (скачивает при необходимости). */
    suspend fun downloadToCache(path: String): File = withContext(Dispatchers.IO) {
        val href = getJson("$BASE/download?path=${enc(path)}").optString("href")
        if (href.isBlank()) throw IOException("Нет ссылки на скачивание для $path")
        val key = "${path.hashCode()}-${path.substringAfterLast('/')}"
        cache.download(href, key)
    }

    private suspend fun getJson(url: String): JSONObject {
        var text = fetch(url)
        if (text == null) {
            refreshAccessToken()
            text = fetch(url) ?: throw IOException("Ошибка авторизации Яндекс.Диска")
        }
        return JSONObject(text)
    }

    /** Возвращает null при 401 (нужно обновить токен). */
    private fun fetch(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "OAuth $accessToken")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code == 401) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $body")
            return body
        }
    }

    private suspend fun refreshAccessToken() {
        val rt = tokenStore.refreshToken ?: throw IOException("Требуется повторный вход")
        val token = YandexAuth.refreshToken(YandexConfig.CLIENT_ID, rt)
        accessToken = token.accessToken
        tokenStore.accessToken = token.accessToken
        token.refreshToken?.let { tokenStore.refreshToken = it }
    }

    private fun parseModified(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val BASE = "https://cloud-api.yandex.net/v1/disk/resources"
    }
}
