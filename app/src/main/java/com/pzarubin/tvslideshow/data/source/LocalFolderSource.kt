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
            .filter { isImage(it.type, it.name) }
            .sortedBy { it.name?.lowercase() }
            .map { doc ->
                MediaItem(
                    id = doc.uri.toString(),
                    kind = MediaItem.Kind.PHOTO,
                    name = doc.name ?: doc.uri.lastPathSegment ?: "?",
                    mimeType = doc.type,
                    sizeBytes = doc.length(),
                    modifiedAt = doc.lastModified(),
                    uri = doc.uri
                )
            }
    }

    private fun isImage(mime: String?, name: String?): Boolean {
        if (mime?.startsWith("image/") == true) return true
        val n = name ?: return false
        val ext = n.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp"
        )
    }
}
