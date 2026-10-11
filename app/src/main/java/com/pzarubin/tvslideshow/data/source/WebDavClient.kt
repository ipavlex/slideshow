package com.pzarubin.tvslideshow.data.source

import android.net.Uri
import android.util.Xml
import com.pzarubin.tvslideshow.data.auth.WebDavStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** 401 от WebDAV: сохранённые креденшелы недействительны — нужен повторный вход. */
class UnauthorizedException : IOException("Неверный логин или пароль Яндекс.Диска")

/**
 * Низкоуровневый клиент WebDAV Яндекс.Диска: PROPFIND-листинг папок,
 * скачивание файлов в кэш, проверка пары логин/пароль приложения.
 * Авторизация — Basic auth (заголовок Authorization); креденшелы хранит [WebDavStore].
 */
class WebDavClient(
    private val store: WebDavStore,
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

    /** Разобранный <response> из multistatus — до конверсии href в disk-путь. */
    private data class Raw(
        var href: String? = null,
        var isDir: Boolean = false,
        var size: Long? = null,
        var mime: String? = null,
        var modified: Long? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Листинг папки: PROPFIND Depth:1, сам элемент из результата убран. */
    suspend fun listEntries(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val request = propfindRequest(urlFor(path), basicAuth(), depth = 1)
        client.newCall(request).execute().use { resp ->
            when {
                resp.code == 401 -> throw UnauthorizedException()
                resp.code == 404 -> throw IOException("Папка не найдена: $path")
                resp.code != 207 -> throw IOException("HTTP ${resp.code}")
            }
            val xml = resp.body?.string().orEmpty()
            val raws = try {
                parseMultistatus(xml)
            } catch (_: Exception) {
                throw IOException("Некорректный ответ WebDAV")
            }
            if (raws.isEmpty()) throw IOException("Некорректный ответ WebDAV")

            val self = normalizedDiskPath(path)
            raws.mapNotNull { raw ->
                val href = raw.href?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val diskPath = diskPathOf(href)
                // Response самого запрошенного пути пропускаем — сравнением
                // путей, независимо от порядка ответов сервера.
                if (diskPath == self) return@mapNotNull null
                val name = diskPath.removePrefix(ROOT).substringAfterLast('/')
                Entry(
                    name = name,
                    path = diskPath,
                    isDir = raw.isDir,
                    mimeType = raw.mime?.takeIf { it.isNotBlank() },
                    mediaType = mediaTypeOf(name, raw.mime),
                    size = raw.size,
                    modified = raw.modified
                )
            }
        }
    }

    /** Возвращает локальный файл для пути на Диске (скачивает при необходимости). */
    suspend fun downloadToCache(path: String): File = withContext(Dispatchers.IO) {
        // Ключ кэша — как в старом REST-клиенте: хэш пути + имя файла.
        val key = "${path.hashCode()}-${path.substringAfterLast('/')}"
        cache.download(
            urlFor(path), key,
            mapOf("Authorization" to basicAuth())
        )
    }

    /**
     * Проверка пары логин/пароль приложения перед сохранением: PROPFIND
     * Depth:0 на корне — 207 означает успех, 401 — неверные креденшелы.
     */
    suspend fun verify(username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            val request = propfindRequest(
                urlFor(ROOT), Credentials.basic(username, password), depth = 0
            )
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    207 -> true
                    401 -> false
                    // Сбой сервера/троттлинг — отдельная ошибка, а не «неверный пароль».
                    else -> throw IOException("HTTP ${resp.code}")
                }
            }
        }

    // --- Запросы ---

    /** PROPFIND-запрос с Basic-авторизацией и заголовком Depth. */
    private fun propfindRequest(url: String, basic: String, depth: Int): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", basic)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
            .build()

    /** Basic-заголовок из сохранённых креденшелов; без входа — ошибка. */
    private fun basicAuth(): String {
        val username = store.username ?: throw IOException("Требуется вход в Яндекс.Диск")
        val password = store.appPassword ?: throw IOException("Требуется вход в Яндекс.Диск")
        return Credentials.basic(username, password)
    }

    // --- Пути: disk:/A/B ↔ сегменты URL ---

    /** URL для disk-пути: appendPath сам процентно кодирует сегменты UTF-8. */
    private fun urlFor(diskPath: String): String {
        val base = Uri.parse(WEBDAV_BASE).buildUpon()
        diskToSegments(diskPath).forEach { base.appendPath(it) }
        return base.build().toString()
    }

    /** "disk:/A/B" → ["A", "B"]; "disk:/" → пусто. */
    private fun diskToSegments(path: String): List<String> =
        path.removePrefix("disk:/").removePrefix("disk:")
            .split('/').filter { it.isNotEmpty() }

    /**
     * href (полный URL или путь) → декодированные сегменты.
     * Пример: "https://webdav.yandex.ru/Foo%20Bar" → ["Foo Bar"].
     */
    private fun hrefSegments(href: String): List<String> {
        val uri = Uri.parse(href)
        val rawPath = if (uri.host != null) uri.path ?: "" else href
        return rawPath.split('/')
            .filter { it.isNotEmpty() }
            .map { Uri.decode(it) }
    }

    /** href → канонический путь "disk:/A/B" (как в старом REST-клиенте). */
    private fun diskPathOf(href: String): String {
        val segments = hrefSegments(href)
        return if (segments.isEmpty()) ROOT else ROOT + segments.joinToString("/")
    }

    /** Канонический вид disk-пути: без хвостового слэша (кроме корня). */
    private fun normalizedDiskPath(path: String): String {
        val trimmed = path.trim().trimEnd('/')
        return if (trimmed == "disk:") ROOT else trimmed
    }

    /** "image"/"video" по mime, иначе по расширению файла, иначе null. */
    private fun mediaTypeOf(name: String, mime: String?): String? = when {
        mime?.startsWith("image/") == true -> "image"
        mime?.startsWith("video/") == true -> "video"
        MediaFileTypes.isImage(name) -> "image"
        MediaFileTypes.isVideo(name) -> "video"
        else -> null
    }

    // --- Разбор 207-ответа ---

    /**
     * Разбирает multistatus: response* → href + propstat/prop. Флаг
     * PROCESS_NAMESPACES делает localName независимым от префикса (d:, D_:).
     * Вложенный <collection> внутри resourcetype означает папку.
     */
    private fun parseMultistatus(xml: String): List<Raw> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        val raws = mutableListOf<Raw>()
        var current: Raw? = null
        val text = StringBuilder()
        var capture = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> Raw().also { current = it; raws.add(it) }
                    "collection" -> current?.isDir = true
                    "href", "getcontentlength", "getcontenttype", "getlastmodified" -> {
                        text.setLength(0)
                        capture = true
                    }
                }
                XmlPullParser.TEXT -> if (capture) text.append(parser.text ?: "")
                XmlPullParser.END_TAG -> when (parser.name) {
                    "href" -> { current?.href = text.toString().trim(); capture = false }
                    "getcontentlength" -> {
                        current?.size = text.toString().trim().toLongOrNull()
                        capture = false
                    }
                    "getcontenttype" -> {
                        current?.mime = text.toString().trim().ifEmpty { null }
                        capture = false
                    }
                    "getlastmodified" -> {
                        current?.modified = parseHttpDate(text.toString().trim())
                        capture = false
                    }
                }
            }
            event = parser.next()
        }
        return raws
    }

    /** RFC 1123 ("Tue, 01 Oct 2024 12:34:56 GMT") → epoch ms. */
    private fun parseHttpDate(value: String): Long? = runCatching {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
    }.getOrNull()

    companion object {
        const val WEBDAV_BASE = "https://webdav.yandex.ru"
        private const val ROOT = "disk:/"

        /** Тело PROPFIND: запрашиваемые свойства элементов. */
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/>" +
            "<d:getcontentlength/><d:getcontenttype/><d:getlastmodified/>" +
            "</d:prop></d:propfind>"
    }
}
