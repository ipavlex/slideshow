package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.source.YandexClient
import kotlinx.coroutines.launch

/**
 * Выбор папки на Яндекс.Диске: список вложенных папок, навигация по дереву,
 * запуск слайдшоу для текущей папки.
 */
class YandexBrowseActivity : AppCompatActivity() {

    private var currentPath = ROOT
    private lateinit var client: YandexClient
    private lateinit var listView: ListView
    private lateinit var pathView: TextView
    private var folders = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_browse)

        listView = findViewById(R.id.browse_list)
        pathView = findViewById(R.id.browse_path)

        client = YandexClient(TokenStore(this), SlideCache(this))

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position == 0) {
                startSlideshowHere()
            } else {
                currentPath = folders[position - 1]
                loadFolders()
            }
        }

        loadFolders()
    }

    private fun loadFolders() {
        pathView.text = currentPath
        lifecycleScope.launch {
            try {
                val entries = client.listEntries(currentPath)
                    .filter { it.isDir }
                    .sortedBy { it.name.lowercase() }
                folders = entries.map { it.path }

                val labels = mutableListOf(getString(R.string.yandex_start_here))
                labels.addAll(entries.map { it.name })

                listView.adapter = ArrayAdapter(
                    this@YandexBrowseActivity,
                    R.layout.list_item_yandex,
                    labels
                )
            } catch (e: Exception) {
                Toast.makeText(this@YandexBrowseActivity, e.message, Toast.LENGTH_LONG).show()
            }
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
    }
}
