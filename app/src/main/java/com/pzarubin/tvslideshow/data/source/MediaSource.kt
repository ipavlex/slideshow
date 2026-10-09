package com.pzarubin.tvslideshow.data.source

import android.net.Uri

/**
 * Абстракция источника контента. Локальная папка (SAF) и Яндекс.Диск
 * реализуют один интерфейс, поэтому верхние слои не зависят от источника.
 */
interface MediaSource {

    suspend fun list(): List<MediaItem>

    /**
     * Открывает элемент и возвращает локальный [Uri], готовый для декодирования
     * (фото) или воспроизведения (видео). Для локального источника это исходный
     * uri; для Яндекс.Диск — скачанный в кэш файл.
     */
    suspend fun open(item: MediaItem): Uri?
}
