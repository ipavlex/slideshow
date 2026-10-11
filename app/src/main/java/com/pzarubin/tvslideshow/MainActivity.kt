package com.pzarubin.tvslideshow

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.settings.ResumeStore
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.data.source.YandexPublicAlbum
import com.pzarubin.tvslideshow.ui.FolderBrowserActivity
import com.pzarubin.tvslideshow.ui.SettingsActivity
import com.pzarubin.tvslideshow.ui.SlideshowActivity
import com.pzarubin.tvslideshow.ui.YandexBrowseActivity
import com.pzarubin.tvslideshow.ui.YandexLoginActivity

/**
 * Главный экран (прототип 01): ряд из трёх плиток — локальная папка,
 * Яндекс.Диск и настройки. Режим слайдшоу (Ken Burns / Классика)
 * настраивается в [SettingsActivity].
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

        // Иконка плитки белеет в фокусе (ревью дизайна).
        com.pzarubin.tvslideshow.ui.TileTint.bind(
            findViewById(R.id.btn_local_folder), findViewById(R.id.icon_local))
        com.pzarubin.tvslideshow.ui.TileTint.bind(
            findViewById(R.id.btn_yandex), findViewById(R.id.icon_yandex))
        com.pzarubin.tvslideshow.ui.TileTint.bind(
            findViewById(R.id.btn_settings), findViewById(R.id.icon_settings))

        findViewById<View>(R.id.btn_local_folder).setOnClickListener {
            folderBrowser.launch(Intent(this, FolderBrowserActivity::class.java))
        }

        findViewById<View>(R.id.btn_yandex).setOnClickListener {
            val target = if (TokenStore(this).hasToken) {
                YandexBrowseActivity::class.java
            } else {
                YandexLoginActivity::class.java
            }
            startActivity(Intent(this, target))
        }

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        maybeAutostartLastFolder(savedInstanceState == null)
    }

    /**
     * Опция «Автозапуск последней папки»: при старте приложения сразу открыть
     * последнее слайдшоу. Только при холодном старте (не при возврате назад),
     * иначе после выхода из слайдшоу оно запустится снова.
     */
    private fun maybeAutostartLastFolder(coldStart: Boolean) {
        if (!coldStart) return
        val settings = SettingsStore(this)
        if (!settings.autostart) return

        val last = ResumeStore(this).lastSource ?: return
        val type = last.substringBefore('|')
        val payload = last.substringAfter('|', "")

        val intent = when (type) {
            "local" ->
                if (payload.isBlank()) null
                else Intent(this, SlideshowActivity::class.java)
                    .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_LOCAL)
                    .putExtra(SlideshowActivity.EXTRA_FOLDER_PATH, payload)
            "yandex" ->
                if (!TokenStore(this).hasToken) null
                else Intent(this, SlideshowActivity::class.java)
                    .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_YANDEX)
                    .putExtra(SlideshowActivity.EXTRA_YANDEX_PATH, payload.ifBlank { "disk:/" })
            "album" ->
                if (payload.isBlank()) null
                else Intent(this, SlideshowActivity::class.java)
                    .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_ALBUM)
                    .putExtra(SlideshowActivity.EXTRA_ALBUM_URL, payload)
            "albums" ->
                if (payload.isBlank()) null
                else Intent(this, SlideshowActivity::class.java)
                    .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_ALBUMS)
                    .putStringArrayListExtra(
                        SlideshowActivity.EXTRA_ALBUM_IDS,
                        ArrayList(payload.split(','))
                    )
            else -> null
        } ?: return

        startActivity(intent)
    }
}
