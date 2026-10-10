package com.pzarubin.tvslideshow.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.source.MediaFileTypes
import java.io.File

/**
 * Встроенный браузер папок (внутренняя память + USB). Используется вместо
 * системного SAF-пикера, которого нет на части Android TV. Показывает вложенные
 * папки, а также медиафайлы текущей папки (фото/видео) — чтобы было видно,
 * что в папке есть контент. Возвращает путь выбранной папки через [EXTRA_FOLDER_PATH].
 */
class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var pathView: TextView
    private lateinit var listView: ListView
    private lateinit var adapter: BrowserAdapter

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
        listView = findViewById(R.id.browser_list)
        val selectBtn = findViewById<Button>(R.id.browser_select)

        adapter = BrowserAdapter(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> onEntryClick(position) }
        selectBtn.setOnClickListener { selectCurrent() }
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
            rows = roots.map { BrowserRow(it.second, it.first, isFolder = true) }
        } else {
            val dir = currentDir!!
            pathView.text = dir.absolutePath

            val children = (dir.listFiles() ?: emptyArray())
                .filter { !MediaFileTypes.isIgnored(it.name) }
            val folders = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
            val media = children
                .filter { it.isFile && (MediaFileTypes.isImage(it.name) || MediaFileTypes.isVideo(it.name)) }
                .sortedBy { it.name.lowercase() }

            val list = mutableListOf<BrowserRow>()
            list.add(BrowserRow(getString(R.string.browser_up), null, isFolder = true))
            folders.forEach { list.add(BrowserRow(it.name, it, isFolder = true)) }
            media.forEach {
                list.add(
                    BrowserRow(
                        it.name,
                        null,
                        isFolder = false,
                        isVideo = MediaFileTypes.isVideo(it.name)
                    )
                )
            }
            rows = list
        }
        adapter.submit(rows)
    }

    private fun onEntryClick(position: Int) {
        val row = rows.getOrNull(position) ?: return
        if (!row.isFolder) return
        currentDir = row.target ?: parentOf(currentDir)
        load()
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

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
    }
}

/** Строка списка: папка (кликабельная) или медиафайл (информационный). */
private data class BrowserRow(
    val label: String,
    val target: File?,
    val isFolder: Boolean,
    val isVideo: Boolean = false
)

private class BrowserAdapter(private val context: Context) : BaseAdapter() {

    private val rows = mutableListOf<BrowserRow>()

    fun submit(newRows: List<BrowserRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size

    override fun getItem(position: Int) = rows[position]

    override fun getItemId(position: Int) = position.toLong()

    // Только папки доступны для выбора/фокуса; медиафайлы — просто для информации.
    override fun isEnabled(position: Int) = rows[position].isFolder

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_browser, parent, false)
        val badge = view.findViewById<TextView>(R.id.badge)
        val name = view.findViewById<TextView>(R.id.name)
        val row = rows[position]

        name.text = row.label
        if (row.isFolder) {
            view.setBackgroundResource(R.drawable.bg_tv_row)
            name.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            badge.visibility = View.GONE
        } else {
            view.background = null
            name.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            badge.visibility = View.VISIBLE
            badge.text = context.getString(
                if (row.isVideo) R.string.badge_video else R.string.badge_photo
            )
        }
        return view
    }
}
