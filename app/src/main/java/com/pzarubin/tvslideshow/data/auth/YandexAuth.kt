package com.pzarubin.tvslideshow.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * OAuth Яндекс по протоколу device-code (для устройств без браузера).
 * Все сетевые вызовы выполняются в IO-диспетчере.
 */
object YandexAuth {

    private const val OAUTH_BASE = "https://oauth.yandex.ru"

    private val client = OkHttpClient()

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int
    )

    data class Token(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Int
    )

    sealed class PollResult {
        data class Success(val token: Token) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        object Denied : PollResult()
        object Expired : PollResult()
        data class Error(val message: String) : PollResult()
    }

    /** Секрет клиента — добавляется в тело, если задан (confidential-приложения). */
    private fun FormBody.Builder.addClientSecret(): FormBody.Builder =
        if (YandexConfig.CLIENT_SECRET.isNotBlank()) add("client_secret", YandexConfig.CLIENT_SECRET) else this

    /** Шаг 1: запросить код устройства. */
    suspend fun requestDeviceCode(clientId: String, deviceId: String): DeviceCode =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("client_id", clientId)
                .add("device_id", deviceId)
                .add("device_name", YandexConfig.DEVICE_NAME)
                .add("scope", YandexConfig.SCOPE)
                .addClientSecret()
                .build()
            val req = Request.Builder().url("$OAUTH_BASE/device/code").post(body).build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "")
                if (!resp.isSuccessful) {
                    throw IOException(
                        json.optString("error_description", "Ошибка запроса кода")
                    )
                }
                DeviceCode(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUrl = json.getString("verification_url"),
                    intervalSeconds = json.optInt("interval", 5),
                    expiresInSeconds = json.optInt("expires_in", 300)
                )
            }
        }

    /** Шаг 2: опрос статуса авторизации. */
    suspend fun pollToken(clientId: String, deviceCode: String): PollResult =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "device_code")
                .add("code", deviceCode)
                .add("client_id", clientId)
                .addClientSecret()
                .build()
            val req = Request.Builder().url("$OAUTH_BASE/token").post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                val json = JSONObject(text)
                if (resp.isSuccessful) {
                    PollResult.Success(
                        Token(
                            accessToken = json.getString("access_token"),
                            refreshToken = json.optString("refresh_token").ifBlank { null },
                            expiresInSeconds = json.optInt("expires_in", 0)
                        )
                    )
                } else {
                    when (json.optString("error", "")) {
                        "authorization_pending" -> PollResult.Pending
                        "slow_down" -> PollResult.SlowDown
                        "access_denied" -> PollResult.Denied
                        "expired_token" -> PollResult.Expired
                        else -> PollResult.Error(
                            json.optString("error_description", "Ошибка авторизации")
                        )
                    }
                }
            }
        }

    /** Обновление access-токена по refresh-токену. */
    suspend fun refreshToken(clientId: String, refreshToken: String): Token =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .addClientSecret()
                .build()
            val req = Request.Builder().url("$OAUTH_BASE/token").post(body).build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "")
                if (!resp.isSuccessful) {
                    throw IOException(
                        json.optString("error_description", "Ошибка обновления токена")
                    )
                }
                Token(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token").ifBlank { null },
                    expiresInSeconds = json.optInt("expires_in", 0)
                )
            }
        }
}
