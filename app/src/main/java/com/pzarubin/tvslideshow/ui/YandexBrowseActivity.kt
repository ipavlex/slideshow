package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.data.source.YandexClient
import com.pzarubin.tvslideshow.data.source.YandexPublicAlbum
import kotlinx.coroutines.launch

/**
 * Экран Яндекс.Диска. Два режима:
 *  - хаб: три раздела — «Альбомы», «Файлы», «Ссылка на альбом»;
 *  - браузер файлов: сетка папок и фото-файлов, навигация по дереву,
 *    запуск слайдшоу для текущей папки.
 */
class YandexBrowseActivity : AppCompatActivity() {

    private enum class Mode { HUB, BROWSE }

    private var mode = Mode.HUB
    private var currentPath = ROOT
    private lateinit var client: YandexClient
    private lateinit var grid: RecyclerView
    private lateinit var pathView: TextView
    private lateinit var startButton: Button
    private var entries = listOf<YandexClient.Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_browse)

        grid = findViewById(R.id.browse_list)
        pathView = findViewById(R.id.browse_path)
        startButton = findViewById(R.id.btn_start_slideshow)

        client = YandexClient(TokenStore(this), SlideCache(this))

        findViewById<Button>(R.id.btn_back).setOnClickListener { navigateBack() }
        startButton.setOnClickListener { startSlideshowHere() }

        grid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)

        showHub()
    }

    // --- Хаб: три раздела ---

    private fun showHub() {
        mode = Mode.HUB
        currentPath = ROOT
        pathView.text = ""
        startButton.visibility = View.GONE
        grid.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
        val cards = listOf(
            GridCard(
                label = getString(R.string.albums_open),
                iconRes = R.drawable.ic_photo,
                subtitle = getString(R.string.yandex_albums_subtitle)
            ),
            GridCard(
                label = getString(R.string.yandex_files),
                iconRes = R.drawable.ic_folder,
                subtitle = getString(R.string.yandex_files_subtitle)
            ),
            GridCard(
                label = getString(R.string.album_link),
                iconRes = R.drawable.ic_cloud,
                subtitle = getString(R.string.album_link_subtitle)
            )
        )
        grid.adapter = GridCardAdapter(lifecycleScope, cards) { position ->
            when (position) {
                0 -> startActivity(
                    Intent(this, AlbumCollectionActivity::class.java)
                        .putExtra(AlbumCollectionActivity.EXTRA_DISK_PATH, ALBUMS_FOLDER)
                )
                1 -> showBrowser()
                else -> showAlbumDialog()
            }
        }
    }

    // --- Браузер файлов ---

    private fun showBrowser() {
        mode = Mode.BROWSE
        currentPath = ROOT
        startButton.visibility = View.VISIBLE
        grid.layoutManager = LinearLayoutManager(this)
        loadEntries()
    }

    private fun loadEntries() {
        pathView.text = currentPath
        lifecycleScope.launch {
            try {
                entries = client.listEntries(currentPath)
                    .filter { it.isDir || it.mediaType == "image" }
                    .sortedWith(
                        compareBy<YandexClient.Entry> { !it.isDir }
                            .thenBy { it.name.lowercase() }
                    )

                grid.adapter = BrowserListAdapter(
                    lifecycleScope,
                    entries.map { it.toCard() }
                ) { position ->
                    val entry = entries.getOrNull(position) ?: return@BrowserListAdapter
                    if (entry.isDir) {
                        currentPath = entry.path
                        loadEntries()
                    }
                    // Фото-файлы не кликабельны — запуск слайдшоу кнопкой.
                }
            } catch (e: Exception) {
                Toast.makeText(this@YandexBrowseActivity, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun YandexClient.Entry.toCard(): GridCard = if (isDir) {
        GridCard(name, R.drawable.ic_folder)
    } else {
        GridCard(
            label = name,
            iconRes = R.drawable.ic_photo,
            nameColorRes = R.color.text_secondary,
            subtitle = getString(R.string.badge_photo)
        )
    }

    private fun navigateBack() {
        when (mode) {
            Mode.HUB -> finish()
            Mode.BROWSE ->
                if (currentPath != ROOT) {
                    currentPath = parentOf(currentPath)
                    loadEntries()
                } else {
                    showHub()
                }
        }
    }

    private fun startSlideshowHere() {
        if (mode != Mode.BROWSE) return
        if (!TokenStore(this).hasToken) return
        startActivity(
            Intent(this, SlideshowActivity::class.java)
                .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_YANDEX)
                .putExtra(SlideshowActivity.EXTRA_YANDEX_PATH, currentPath)
        )
    }

    // --- Ссылка на альбом: ввод только ID ---

    /** Диалог ввода ID публичного альбома; префикс ссылки подставляется сам. */
    private fun showAlbumDialog() {
        val settings = SettingsStore(this)
        val input = EditText(this).apply {
            hint = getString(R.string.album_dialog_hint)
            setText(settings.albumUrl)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val pad = (resources.displayMetrics.density * 16).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.album_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val id = albumIdFrom(input.text.toString())
                if (id.isNotBlank()) {
                    settings.albumUrl = id
                    startActivity(
                        Intent(this, SlideshowActivity::class.java)
                            .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_ALBUM)
                            .putExtra(SlideshowActivity.EXTRA_ALBUM_URL, YandexPublicAlbum.albumUrl(id))
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Ожидаем только ID альбома; если вставили полную ссылку — вырезаем ID.
     * Префикс «https://disk.yandex.ru/a/» подставляется автоматически.
     */
    private fun albumIdFrom(raw: String): String {
        val text = raw.trim()
        val afterPrefix = text.substringAfterLast("/a/", text)
        return afterPrefix.trim().trimEnd('/')
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            navigateBack()
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
