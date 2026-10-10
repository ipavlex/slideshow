package com.pzarubin.tvslideshow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.source.MediaFileTypes
import java.io.File

/**
 * Встроенный браузер папок (внутренняя память + USB). Показывает вложенные папки,
 * а также медиафайлы текущей папки (фото/видео) — сеткой карточек. Кнопка
 * «Назад» поднимается на уровень выше, «Запустить слайдшоу» выбирает текущую
 * папку. Возвращает путь выбранной папки через [EXTRA_FOLDER_PATH].
 */
class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var pathView: TextView
    private lateinit var grid: RecyclerView

    private var currentDir: File? = null
    private var roots: List<Pair<File, String>> = emptyList()
    private var rows: List<BrowserRow> = emptyList()

    private val readPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) load() else showNeedAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_browser)

        pathView = findViewById(R.id.browser_path)
        grid = findViewById(R.id.browser_list)
        grid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)

        findViewById<Button>(R.id.btn_back).setOnClickListener { navigateBack() }
        findViewById<Button>(R.id.btn_start_slideshow).setOnClickListener { selectCurrent() }
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) load() else requestAccess()
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            readPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun showNeedAccess() {
        Toast.makeText(this, R.string.storage_access_needed, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun load() {
        if (currentDir == null) {
            roots = storageRoots()
            pathView.text = getString(R.string.browser_root_title)
            rows = roots.map { BrowserRow(it.second, RowKind.FOLDER, it.first) }
        } else {
            val dir = currentDir!!
            pathView.text = dir.absolutePath

            val children = (dir.listFiles() ?: emptyArray())
                .filter { !MediaFileTypes.isIgnored(it.name) }
            val folders = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            val media = children
                .filter { it.isFile && it.length() > 0L && (MediaFileTypes.isImage(it.name) || MediaFileTypes.isVideo(it.name)) }
                .sortedBy { it.name.lowercase() }

            val list = mutableListOf<BrowserRow>()
            folders.forEach { list.add(BrowserRow(it.name, RowKind.FOLDER, it)) }
            media.forEach {
                list.add(
                    BrowserRow(
                        it.name,
                        RowKind.MEDIA,
                        isVideo = MediaFileTypes.isVideo(it.name),
                        file = it
                    )
                )
            }
            rows = list
        }
        grid.adapter = GridCardAdapter(lifecycleScope, rows.map { it.toCard() }) { position ->
            onEntryClick(position)
        }
    }

    private fun onEntryClick(position: Int) {
        val row = rows.getOrNull(position) ?: return
        when (row.kind) {
            RowKind.FOLDER -> {
                currentDir = row.target
                load()
            }
            RowKind.MEDIA -> Unit // не кликабельно
        }
    }

    private fun navigateBack() {
        if (currentDir == null) {
            finish()
        } else {
            currentDir = parentOf(currentDir)
            load()
        }
    }

    private fun parentOf(dir: File?): File? {
        val d = dir ?: return null
        val parent = d.parentFile
        val isContainer = parent == null ||
            parent.absolutePath == "/storage" ||
            parent.absolutePath == "/storage/emulated"
        return if (isContainer) null else parent
    }

    private fun selectCurrent() {
        val dir = currentDir
        if (dir == null) {
            Toast.makeText(this, R.string.browser_select_root_hint, Toast.LENGTH_SHORT).show()
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_FOLDER_PATH, dir.absolutePath))
        finish()
    }

    private fun storageRoots(): List<Pair<File, String>> {
        val list = mutableListOf<Pair<File, String>>()
        list.add(Environment.getExternalStorageDirectory() to getString(R.string.storage_internal))
        val storage = File("/storage")
        storage.listFiles()?.forEach { f ->
            if (f.isDirectory &&
                f.name != "emulated" &&
                f.name != "self" &&
                f.name != "enc_emulated"
            ) {
                list.add(f to getString(R.string.storage_usb, f.name))
            }
        }
        return list
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && currentDir != null) {
            navigateBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun BrowserRow.toCard(): GridCard = when (kind) {
        RowKind.FOLDER -> GridCard(label, R.drawable.ic_folder)
        RowKind.MEDIA -> GridCard(
            label,
            R.drawable.ic_photo,
            R.color.text_secondary,
            getString(if (isVideo) R.string.badge_video else R.string.badge_photo),
            thumbFile = file,
            thumbIsVideo = isVideo
        )
    }

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
        private const val GRID_COLUMNS = 5
    }
}

/** Тип строки сетки браузера. */
private enum class RowKind { FOLDER, MEDIA }

private data class BrowserRow(
    val label: String,
    val kind: RowKind,
    val target: File? = null,
    val isVideo: Boolean = false,
    val file: File? = null
)
