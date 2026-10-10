package com.pzarubin.tvslideshow.data.albums

import android.content.Context
import com.pzarubin.tvslideshow.data.source.YandexPublicAlbum
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Локальный кэш данных публичных альбомов (JSON в filesDir/albums).
 * Позволяет сканировать «спец-папку» на Диске один раз: при повторных
 * запусках данные альбомов читаются с диска без сетевых запросов.
 */
class AlbumStore(context: Context) {

    private val dir = File(context.filesDir, "albums").apply { mkdirs() }

    fun has(albumId: String): Boolean = File(dir, "$albumId.json").exists()

    /** Читает альбом из кэша; null — если нет или файл повреждён. */
    fun get(albumId: String): YandexPublicAlbum.Album? {
        val f = File(dir, "$albumId.json")
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            YandexPublicAlbum.Album(
                url = o.getString("url"),
                title = o.optString("title"),
                hash = o.getString("hash"),
                photos = o.optJSONArray("photos").toList { p ->
                    YandexPublicAlbum.Photo(
                        id = p.getString("id"),
                        name = p.optString("name"),
                        previewUrl = p.getString("previewUrl"),
                        sizeBytes = p.optLong("sizeBytes").takeIf { it > 0L },
                        mimeType = p.optString("mimeType").ifBlank { null }
                    )
                },
                videos = o.optJSONArray("videos").toList { v ->
                    YandexPublicAlbum.Video(
                        id = v.getString("id"),
                        name = v.optString("name"),
                        albumItemId = v.getString("albumItemId"),
                        sizeBytes = v.optLong("sizeBytes").takeIf { it > 0L },
                        mimeType = v.optString("mimeType").ifBlank { null }
                    )
                }
            )
        }.getOrNull()
    }

    fun put(albumId: String, album: YandexPublicAlbum.Album) {
        val o = JSONObject()
            .put("id", albumId)
            .put("url", album.url)
            .put("title", album.title)
            .put("hash", album.hash)
            .put("fetchedAt", System.currentTimeMillis())
            .put("photos", JSONArray().apply {
                album.photos.forEach { p ->
                    put(JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("previewUrl", p.previewUrl)
                        .put("sizeBytes", p.sizeBytes ?: 0L)
                        .put("mimeType", p.mimeType ?: ""))
                }
            })
            .put("videos", JSONArray().apply {
                album.videos.forEach { v ->
                    put(JSONObject()
                        .put("id", v.id)
                        .put("name", v.name)
                        .put("albumItemId", v.albumItemId)
                        .put("sizeBytes", v.sizeBytes ?: 0L)
                        .put("mimeType", v.mimeType ?: ""))
                }
            })
        File(dir, "$albumId.json").writeText(o.toString())
    }

    fun delete(albumId: String) {
        File(dir, "$albumId.json").delete()
    }

    private inline fun <T> JSONArray?.toList(create: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            runCatching { create(getJSONObject(i)) }.getOrNull()
        }
    }
}
