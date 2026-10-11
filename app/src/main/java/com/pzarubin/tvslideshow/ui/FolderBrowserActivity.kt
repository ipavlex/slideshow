package com.pzarubin.tvslideshow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.data.source.MediaFileTypes
import com.pzarubin.tvslideshow.playback.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Встроенный браузер папок (внутренняя память + USB). Показывает вложенные папки,
 * а также медиафайлы текущей папки — вертикальным списком строк. Кнопка
 * «Назад» (системная) поднимается на уровень выше, «Смотреть слайдшоу» выбирает
 * текущую папку. Пока список грузится — спиннер «Загрузка…», кнопка недоступна.
 * Возвращает путь выбранной папки через [EXTRA_FOLDER_PATH].
 */
class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var pathView: TextView
    private lateinit var grid: RecyclerView
    private lateinit var loadingView: View
    private lateinit var startButton: Button

    private var currentDir: File? = null
    private var roots: List<Pair<File, String>> = emptyList()
    private var rows: List<BrowserRow> = emptyList()

    /** Сохранённая папка восстанавливается один раз, при первом входе. */
    private var restoredFromSettings = false

    /** Счётчик запусков [load] — для отбрасывания устаревших ответов. */
    private var loadGeneration = 0

    private val readPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) load() else showNeedAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_browser)

        Thumbnails.init(this)

        pathView = findViewById(R.id.browser_path)
        grid = findViewById(R.id.browser_list)
        grid.layoutManager = LinearLayoutManager(this)
        loadingView = findViewById(R.id.browser_loading)
        startButton = findViewById(R.id.btn_start_slideshow)

        // В свёрстанном дизайне (прототип 04) кнопки «Назад» нет — на уровень
        // выше поднимает системная клавиша.
        startButton.setOnClickListener { selectCurrent() }
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            // П.7 «Помнить последнюю папку»: при первом входе открываем сохранённую
            // папку (или ближайшего существующего родителя — например, если USB
            // накопитель перемонтировался с другим путём), иначе корень.
            if (currentDir == null && !restoredFromSettings) {
                restoredFromSettings = true
                currentDir = restoreLastFolder()
            }
            load()
        } else requestAccess()
    }

    private fun restoreLastFolder(): File? {
        val saved = SettingsStore(this).lastLocalFolder
        if (saved.isBlank()) return null
        var dir: File? = File(saved)
        // Поднимаемся вверх, пока папка не станет существующей и читаемой
        // (например, USB накопитель перемонтировался или отключён).
        while (dir != null && (!dir.exists() || !dir.isDirectory || !dir.canRead())) {
            dir = dir.parentFile
        }
        // Останавливаемся на уровне корней хранилищ: контейнеры вроде /storage
        // или / не годятся как текущая папка — открываем корень браузера.
        val candidate = dir ?: return null
        val rootPaths = storageRoots().map { it.first.absolutePath }
        if (candidate.absolutePath in rootPaths) return candidate
        return if (parentOf(candidate) == null) null else candidate
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
        // Прототип 05: на время загрузки — спиннер «Загрузка…», список скрыт,
        // кнопка слайдшоу недоступна и не принимает фокус.
        loadingView.visibility = View.VISIBLE
        grid.visibility = View.INVISIBLE
        startButton.isEnabled = false
        startButton.isFocusable = false

        // Поколение загрузки: быстрые клики по папкам — устаревший ответ
        // не перебивает свежий (ревью: гонка «кто позже завершился»).
        val gen = ++loadGeneration

        val dir = currentDir
        // П.7: запоминаем последнюю открытую папку при каждой навигации.
        if (dir != null) SettingsStore(this).lastLocalFolder = dir.absolutePath
        lifecycleScope.launch {
            val newRows = withContext(Dispatchers.IO) { buildRows(dir) }
            if (gen != loadGeneration) return@launch
            rows = newRows
            pathView.text =
                if (dir == null) getString(R.string.browser_root_title) else dir.absolutePath
            grid.adapter = BrowserListAdapter(lifecycleScope, newRows.map { it.toCard() }) { position ->
                onEntryClick(position)
            }
            loadingView.visibility = View.GONE
            grid.visibility = View.VISIBLE
            startButton.isEnabled = true
            startButton.isFocusable = true
            startButton.requestFocus()
        }
    }

    /** Чтение каталога/корней — вне UI-потока. */
    private fun buildRows(dir: File?): List<BrowserRow> {
        if (dir == null) {
            roots = storageRoots()
            // Для корней-дисков метаданные не считаем — счётчики нулевые (подписи нет).
            return roots.map { BrowserRow(it.second, RowKind.FOLDER, it.first) }
        }
        val children = dir.listFiles()
        if (children == null) {
            // Типично для USB без выданного доступа: listFiles() = null.
            Log.w(TAG, "listFiles() = null: ${dir.absolutePath}, canRead=${dir.canRead()}")
        }
        val entries = children ?: emptyArray()
        val folders = entries
            .filter { !MediaFileTypes.isIgnored(it.name) && it.isDirectory }
            .sortedBy { it.name.lowercase() }
        val media = entries
            .filter { it.isFile && it.length() > 0L && (MediaFileTypes.isImage(it.name) || MediaFileTypes.isVideo(it.name)) }
            .sortedBy { it.name.lowercase() }

        val list = mutableListOf<BrowserRow>()
        folders.forEach { folder ->
            // Метаданные папки (прототип 04): прямые дети — фото / видео / прочие файлы.
            var photos = 0
            var videos = 0
            var files = 0
            (folder.listFiles() ?: emptyArray()).forEach { child ->
                if (MediaFileTypes.isIgnored(child.name)) return@forEach
                when {
                    child.isDirectory -> return@forEach
                    MediaFileTypes.isImage(child.name) -> photos++
                    MediaFileTypes.isVideo(child.name) -> videos++
                    else -> files++
                }
            }
            list.add(
                BrowserRow(
                    folder.name,
                    RowKind.FOLDER,
                    folder,
                    photoCount = photos,
                    videoCount = videos,
                    fileCount = files
                )
            )
        }
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
        return list
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
        // П.7: выбранная для слайдшоу папка — тоже «последняя открытая».
        SettingsStore(this).lastLocalFolder = dir.absolutePath
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
        RowKind.FOLDER -> GridCard(
            label,
            R.drawable.ic_folder,
            subtitle = formatFolderMeta(photoCount, videoCount, fileCount),
            isDir = true
        )
        // Справа — размер файла (прототип 04); «ФОТО»/«ВИДЕО» больше не пишем.
        RowKind.MEDIA -> GridCard(
            label,
            R.drawable.ic_photo,
            subtitle = BrowserListAdapter.formatSize(file?.length() ?: 0L),
            thumbFile = file,
            thumbIsVideo = isVideo
        )
    }

    /**
     * Подпись папки как в прототипе 04: «12 фото · 3 видео» (только ненулевые
     * части), если медиафайлов нет, но есть прочие — «47 файлов», иначе null
     * (подпись не показывается, например у пустой папки).
     */
    private fun formatFolderMeta(photoCount: Int, videoCount: Int, fileCount: Int): String? {
        val parts = mutableListOf<String>()
        if (photoCount > 0) parts.add(getString(R.string.browser_meta_photos, photoCount))
        if (videoCount > 0) parts.add(getString(R.string.browser_meta_videos, videoCount))
        if (photoCount + videoCount > 0) return parts.joinToString(" · ")
        if (fileCount > 0) return getString(R.string.browser_meta_files, fileCount)
        return null
    }

    companion object {
        private const val TAG = "FolderBrowser"
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
    }
}

/** Тип строки сетки браузера. */
private enum class RowKind { FOLDER, MEDIA }

private data class BrowserRow(
    val label: String,
    val kind: RowKind,
    val target: File? = null,
    val isVideo: Boolean = false,
    val file: File? = null,
    /** Прямые дети папки (прототип 04): фото / видео / прочие файлы; для корней — нули. */
    val photoCount: Int = 0,
    val videoCount: Int = 0,
    val fileCount: Int = 0
)
