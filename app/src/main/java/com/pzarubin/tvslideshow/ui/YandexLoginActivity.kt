package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.auth.YandexAuth
import com.pzarubin.tvslideshow.data.auth.YandexConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Вход в Яндекс.Диск через device-code: показывает URL и код, опрашивает
 * статус авторизации, при успехе переходит к выбору папки. Кнопка «Проверить
 * подключение» перезапускает опрос.
 */
class YandexLoginActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var urlView: TextView
    private lateinit var codeView: TextView
    private lateinit var qrView: ImageView
    private lateinit var tokenStore: TokenStore
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_login)

        urlView = findViewById(R.id.login_url)
        codeView = findViewById(R.id.login_code)
        qrView = findViewById(R.id.login_qr)
        statusView = findViewById(R.id.login_status)
        tokenStore = TokenStore(this)

        findViewById<Button>(R.id.btn_login_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_login_retry).setOnClickListener { startAuth() }

        startAuth()
    }

    private fun startAuth() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            statusView.text = getString(R.string.yandex_requesting)
            urlView.text = ""
            codeView.text = ""
            qrView.setImageBitmap(null)
            try {
                val code = YandexAuth.requestDeviceCode(YandexConfig.CLIENT_ID, tokenStore.deviceId)
                urlView.text = code.verificationUrl
                codeView.text = code.userCode
                qrView.setImageBitmap(generateQrCode(code.verificationUrl, 512))
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

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
    }

    private fun generateQrCode(text: String, size: Int): Bitmap {
        val matrix: BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
