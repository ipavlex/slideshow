package com.pzarubin.tvslideshow.data.settings

import android.content.Context
import com.pzarubin.tvslideshow.domain.PlaylistBuilder
import com.pzarubin.tvslideshow.domain.SlideMode

/**
 * Настройки слайдшоу, сохраняемые в SharedPreferences.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var order: PlaylistBuilder.Order
        get() = runCatching {
            PlaylistBuilder.Order.valueOf(
                prefs.getString("order", PlaylistBuilder.Order.NAME.name) ?: PlaylistBuilder.Order.NAME.name
            )
        }.getOrDefault(PlaylistBuilder.Order.NAME)
        set(v) = prefs.edit().putString("order", v.name).apply()

    var shuffle: Boolean
        get() = prefs.getBoolean("shuffle", false)
        set(v) = prefs.edit().putBoolean("shuffle", v).apply()

    /** Режим слайдшоу: Ken Burns или Классика. */
    var mode: SlideMode
        get() = runCatching {
            SlideMode.valueOf(
                prefs.getString("mode", SlideMode.KEN_BURNS.name) ?: SlideMode.KEN_BURNS.name
            )
        }.getOrDefault(SlideMode.KEN_BURNS)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    /** 0 — авто (5с «Классика» / 7с «Ken Burns»), иначе явная длительность в мс. */
    var photoDurationMs: Long
        get() = prefs.getLong("photo_duration_ms", 0L)
        set(v) = prefs.edit().putLong("photo_duration_ms", v).apply()

    var recursive: Boolean
        get() = prefs.getBoolean("recursive", false)
        set(v) = prefs.edit().putBoolean("recursive", v).apply()

    /** Показывать OSD-плашку (название файла, счётчик и пр.) поверх слайдшоу. */
    var showOsd: Boolean
        get() = prefs.getBoolean("show_osd", true)
        set(v) = prefs.edit().putBoolean("show_osd", v).apply()

    /** Последняя ссылка на публичный альбом Яндекс.Диска (для повторного запуска). */
    var albumUrl: String
        get() = prefs.getString("album_url", "") ?: ""
        set(v) = prefs.edit().putString("album_url", v).apply()
}
