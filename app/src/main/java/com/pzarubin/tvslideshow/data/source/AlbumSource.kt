package com.pzarubin.tvslideshow.data.source

import android.net.Uri
import com.pzarubin.tvslideshow.data.cache.SlideCache

/**
 * Источник контента: один или несколько публичных фотоальбомов Яндекс.Диска.
 * Фото отдаются готовыми preview-ссылками, видео — скачиванием оригинала.
 * Всё кэшируется локально, как и для обычного Диска.
 */
class AlbumSource(
    albums: List<YandexPublicAlbum.Album>,
    private val api: YandexPublicAlbum,
    private val cache: SlideCache
) : MediaSource {

    private val albums: List<YandexPublicAlbum.Album> = albums

    /** Видео-элементы: id → альбом (нужен для получения ссылки на оригинал). */
    private val videoAlbums = HashMap<String, YandexPublicAlbum.Album>().apply {
        albums.forEach { album ->
            album.videos.forEach { v -> put(v.id, album) }
        }
    }

    override suspend fun list(): List<MediaItem> = albums.flatMap { album ->
        album.photos.map { p ->
            MediaItem(
                id = p.id,
                kind = MediaItem.Kind.PHOTO,
                name = p.name,
                mimeType = p.mimeType,
                sizeBytes = p.sizeBytes,
                remotePath = p.previewUrl
            )
        } + album.videos.map { v ->
            MediaItem(
                id = v.id,
                kind = MediaItem.Kind.VIDEO,
                name = v.name,
                mimeType = v.mimeType,
                sizeBytes = v.sizeBytes,
                remotePath = v.albumItemId
            )
        }
    }

    override suspend fun open(item: MediaItem): Uri? {
        val ref = item.remotePath ?: return null
        val url = if (item.kind == MediaItem.Kind.VIDEO) {
            val album = videoAlbums[item.id] ?: albums.firstOrNull()
                ?: return null
            api.videoDownloadUrl(album, ref)
        } else {
            ref
        }
        val key = "${item.id.hashCode()}-${item.name.replace('/', '_')}"
        return Uri.fromFile(cache.download(url, key))
    }
}
