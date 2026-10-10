package com.pzarubin.tvslideshow

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.data.auth.TokenStore
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
    }
}
