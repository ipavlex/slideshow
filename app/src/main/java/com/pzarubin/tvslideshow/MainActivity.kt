package com.pzarubin.tvslideshow

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.ui.FolderBrowserActivity
import com.pzarubin.tvslideshow.ui.SettingsActivity
import com.pzarubin.tvslideshow.ui.SlideshowActivity
import com.pzarubin.tvslideshow.ui.YandexBrowseActivity
import com.pzarubin.tvslideshow.ui.YandexLoginActivity

/**
 * Главный экран: выбор источника (локальная папка или Яндекс.Диск) и настройки.
 * Режим слайдшоу (Ken Burns / Классика) настраивается в [SettingsActivity].
 */
class MainActivity : AppCompatActivity() {

    private val folderBrowser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val path = result.data?.getStringExtra(FolderBrowserActivity.EXTRA_FOLDER_PATH)
            if (result.resultCode == RESULT_OK && !path.isNullOrBlank()) {
                startActivity(
                    Intent(this, SlideshowActivity::class.java)
                        .putExtra(SlideshowActivity.EXTRA_FOLDER_PATH, path)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_local_folder).setOnClickListener {
            folderBrowser.launch(Intent(this, FolderBrowserActivity::class.java))
        }

        findViewById<Button>(R.id.btn_yandex).setOnClickListener {
            val target = if (TokenStore(this).hasToken) {
                YandexBrowseActivity::class.java
            } else {
                YandexLoginActivity::class.java
            }
            startActivity(Intent(this, target))
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btn_album).setOnClickListener { showAlbumDialog() }
    }

    /** Диалог ввода ссылки на публичный альбом Яндекс.Диска. */
    private fun showAlbumDialog() {
        val settings = SettingsStore(this)
        val input = EditText(this).apply {
            hint = getString(R.string.album_dialog_hint)
            setText(settings.albumUrl)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = (resources.displayMetrics.density * 16).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.album_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    settings.albumUrl = url
                    startActivity(
                        Intent(this, SlideshowActivity::class.java)
                            .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_ALBUM)
                            .putExtra(SlideshowActivity.EXTRA_ALBUM_URL, url)
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
