package com.pzarubin.tvslideshow.data.auth

/**
 * Настройки OAuth Яндекс.
 *
 * ВАЖНО: зарегистрируйте приложение на https://oauth.yandex.ru/ и вставьте
 * свой client_id в [CLIENT_ID]. Без валидного client_id авторизация не работает.
 */
object YandexConfig {
    const val CLIENT_ID = ""

    /** Чтение файлов на Яндекс.Диске. */
    const val SCOPE = "cloud_api:disk.read"

    const val DEVICE_NAME = "TV Слайдшоу"
}
