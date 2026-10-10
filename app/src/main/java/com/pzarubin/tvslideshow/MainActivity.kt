package com.pzarubin.tvslideshow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.ui.SlideshowActivity
import com.pzarubin.tvslideshow.ui.SettingsActivity
import com.pzarubin.tvslideshow.ui.YandexBrowseActivity
import com.pzarubin.tvslideshow.ui.YandexLoginActivity

/**
 * Главный экран: выбор источника (локальная папка или Яндекс.Диск) и настройки.
 * Режим слайдшоу (Ken Burns / Классика) настраивается в [SettingsActivity].
 */
class MainActivity : AppCompatActivity() {

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_local_folder)
            .setOnClickListener { launchFolderPicker() }

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

    private fun launchFolderPicker() {
        try {
            folderPicker.launch(null)
        } catch (e: Exception) {
            // Диагностика: показываем реальную причину, чтобы понять, чего не хватает.
            val detail = "${e.javaClass.simpleName}: ${e.message}"
            Toast.makeText(
                this,
                "${getString(R.string.folder_picker_unavailable)}\n$detail",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onFolderPicked(uri: Uri) {
        // Сохраняем разрешение, чтобы папка оставалась доступной после перезапуска.
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Провайдер не выдаёт persistable-разрешение — работаем в рамках сессии.
        }
        startActivity(
            Intent(this, SlideshowActivity::class.java)
                .putExtra(SlideshowActivity.EXTRA_TREE_URI, uri)
        )
    }
}
