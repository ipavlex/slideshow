package com.pzarubin.tvslideshow.data.source

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Локальный источник на основе файловой системы (внутренняя память / USB).
 * Используется на Android TV, где нет системного SAF-пикера.
 */
class FolderSource(
    private val rootDir: File,
    private val recursive: Boolean = false
) : MediaSource {

    override suspend fun list(): List<MediaItem> = withContext(Dispatchers.IO) {
        val files = mutableListOf<File>()
        collectFiles(rootDir, files)
        files.filter { it.isFile && (isImage(it.name) || isVideo(it.name)) }
            .sortedBy { it.name.lowercase() }
            .map { file ->
                MediaItem(
                    id = file.absolutePath,
                    kind = if (isVideo(file.name)) MediaItem.Kind.VIDEO else MediaItem.Kind.PHOTO,
                    name = file.name,
                    mimeType = null,
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                    uri = Uri.fromFile(file)
                )
            }
    }

    override suspend fun open(item: MediaItem): Uri? = item.uri

    private fun collectFiles(dir: File, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            when {
                f.isDirectory -> if (recursive) collectFiles(f, out)
                f.isFile -> out.add(f)
            }
        }
    }

    private fun isImage(name: String): Boolean = extension(name) in IMAGE_EXTENSIONS

    private fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase()

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
