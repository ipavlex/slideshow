package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.auth.YandexAuth
import com.pzarubin.tvslideshow.data.auth.YandexConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Вход в Яндекс.Диск через device-code: показывает URL и код, опрашивает
 * статус авторизации, при успехе переходит к выбору папки.
 */
class YandexLoginActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_login)

        val urlView = findViewById<TextView>(R.id.login_url)
        val codeView = findViewById<TextView>(R.id.login_code)
        statusView = findViewById(R.id.login_status)

        val tokenStore = TokenStore(this)

        lifecycleScope.launch {
            statusView.text = getString(R.string.yandex_requesting)
            try {
                val code = YandexAuth.requestDeviceCode(YandexConfig.CLIENT_ID, tokenStore.deviceId)
                urlView.text = code.verificationUrl
                codeView.text = code.userCode
                statusView.text = getString(R.string.yandex_waiting)

                var interval = code.intervalSeconds.coerceAtLeast(1)
                while (true) {
                    delay(interval * 1000L)
                    when (val r = YandexAuth.pollToken(YandexConfig.CLIENT_ID, code.deviceCode)) {
                        is YandexAuth.PollResult.Success -> {
                            tokenStore.accessToken = r.token.accessToken
                            tokenStore.refreshToken = r.token.refreshToken
                            statusView.text = getString(R.string.yandex_success)
                            startActivity(
                                Intent(this@YandexLoginActivity, YandexBrowseActivity::class.java)
                            )
                            finish()
                            return@launch
                        }
                        YandexAuth.PollResult.Pending -> Unit
                        YandexAuth.PollResult.SlowDown -> interval += 1
                        YandexAuth.PollResult.Denied -> {
                            statusView.text = getString(R.string.yandex_denied)
                            return@launch
                        }
                        YandexAuth.PollResult.Expired -> {
                            statusView.text = getString(R.string.yandex_expired)
                            return@launch
                        }
                        is YandexAuth.PollResult.Error -> {
                            statusView.text = r.message
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                statusView.text = e.message ?: getString(R.string.yandex_error)
            }
        }
    }
}
