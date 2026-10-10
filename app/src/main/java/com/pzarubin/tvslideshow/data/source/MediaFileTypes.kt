package com.pzarubin.tvslideshow.data.source

/**
 * Общие наборы расширений изображений и видео. Используются и в листинге
 * источника, и в браузере папок (чтобы показывать медиафайлы).
 */
object MediaFileTypes {
    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp"
    )
    val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "m2ts",
        "mpg", "mpeg", "flv", "wmv"
    )

    fun isImage(name: String): Boolean = extension(name) in IMAGE_EXTENSIONS
    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase()
}
