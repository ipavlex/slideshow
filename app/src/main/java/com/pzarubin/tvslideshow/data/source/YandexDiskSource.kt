package com.pzarubin.tvslideshow.data.source

import android.net.Uri

/**
 * Источник контента: папка на Яндекс.Диске.
 * Возвращает фото и видео; открытие элемента скачивает файл в локальный кэш.
 */
class YandexDiskSource(
    private val client: YandexClient,
    private val folderPath: String
) : MediaSource {

    override suspend fun list(): List<MediaItem> =
        client.listEntries(folderPath)
            .filter { !it.isDir }
            .filter { isPhoto(it) || isVideo(it) }
            .sortedBy { it.name.lowercase() }
            .map { e ->
                MediaItem(
                    id = e.path,
                    kind = if (isVideo(e)) MediaItem.Kind.VIDEO else MediaItem.Kind.PHOTO,
                    name = e.name,
                    mimeType = e.mimeType,
                    sizeBytes = e.size,
                    modifiedAt = e.modified,
                    remotePath = e.path
                )
            }

    override suspend fun open(item: MediaItem): Uri? {
        val path = item.remotePath ?: return null
        return Uri.fromFile(client.downloadToCache(path))
    }

    private fun isPhoto(e: YandexClient.Entry): Boolean =
        e.mediaType == "image" || e.mimeType?.startsWith("image/") == true

    private fun isVideo(e: YandexClient.Entry): Boolean =
        e.mediaType == "video" || e.mimeType?.startsWith("video/") == true
}
