package com.pzarubin.tvslideshow.data.auth

import android.content.Context
import java.util.UUID

/**
 * Хранит OAuth-токены Яндекс и стабильный device_id.
 * Для простоты используем SharedPreferences; на будущее — EncryptedSharedPreferences.
 */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("yandex_auth", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(v) = prefs.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(v) = prefs.edit().putString("refresh_token", v).apply()

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    val hasToken: Boolean get() = !accessToken.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
