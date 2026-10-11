package com.pzarubin.tvslideshow.data.auth

import android.content.Context

/**
 * Хранит креденшелы WebDAV Яндекс.Диска: логин и «пароль приложения».
 * Для простоты используем SharedPreferences; на будущее — EncryptedSharedPreferences.
 */
class WebDavStore(context: Context) {

    private val prefs = context.getSharedPreferences("webdav_auth", Context.MODE_PRIVATE)

    var username: String?
        get() = prefs.getString("username", null)
        set(v) = prefs.edit().putString("username", v).apply()

    var appPassword: String?
        get() = prefs.getString("app_password", null)
        set(v) = prefs.edit().putString("app_password", v).apply()

    val hasCredentials: Boolean
        get() = !username.isNullOrBlank() && !appPassword.isNullOrBlank()

    fun save(username: String, password: String) {
        prefs.edit()
            .putString("username", username)
            .putString("app_password", password)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
