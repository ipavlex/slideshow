package com.pzarubin.tvslideshow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.domain.SlideMode
import com.pzarubin.tvslideshow.ui.SlideshowActivity

/**
 * Главный экран: выбор режима и папки.
 *
 * Фаза 1 — локальная папка через SAF. В Фазе 4 добавится Яндекс.Диск.
 */
class MainActivity : AppCompatActivity() {

    private var pendingMode = SlideMode.KEN_BURNS

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_ken_burns)
            .setOnClickListener { pickFolder(SlideMode.KEN_BURNS) }
        findViewById<Button>(R.id.btn_classic)
            .setOnClickListener { pickFolder(SlideMode.CLASSIC) }
    }

    private fun pickFolder(mode: SlideMode) {
        pendingMode = mode
        folderPicker.launch(null)
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
                .putExtra(SlideshowActivity.EXTRA_MODE, pendingMode.name)
        )
    }
}
