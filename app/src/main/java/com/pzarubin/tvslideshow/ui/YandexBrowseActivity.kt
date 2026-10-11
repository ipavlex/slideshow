package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.data.source.MediaFileTypes
import com.pzarubin.tvslideshow.data.source.YandexClient
import com.pzarubin.tvslideshow.data.source.YandexPublicAlbum
import kotlinx.coroutines.launch

/**
 * Экран Яндекс.Диска (прототип 02). Два режима:
 *  - хаб: ряд из трёх плиток — «Альбомы», «Файлы на диске», «Публичная ссылка»;
 *  - браузер файлов: список папок и фото-файлов, навигация по дереву,
 *    запуск слайдшоу для текущей папки.
 *
 * Третья плитка раскрывает панель ввода ссылки (без кнопок): ОК —
 * подключить, Назад на пустом поле — отмена (возврат к плиткам).
 * Возврат назад (из браузера в хаб, из хаба на главный экран) —
 * системной клавишей, как в локальном браузере.
 */
class YandexBrowseActivity : AppCompatActivity() {

    private enum class Mode { HUB, BROWSE }

    private var mode = Mode.HUB
    private var currentPath = ROOT
    private lateinit var client: YandexClient
    private lateinit var grid: RecyclerView
    private lateinit var pathView: TextView
    private lateinit var titleView: TextView
    private lateinit var breadcrumbView: TextView
    private lateinit var startButton: Button
    private lateinit var loadingView: View
    private lateinit var linkPanel: View
    private lateinit var linkInput: EditText
    private var entries = listOf<YandexClient.Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_yandex_browse)

        grid = findViewById(R.id.browse_list)
        pathView = findViewById(R.id.browse_path)
        titleView = findViewById(R.id.browser_title)
        breadcrumbView = findViewById(R.id.hub_breadcrumb)
        startButton = findViewById(R.id.btn_start_slideshow)
        loadingView = findViewById(R.id.browser_loading)
        linkPanel = findViewById(R.id.link_panel)
        linkInput = findViewById(R.id.link_input)

        client = YandexClient(TokenStore(this), SlideCache(this))

        startButton.setOnClickListener { startSlideshowHere() }

        linkInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            ) {
                connectFromInput()
                true
            } else {
                false
            }
        }

        grid.layoutManager = LinearLayoutManager(this)

        showHub()
    }

    // --- Хаб: выбор источника ---

    private fun showHub() {
        mode = Mode.HUB
        currentPath = ROOT
        breadcrumbView.visibility = View.VISIBLE
        // Заголовок «Выберите источник» убран (ревью дизайна): экран
        // идентифицирует только крошка «Яндекс.Диск».
        titleView.visibility = View.GONE
        loadingView.visibility = View.GONE
        pathView.visibility = View.GONE
        startButton.visibility = View.GONE
        grid.visibility = View.VISIBLE
        linkPanel.visibility = View.GONE
        centerList()
        grid.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // Плитки хаба без подписи — только крупная иконка и название (прототип 02).
        val cards = listOf(
            GridCard(
                label = getString(R.string.source_albums_title),
                iconRes = R.drawable.ic_albums_large
            ),
            GridCard(
                label = getString(R.string.source_files_title),
                iconRes = R.drawable.ic_folder_large
            ),
            GridCard(
                label = getString(R.string.source_public_title),
                iconRes = R.drawable.ic_link_large
            )
        )
        grid.adapter = GridCardAdapter(cards) { position ->
            when (position) {
                0 -> startActivity(
                    Intent(this, AlbumCollectionActivity::class.java)
                        .putExtra(AlbumCollectionActivity.EXTRA_DISK_PATH, ALBUMS_FOLDER)
                )
                1 -> showBrowser()
                else -> showLinkPanel()
            }
        }
    }

    // --- Браузер файлов ---

    private fun showBrowser() {
        mode = Mode.BROWSE
        currentPath = ROOT
        breadcrumbView.visibility = View.GONE
        titleView.visibility = View.VISIBLE
        titleView.text = getString(R.string.yandex_browse_title)
        pathView.visibility = View.VISIBLE
        startButton.visibility = View.VISIBLE
        grid.visibility = View.VISIBLE
        linkPanel.visibility = View.GONE
        fillListArea()
        grid.layoutManager = LinearLayoutManager(this)
        loadEntries()
    }

    private fun loadEntries() {
        pathView.text = currentPath
        // Прототип 05: спиннер появляется сразу (в т.ч. по клику на папку),
        // список скрыт, кнопка слайдшоу недоступна.
        loadingView.visibility = View.VISIBLE
        grid.visibility = View.INVISIBLE
        startButton.isEnabled = false
        lifecycleScope.launch {
            try {
                entries = client.listEntries(currentPath)
                    .filter { (it.isDir || it.mediaType == "image") && !MediaFileTypes.isIgnored(it.name) }
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
            } finally {
                loadingView.visibility = View.GONE
                grid.visibility = View.VISIBLE
                startButton.isEnabled = true
                // Фокус мог «потеряться» на скрытом списке — возвращаем на кнопку.
                if (currentFocus == null) startButton.requestFocus()
            }
        }
    }

    private fun YandexClient.Entry.toCard(): GridCard = if (isDir) {
        // Папка: кликабельна, подпись не нужна.
        GridCard(name, R.drawable.ic_folder, isDir = true)
    } else {
        // Фото: не кликабельно, вместо «фото»-бейджа — размер файла.
        GridCard(
            label = name,
            iconRes = R.drawable.ic_photo,
            subtitle = size?.let { BrowserListAdapter.formatSize(it) }
        )
    }

    // --- Панель ввода ссылки (без кнопок) ---

    /** Раскрывает панель ввода ссылки вместо плиток; фокус — в поле. */
    private fun showLinkPanel() {
        grid.visibility = View.GONE
        linkPanel.visibility = View.VISIBLE
        linkInput.setText("")
        linkInput.requestFocus()
    }

    /** Прячет панель, возвращает плитки и фокус на плитку «Публичная ссылка». */
    private fun closeLinkPanel() {
        linkPanel.visibility = View.GONE
        linkInput.setText("")
        grid.visibility = View.VISIBLE
        grid.post {
            (grid.findViewHolderForAdapterPosition(HUB_LINK_POSITION)?.itemView ?: grid)
                .requestFocus()
        }
    }

    /** ОК в панели: сохраняем ID альбома и запускаем слайдшоу. */
    private fun connectFromInput() {
        // Защита от двойного срабатывания (EditorAction + onKeyDown): панель
        // уже закрыта — повторный вызов ничего не делает.
        if (linkPanel.visibility != View.VISIBLE) return
        val id = albumIdFrom(linkInput.text.toString())
        if (id.isBlank()) return
        closeLinkPanel()
        SettingsStore(this).albumUrl = id
        startActivity(
            Intent(this, SlideshowActivity::class.java)
                .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_ALBUM)
                .putExtra(SlideshowActivity.EXTRA_ALBUM_URL, YandexPublicAlbum.albumUrl(id))
        )
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

    // --- Навигация ---

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

    /**
     * Ключи пульта: в панели перехватываем до super — Назад закрывает
     * панель (поле пустое), ОК/Enter — подключает. Остальное — навигация.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (linkPanel.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeLinkPanel()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                connectFromInput()
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            navigateBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- Геометрия списка: центр для плиток хаба, вся область для браузера ---

    /** Ряд плиток хаба — по центру экрана (в прототипе justify-content: center). */
    private fun centerList() {
        val lp = grid.layoutParams as FrameLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.CENTER
        grid.layoutParams = lp
    }

    private fun fillListArea() {
        val lp = grid.layoutParams as FrameLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.TOP or Gravity.START
        grid.layoutParams = lp
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

        /** Позиция плитки «Публичная ссылка» в ряду хаба. */
        private const val HUB_LINK_POSITION = 2
    }
}
