package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.source.YandexClient
import kotlinx.coroutines.launch

/**
 * Выбор папки на Яндекс.Диске: сетка вложенных папок, навигация по дереву,
 * запуск слайдшоу для текущей папки.
 */
class YandexBrowseActivity : AppCompatActivity() {

    private var currentPath = ROOT
    private lateinit var client: YandexClient
    private lateinit var grid: RecyclerView
    private lateinit var pathView: TextView
    private var dirs = listOf<YandexClient.Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_browse)

        grid = findViewById(R.id.browse_list)
        pathView = findViewById(R.id.browse_path)

        client = YandexClient(TokenStore(this), SlideCache(this))

        findViewById<Button>(R.id.btn_back).setOnClickListener { navigateBack() }
        findViewById<Button>(R.id.btn_start_slideshow).setOnClickListener { startSlideshowHere() }
        findViewById<Button>(R.id.btn_albums).setOnClickListener {
            // Спец-папка с альбомами — фиксированное имя в корне Диска.
            startActivity(
                Intent(this, AlbumCollectionActivity::class.java)
                    .putExtra(AlbumCollectionActivity.EXTRA_DISK_PATH, ALBUMS_FOLDER)
            )
        }

        grid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)

        loadFolders()
    }

    private fun loadFolders() {
        pathView.text = currentPath
        lifecycleScope.launch {
            try {
                dirs = client.listEntries(currentPath)
                    .filter { it.isDir }
                    .sortedBy { it.name.lowercase() }

                grid.adapter = GridCardAdapter(
                    lifecycleScope,
                    dirs.map { GridCard(it.name, R.drawable.ic_folder) }
                ) { position ->
                    currentPath = dirs[position].path
                    loadFolders()
                }
            } catch (e: Exception) {
                Toast.makeText(this@YandexBrowseActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun navigateBack() {
        if (currentPath != ROOT) {
            currentPath = parentOf(currentPath)
            loadFolders()
        } else {
            finish()
        }
    }

    private fun startSlideshowHere() {
        if (!TokenStore(this).hasToken) return
        startActivity(
            Intent(this, SlideshowActivity::class.java)
                .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_YANDEX)
                .putExtra(SlideshowActivity.EXTRA_YANDEX_PATH, currentPath)
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && currentPath != ROOT) {
            currentPath = parentOf(currentPath)
            loadFolders()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun parentOf(path: String): String {
        if (path == ROOT) return ROOT
        val trimmed = path.trimEnd('/')
        val idx = trimmed.lastIndexOf('/')
        val parent = trimmed.substring(0, idx + 1)
        return if (parent == "disk:/") ROOT else parent.trimEnd('/')
    }

    companion object {
        const val ROOT = "disk:/"

        /** Спец-папка в корне Диска, в подпапках которой — ссылки на альбомы. */
        const val ALBUMS_FOLDER = "disk:/_albums"
        private const val GRID_COLUMNS = 5
    }
}
