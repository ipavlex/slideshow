package com.pzarubin.tvslideshow.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pzarubin.tvslideshow.R
import java.io.File

/**
 * Встроенный браузер папок (внутренняя память + USB). Используется вместо
 * системного SAF-пикера, которого нет на части Android TV. Возвращает путь
 * к выбранной папке через [EXTRA_FOLDER_PATH].
 */
class FolderBrowserActivity : AppCompatActivity() {

    private lateinit var pathView: TextView
    private lateinit var adapter: ArrayAdapter<String>

    private var currentDir: File? = null
    private var roots: List<Pair<File, String>> = emptyList()
    private var entries: List<File> = emptyList()

    private val readPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) load() else showNeedAccess()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_folder_browser)

        pathView = findViewById(R.id.browser_path)
        val listView = findViewById<ListView>(R.id.browser_list)
        val selectBtn = findViewById<Button>(R.id.browser_select)

        adapter = ArrayAdapter(this, R.layout.item_folder, android.R.id.text1, mutableListOf())
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
        adapter.clear()
        if (currentDir == null) {
            roots = storageRoots()
            pathView.text = getString(R.string.browser_root_title)
            roots.forEach { adapter.add(it.second) }
        } else {
            val dir = currentDir!!
            pathView.text = dir.absolutePath
            adapter.add(getString(R.string.browser_up))
            entries = (dir.listFiles() ?: emptyArray())
                .filter { it.isDirectory }
                .sortedBy { it.name.lowercase() }
            entries.forEach { adapter.add(it.name) }
        }
    }

    private fun onEntryClick(position: Int) {
        if (currentDir == null) {
            currentDir = roots[position].first
        } else if (position == 0) {
            val parent = currentDir!!.parentFile
            val isContainer = parent == null ||
                parent.absolutePath == "/storage" ||
                parent.absolutePath == "/storage/emulated"
            currentDir = if (isContainer) null else parent
        } else {
            currentDir = entries[position - 1]
        }
        load()
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
