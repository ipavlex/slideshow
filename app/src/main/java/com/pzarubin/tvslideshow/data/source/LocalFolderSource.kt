package com.pzarubin.tvslideshow.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Локальный источник: папка, выбранная через SAF (ACTION_OPEN_DOCUMENT_TREE).
 * Читает прямые дочерние файлы; рекурсия подпапок — опция на будущее.
 */
class LocalFolderSource(
    private val context: Context,
    private val treeUri: Uri
) : MediaSource {

    override suspend fun list(): List<MediaItem> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext emptyList()

        root.listFiles()
            .filter { it.isFile }
            .filter { isImage(it.type, it.name) || isVideo(it.type, it.name) }
            .sortedBy { it.name?.lowercase() }
            .map { doc ->
                MediaItem(
                    id = doc.uri.toString(),
                    kind = if (isVideo(doc.type, doc.name)) {
                        MediaItem.Kind.VIDEO
                    } else {
                        MediaItem.Kind.PHOTO
                    },
                    name = doc.name ?: doc.uri.lastPathSegment ?: "?",
                    mimeType = doc.type,
                    sizeBytes = doc.length(),
                    modifiedAt = doc.lastModified(),
                    uri = doc.uri
                )
            }
    }

    override suspend fun open(item: MediaItem): Uri? = item.uri

    private fun isImage(mime: String?, name: String?): Boolean {
        if (mime?.startsWith("image/") == true) return true
        return extension(name) in IMAGE_EXTENSIONS
    }

    private fun isVideo(mime: String?, name: String?): Boolean {
        if (mime?.startsWith("video/") == true) return true
        return extension(name) in VIDEO_EXTENSIONS
    }

    private fun extension(name: String?): String {
        val n = name ?: return ""
        return n.substringAfterLast('.', "").lowercase()
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp"
        )
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts",
            "mpg", "mpeg", "flv", "wmv"
        )
    }
}
