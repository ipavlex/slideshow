package com.pzarubin.tvslideshow.data.auth

import com.pzarubin.tvslideshow.BuildConfig

/**
 * Настройки OAuth Яндекс.
 *
 * ВАЖНО: зарегистрируйте приложение на https://oauth.yandex.ru/ и укажите
 * свой client_id в файле app/secrets.properties (ключ YANDEX_CLIENT_ID).
 * Значение читается на этапе сборки и попадает в BuildConfig.
 */
object YandexConfig {
    val CLIENT_ID: String = BuildConfig.YANDEX_CLIENT_ID

    /**
     * Секрет клиента. Пуст для public-приложений; для confidential
     * (тип «Для доступа к API или отладки») обязателен в запросах /token.
     */
    val CLIENT_SECRET: String = BuildConfig.YANDEX_CLIENT_SECRET

    /** Чтение файлов на Яндекс.Диске. */
    const val SCOPE = "cloud_api:disk.read"

    const val DEVICE_NAME = "TV Слайдшоу"
}
