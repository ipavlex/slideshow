package com.pzarubin.tvslideshow.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Клиент публичных фотоальбомов Яндекс.Диска (ссылки вида disk.yandex.ru/a/…).
 *
 * Виртуальные альбомы недоступны через официальный REST API — используется
 * внутренний API веб-галереи (тот же, что у публичной страницы альбома).
 * OAuth не нужен: альбом должен быть опубликован по ссылке.
 *
 * Нюансы протокола (проверено на живом альбоме):
 *  - страница альбома отдаёт встроенное состояние (store-prefetch) с hash и sk;
 *  - sk привязан к сессии: запросы к API должны идти с куками bootstrap-запроса,
 *    иначе сервер отвечает wrongSk (с новым sk — тогда повторяем);
 *  - без браузерного User-Agent страница может отдавать капчу вместо данных.
 *
 * ВАЖНО: эндпоинт недокументирован — возможны капча и изменения формата.
 */
class YandexPublicAlbum {

    data class Photo(
        val id: String,
        val name: String,
        val previewUrl: String,
        val sizeBytes: Long?,
        val mimeType: String?
    )

    data class Video(
        val id: String,
        val name: String,
        val albumItemId: String,
        val sizeBytes: Long?,
        val mimeType: String?
    )

    data class Album(
        val url: String,
        val title: String,
        val hash: String,
        val photos: List<Photo>,
        val videos: List<Video>
    )

    /** sk текущей веб-сессии (ротируется сервером, обновляется по wrongSk). */
    @Volatile
    private var sk: String? = null

    /** Куки одной веб-сессии: bootstrap и API-запросы должны делить их. */
    private val cookieJar = object : CookieJar {
        private val store = HashMap<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
    }

    private val client = OkHttpClient.Builder().cookieJar(cookieJar).build()

    /**
     * Открывает альбом по ссылке: читает страницу, извлекает состояние галереи
     * и постранично перечисляет содержимое через fetch-album-list.
     */
    suspend fun open(url: String): Album = withContext(Dispatchers.IO) {
        val pageUrl = normalizeUrl(url)
        val state = bootstrap(pageUrl)
        val root = state.getJSONObject("resources")
            .getJSONObject(state.getString("rootResourceId"))
        if (root.optString("type") != "album") {
            throw IOException("Ссылка должна вести на публичный фотоальбом (/a/…)")
        }
        if (root.has("errorCode") || root.optBoolean("blocked", false)) {
            throw IOException("Альбом недоступен")
        }
        val hash = root.optString("path").ifBlank { root.optString("hash") }
        sk = state.getJSONObject("environment").getString("sk")
        val title = root.optString("name")

        val photos = mutableListOf<Photo>()
        val videos = mutableListOf<Video>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        var completed = false
        // Постраничный обход всегда с первой страницы: bootstrap может
        // содержать только первую порцию элементов.
        var guard = 0
        while (!completed && guard++ < MAX_PAGES) {
            val payload = JSONObject()
                .put("hash", hash)
                .put("sk", currentSk())
            cursor?.let { payload.put("lastItemId", it) }
            val data = publicApi(pageUrl, "fetch-album-list", payload)
            completed = data.optBoolean("completed", false)
            val items = data.optJSONArray("resources") ?: JSONArray()
            for (i in 0 until items.length()) {
                collect(items.getJSONObject(i), photos, videos)
            }
            val next = if (items.length() > 0) {
                items.getJSONObject(items.length() - 1).optString("albumItemId").ifBlank { null }
            } else {
                null
            }
            if (next == null || !seenCursors.add(next)) break
            cursor = next
        }
        Album(pageUrl, title, hash, photos, videos)
    }

    /** Ссылка на скачивание оригинала видео из альбома. */
    suspend fun videoDownloadUrl(album: Album, albumItemId: String): String =
        withContext(Dispatchers.IO) {
            ensureSession(album.url)
            val data = publicApi(
                album.url, "album-download-url",
                JSONObject()
                    .put("hash", album.hash)
                    .put("sk", currentSk())
                    .put("itemId", albumItemId)
            )
            val url = data.optJSONObject("data")?.optString("url").orEmpty()
            if (url.isBlank()) throw IOException("Не удалось получить ссылку на видео")
            url
        }

    /**
     * Гарантирует живую веб-сессию (sk + куки): нужна для запросов к API,
     * когда данные альбома взяты из локального кэша, а не из bootstrap.
     */
    suspend fun ensureSession(albumUrl: String): Unit = withContext(Dispatchers.IO) {
        if (sk != null) return@withContext
        val state = bootstrap(normalizeUrl(albumUrl))
        sk = state.getJSONObject("environment").getString("sk")
    }

    private fun currentSk(): String =
        sk ?: throw IOException("Сессия альбома не инициализирована")

    private fun collect(item: JSONObject, photos: MutableList<Photo>, videos: MutableList<Video>) {
        if (item.optString("type") != "file") return
        val meta = item.optJSONObject("meta") ?: return
        val id = item.optString("id")
        if (id.isBlank()) return
        val name = item.optString("name")
        val size = meta.optLong("size").takeIf { it > 0L }
        val mime = meta.optString("mimetype").ifBlank { null }
        if (meta.optString("mediatype") == "video") {
            val albumItemId = item.optString("albumItemId")
            if (albumItemId.isNotBlank()) {
                videos += Video(id, name, albumItemId, size, mime)
            }
        } else {
            val preview = meta.optString("xxxlPreview").ifBlank { meta.optString("original") }
            if (preview.isNotBlank()) {
                photos += Photo(id, name, preview, size, mime)
            }
        }
    }

    /** Загружает страницу альбома и извлекает встроенное состояние галереи. */
    private fun bootstrap(pageUrl: String): JSONObject {
        val req = Request.Builder().url(pageUrl).header("User-Agent", USER_AGENT).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val html = resp.body?.string() ?: ""
            val match = PREFETCH_REGEX.find(html)
                ?: throw IOException(
                    "Альбом недоступен (капча, приватный альбом или изменился формат страницы)"
                )
            return JSONObject(match.groupValues[1])
        }
    }

    /**
     * POST к внутреннему API веб-галереи (тело — URL-кодированный JSON).
     * При wrongSk сервер присылает новый sk — повторяем запрос один раз.
     */
    private fun publicApi(pageUrl: String, endpoint: String, payload: JSONObject): JSONObject {
        val httpUrl = pageUrl.toHttpUrlOrNull()
            ?: throw IOException("Некорректная ссылка на альбом")
        val origin = "${httpUrl.scheme}://${httpUrl.host}"
        repeat(2) { attempt ->
            val body = URLEncoder.encode(payload.toString(), "UTF-8").replace("+", "%20")
                .toRequestBody("text/plain".toMediaType())
            val req = Request.Builder()
                .url("$origin/public/api/$endpoint")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-Retpath-Y", pageUrl)
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    throw IOException("Некорректный ответ альбома (возможна капча)")
                }
                if (json.optBoolean("wrongSk", false)) {
                    val newSk = json.optString("newSk").ifBlank { null }
                    if (attempt == 0 && newSk != null) {
                        sk = newSk
                        payload.put("sk", newSk)
                        return@repeat
                    }
                    throw IOException("Сессия альбома устарела, попробуйте снова")
                }
                if (json.optBoolean("error", false) || json.optString("type") == "captcha") {
                    throw IOException("Альбом временно недоступен (капча). Попробуйте позже")
                }
                return json
            }
        }
        throw IOException("Не удалось получить данные альбома")
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    companion object {
        /** Без браузерного User-Agent страница может отдавать капчу вместо данных. */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private const val MAX_PAGES = 10_000

        private val PREFETCH_REGEX = Regex(
            """<script\b[^>]*\bid=["']store-prefetch["'][^>]*>(.*?)</script>""",
            RegexOption.DOT_MATCHES_ALL
        )

        private val ALBUM_ID_IN_NAME = Regex("""/a/([A-Za-z0-9_-]+)""")
        private val BARE_ALBUM_ID = Regex("""^[A-Za-z0-9_-]{6,}$""")

        /**
         * Извлекает ID альбома из имени папки на Диске. Имя папки не может
         * содержать «/», поэтому ожидается либо ID (pGhMjtc-RTQShg), либо
         * ссылка без слэшей. Возвращает null, если имя не похоже на альбом.
         */
        fun albumIdFromName(name: String): String? {
            val trimmed = name.trim()
            ALBUM_ID_IN_NAME.find(trimmed)?.let { return it.groupValues[1] }
            return if (BARE_ALBUM_ID.matches(trimmed)) trimmed else null
        }

        /** Каноническая ссылка на альбом по ID. */
        fun albumUrl(albumId: String): String = "https://disk.yandex.ru/a/$albumId"
    }
}
