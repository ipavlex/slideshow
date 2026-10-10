package com.pzarubin.tvslideshow.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.albums.AlbumStore
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.source.YandexClient
import com.pzarubin.tvslideshow.data.source.YandexPublicAlbum
import kotlinx.coroutines.launch

/**
 * Коллекция альбомов: сканирует папку на Яндекс.Диске, в подпапках которой
 * лежат ссылки на публичные альбомы (имя папки = ID или ссылка альбома).
 * Данные альбомов кэшируются локально ([AlbumStore]) — повторные запуски
 * обходятся без сетевых запросов. Клик по альбому — слайдшоу по нему,
 * кнопка «Запустить слайдшоу» — по всем альбомам подряд.
 */
class AlbumCollectionActivity : AppCompatActivity() {

    private lateinit var client: YandexClient
    private lateinit var store: AlbumStore
    private lateinit var api: YandexPublicAlbum
    private lateinit var cache: SlideCache
    private lateinit var grid: RecyclerView
    private lateinit var statusView: TextView

    private var diskPath = DEFAULT_PATH
    private var albums = listOf<Pair<String, YandexPublicAlbum.Album>>()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_album_collection)

        diskPath = intent.getStringExtra(EXTRA_DISK_PATH) ?: DEFAULT_PATH
        client = YandexClient(TokenStore(this), SlideCache(this))
        store = AlbumStore(this)
        api = YandexPublicAlbum()
        cache = SlideCache(this)

        grid = findViewById(R.id.albums_list)
        statusView = findViewById(R.id.albums_status)
        grid.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btn_albums_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_albums_refresh).setOnClickListener { scan(forceRefresh = true) }
        findViewById<Button>(R.id.btn_albums_start).setOnClickListener {
            startSlideshow(albums.map { it.first })
        }

        scan(forceRefresh = false)
    }

    private fun scan(forceRefresh: Boolean) {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            try {
                statusView.text = getString(R.string.albums_loading)
                val dirs = client.listEntries(diskPath).filter { it.isDir }
                val ids = dirs.mapNotNull { YandexPublicAlbum.albumIdFromName(it.name) }.distinct()
                if (ids.isEmpty()) {
                    albums = emptyList()
                    statusView.text = getString(R.string.albums_empty)
                    return@launch
                }
                val loaded = mutableListOf<Pair<String, YandexPublicAlbum.Album>>()
                var failed = 0
                ids.forEachIndexed { index, id ->
                    statusView.text = getString(R.string.albums_progress, index + 1, ids.size)
                    try {
                        val cached = if (forceRefresh) null else store.get(id)
                        val album = cached ?: api.open(YandexPublicAlbum.albumUrl(id))
                            .also { store.put(id, it) }
                        loaded += id to album
                    } catch (e: Exception) {
                        // Один проблемный альбом не должен ломать весь скан.
                        failed++
                    }
                }
                if (loaded.isEmpty()) {
                    albums = emptyList()
                    statusView.text = getString(R.string.albums_empty)
                    return@launch
                }
                albums = loaded
                showGrid()
                statusView.text = if (failed > 0) {
                    getString(R.string.albums_failed, failed)
                } else {
                    ""
                }
            } catch (e: Exception) {
                statusView.text = e.message ?: getString(R.string.error_generic)
            } finally {
                loading = false
            }
        }
    }

    private suspend fun showGrid() {
        val cards = albums.map { (id, album) ->
            val cover = album.photos.firstOrNull()?.let { p ->
                runCatching { cache.download(p.previewUrl, "cover-$id") }.getOrNull()
            }
            GridCard(
                label = album.title.ifBlank { id },
                iconRes = R.drawable.ic_photo,
                subtitle = getString(R.string.albums_count, album.photos.size),
                thumbFile = cover
            )
        }
        grid.adapter = BrowserListAdapter(lifecycleScope, cards) { position ->
            startSlideshow(listOf(albums[position].first))
        }
    }

    private fun startSlideshow(albumIds: List<String>) {
        if (albumIds.isEmpty()) return
        startActivity(
            Intent(this, SlideshowActivity::class.java)
                .putExtra(SlideshowActivity.EXTRA_SOURCE_TYPE, SlideshowActivity.SOURCE_ALBUMS)
                .putStringArrayListExtra(
                    SlideshowActivity.EXTRA_ALBUM_IDS,
                    ArrayList(albumIds)
                )
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_DISK_PATH = "extra_disk_path"

        /** Спец-папка по умолчанию — корень Диска. */
        const val DEFAULT_PATH = "disk:/_albums"
    }
}
