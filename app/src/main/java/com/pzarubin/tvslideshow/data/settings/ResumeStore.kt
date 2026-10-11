package com.pzarubin.tvslideshow.data.settings

import android.content.Context

/**
 * Хранит последний показанный слайд для каждого источника слайдшоу,
 * чтобы при повторном запуске предложить «Продолжить» или «С начала».
 *
 * Ключ — идентификатор источника (тип + путь/URL/альбомы), значение —
 * [com.pzarubin.tvslideshow.data.source.MediaItem.id] последнего слайда.
 */
class ResumeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("resume_store", Context.MODE_PRIVATE)

    fun get(sourceKey: String): String? = prefs.getString(sourceKey, null)

    fun put(sourceKey: String, itemId: String) {
        prefs.edit().putString(sourceKey, itemId).apply()
    }

    fun clear(sourceKey: String) {
        prefs.edit().remove(sourceKey).apply()
    }

    /**
     * Последний запущенный источник (строка вида «local|/путь», «yandex|…»,
     * «album|…», «albums|id1,id2») — для опции «Автозапуск последней папки».
     */
    var lastSource: String?
        get() = prefs.getString(KEY_LAST_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_LAST_SOURCE, value).apply()

    private companion object {
        const val KEY_LAST_SOURCE = "last_source"
    }
}
