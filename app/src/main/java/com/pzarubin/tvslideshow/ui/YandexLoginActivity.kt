package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.auth.WebDavStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.source.WebDavClient
import kotlinx.coroutines.launch

/**
 * Вход в Яндекс.Диск через WebDAV: форма с логином и «паролем приложения»
 * (Basic auth). Креденшелы проверяются PROPFIND-запросом и сохраняются в
 * [WebDavStore]; при успехе — переход к выбору папки.
 */
class YandexLoginActivity : AppCompatActivity() {

    private lateinit var loginInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var statusView: TextView
    private lateinit var progressView: ProgressBar
    private lateinit var loginButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_login)

        loginInput = findViewById(R.id.login_username)
        passwordInput = findViewById(R.id.login_password)
        statusView = findViewById(R.id.login_status)
        progressView = findViewById(R.id.login_progress)
        loginButton = findViewById(R.id.btn_login)

        loginButton.setOnClickListener { submit() }
        // DONE в поле пароля — сабмит формы, как кнопка «Войти».
        passwordInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
    }

    private fun submit() {
        val username = loginInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            statusView.text = getString(R.string.webdav_fill_fields)
            return
        }
        loginButton.isEnabled = false
        progressView.visibility = View.VISIBLE
        statusView.text = getString(R.string.webdav_checking)
        lifecycleScope.launch {
            val store = WebDavStore(this@YandexLoginActivity)
            try {
                val ok = WebDavClient(store, SlideCache(this@YandexLoginActivity))
                    .verify(username, password)
                if (ok) {
                    store.save(username, password)
                    startActivity(
                        Intent(this@YandexLoginActivity, YandexBrowseActivity::class.java)
                    )
                    finish()
                } else {
                    statusView.text = getString(R.string.webdav_invalid)
                }
            } catch (e: Exception) {
                statusView.text = e.message ?: getString(R.string.webdav_invalid)
            } finally {
                loginButton.isEnabled = true
                progressView.visibility = View.GONE
            }
        }
    }
}
