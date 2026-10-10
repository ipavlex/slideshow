package com.pzarubin.tvslideshow.ui

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaSession
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.albums.AlbumStore
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.data.source.AlbumSource
import com.pzarubin.tvslideshow.data.source.FolderSource
import com.pzarubin.tvslideshow.data.source.MediaItem
import com.pzarubin.tvslideshow.data.source.MediaSource
import com.pzarubin.tvslideshow.data.source.YandexClient
import com.pzarubin.tvslideshow.data.source.YandexDiskSource
import com.pzarubin.tvslideshow.data.source.YandexPublicAlbum
import com.pzarubin.tvslideshow.domain.PlaylistBuilder
import com.pzarubin.tvslideshow.domain.SlideMode
import com.pzarubin.tvslideshow.domain.SlideshowEngine
import com.pzarubin.tvslideshow.playback.BitmapLoader
import com.pzarubin.tvslideshow.playback.KenBurnsView
import com.pzarubin.tvslideshow.playback.SlideshowView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Полноэкранное слайдшоу (фото и видео) с OSD-оверлеем и MediaSession.
 * Источник задаётся через Intent: локальная папка (SAF) или Яндекс.Диск.
 */
class SlideshowActivity : AppCompatActivity() {

    private lateinit var slideshowView: SlideshowView
    private lateinit var source: MediaSource
    private var engine: SlideshowEngine? = null
    private var mediaSession: MediaSession? = null
    private var generation = 0
    private lateinit var mode: SlideMode
    private var photoDuration = 0L
    private var currentItem: MediaItem? = null
    private var playlist: List<MediaItem> = emptyList()

    /** Настройка «показывать плашку (OSD)» — гейтится в [showOsd]. */
    private var osdEnabled = true

    // Prefetch следующего фото-слайда: декодируем заранее, пока показывается
    // текущий, чтобы HEIC (медленный программный декод) не давал чёрный экран.
    private var prefetchJob: Job? = null
    private var prefetchItem: MediaItem? = null

    @Volatile
    private var prefetchedBitmap: Bitmap? = null
    private var lastShownGen = -1

    // OSD
    private lateinit var osdOverlay: View
    private lateinit var osdTitle: TextView
    private lateinit var osdCounter: TextView
    private lateinit var osdSpeed: TextView
    private lateinit var osdState: TextView
    private lateinit var osdTime: TextView
    private lateinit var osdProgress: ProgressBar
    private val osdHideHandler = Handler(Looper.getMainLooper())
    private val osdHideRunnable = Runnable { hideOsd() }
    private var osdJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = SettingsStore(this)

        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: SOURCE_LOCAL
        var albumUrl: String? = null
        var albumIds: List<String>? = null
        if (sourceType == SOURCE_YANDEX) {
            val token = TokenStore(this).accessToken
            if (token == null) {
                finish()
                return
            }
            source = YandexDiskSource(
                YandexClient(TokenStore(this), SlideCache(this)),
                intent.getStringExtra(EXTRA_YANDEX_PATH) ?: "disk:/"
            )
        } else if (sourceType == SOURCE_ALBUM) {
            albumUrl = intent.getStringExtra(EXTRA_ALBUM_URL)
            if (albumUrl.isNullOrBlank()) {
                finish()
                return
            }
        } else if (sourceType == SOURCE_ALBUMS) {
            albumIds = intent.getStringArrayListExtra(EXTRA_ALBUM_IDS)
            if (albumIds.isNullOrEmpty()) {
                finish()
                return
            }
        } else {
            val folderPath = intent.getStringExtra(EXTRA_FOLDER_PATH)
                ?: run { finish(); return }
            source = FolderSource(File(folderPath), settings.recursive)
        }

        mode = settings.mode
        photoDuration = settings.photoDurationMs.takeIf { it > 0L } ?: mode.photoDurationMs
        osdEnabled = settings.showOsd

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val root = FrameLayout(this)
        slideshowView = SlideshowView(this, null, mode).apply {
            onVideoEnded = { engine?.notifyCurrentFinished() }
        }
        root.addView(
            slideshowView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        osdOverlay = layoutInflater.inflate(R.layout.osd_overlay, root, false)
        root.addView(
            osdOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(root)
        bindOsdViews()
        if (!osdEnabled) osdOverlay.visibility = View.GONE

        // MediaSession (карточка Now Playing) — опционально; не роняем слайдшоу,
        // если media3-session не может инициализироваться на этом устройстве.
        try {
            mediaSession = MediaSession.Builder(this, slideshowView.player)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, SlideshowActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
                .build()
        } catch (e: Exception) {
            mediaSession = null
        }

        startOsdTicker()

        lifecycleScope.launch {
            try {
                // Публичный альбом: источник создаётся асинхронно после bootstrap.
                if (sourceType == SOURCE_ALBUM && !::source.isInitialized) {
                    val api = YandexPublicAlbum()
                    val album = api.open(albumUrl!!)
                    source = AlbumSource(listOf(album), api, SlideCache(this@SlideshowActivity))
                }
                // Коллекция альбомов: данные из локального кэша, отсутствующие —
                // догружаем по сети один раз.
                if (sourceType == SOURCE_ALBUMS && !::source.isInitialized) {
                    val store = AlbumStore(this@SlideshowActivity)
                    val api = YandexPublicAlbum()
                    val albums = albumIds!!.map { id ->
                        store.get(id) ?: api.open(YandexPublicAlbum.albumUrl(id))
                            .also { store.put(id, it) }
                    }
                    source = AlbumSource(albums, api, SlideCache(this@SlideshowActivity))
                }
                val items = PlaylistBuilder.build(source.list(), settings.order, settings.shuffle)
                if (items.isEmpty()) {
                    Toast.makeText(
                        this@SlideshowActivity,
                        R.string.no_media_found,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@launch
                }
                startSlideshow(items)
            } catch (e: Exception) {
                Toast.makeText(
                    this@SlideshowActivity,
                    e.message ?: getString(R.string.error_generic),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun bindOsdViews() {
        osdTitle = osdOverlay.findViewById(R.id.osd_title)
        osdCounter = osdOverlay.findViewById(R.id.osd_counter)
        osdSpeed = osdOverlay.findViewById(R.id.osd_speed)
        osdState = osdOverlay.findViewById(R.id.osd_state)
        osdTime = osdOverlay.findViewById(R.id.osd_time)
        osdProgress = osdOverlay.findViewById(R.id.osd_progress)
    }

    private fun startSlideshow(items: List<MediaItem>) {
        playlist = items
        val engine = SlideshowEngine(items, lifecycleScope) { photoDuration }
        this.engine = engine
        engine.onSlide = { showSlide(it) }
        engine.onStateChanged = { onEngineStateChanged(it) }
        engine.onSpeedChanged = { speed ->
            // Скорость влияет и на видео: ускоряем/замедляем воспроизведение.
            if (currentItem?.kind == MediaItem.Kind.VIDEO) {
                slideshowView.player.setPlaybackSpeed(speed)
            }
            // Отклик на нажатие даже при выключенной плашке.
            showOsd(force = true)
        }
        engine.start()
    }

    /** Целевой размер декодирования: экран с запасом под зум Кен Бёрнса. */
    private fun slideTarget(): IntArray {
        val zoom = KenBurnsView.KEN_BURNS_SCALE
        return intArrayOf(
            (resources.displayMetrics.widthPixels * zoom).toInt(),
            (resources.displayMetrics.heightPixels * zoom).toInt()
        )
    }

    private suspend fun decodeItem(item: MediaItem, targetW: Int, targetH: Int): Bitmap? {
        val uri = source.open(item) ?: return null
        return BitmapLoader.decode(contentResolver, uri, targetW, targetH)
    }

    /**
     * Декодирует фото-слайд: сначала готовый prefetch, иначе — декод «по требованию».
     * Вызывается из IO-корутины.
     */
    private suspend fun loadSlideBitmap(item: MediaItem, targetW: Int, targetH: Int): Bitmap? {
        if (prefetchItem == item) {
            prefetchJob?.join()
            prefetchJob = null
            val bmp = prefetchedBitmap
            prefetchedBitmap = null
            prefetchItem = null
            if (bmp != null) return bmp
        }
        return decodeItem(item, targetW, targetH)
    }

    /**
     * Запускает фоновое декодирование следующего фото-слайда (по кругу).
     * Вызывается после показа текущего слайда.
     */
    private fun startPrefetch(nextIndex: Int) {
        if (playlist.isEmpty()) return
        val nextItem = playlist[nextIndex % playlist.size]
        if (nextItem.kind != MediaItem.Kind.PHOTO) return
        // Уже готово или готовится именно этот слайд.
        if (prefetchItem == nextItem &&
            (prefetchedBitmap != null || prefetchJob?.isActive == true)
        ) return
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchedBitmap?.recycle()
        prefetchedBitmap = null
        prefetchItem = null

        val gen = generation
        val (targetW, targetH) = slideTarget()
        prefetchItem = nextItem
        prefetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val bmp = decodeItem(nextItem, targetW, targetH)
            if (gen == generation) {
                prefetchedBitmap = bmp
            } else {
                bmp?.recycle()
            }
        }
    }

    private fun showSlide(item: MediaItem) {
        currentItem = item
        showOsd()
        when (item.kind) {
            MediaItem.Kind.PHOTO -> {
                val gen = ++generation
                engine?.beginPhotoLoad()
                val (targetW, targetH) = slideTarget()
                // Длительность с учётом скорости — чтобы анимация Ken Burns
                // соответствовала фактическому времени показа слайда.
                val duration = (photoDuration / (engine?.speedMultiplier ?: 1f)).toLong()
                lifecycleScope.launch(Dispatchers.IO) {
                    val bmp = loadSlideBitmap(item, targetW, targetH)
                    if (gen != generation) {
                        bmp?.recycle()
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        if (gen != generation) {
                            bmp?.recycle()
                            return@withContext
                        }
                        if (bmp != null) {
                            lastShownGen = gen
                            slideshowView.showPhoto(bmp, duration)
                            engine?.notifyPhotoShown()
                            startPrefetch((engine?.currentIndex ?: 0) + 1)
                        } else {
                            // Пустое/битое фото — пропускаем к следующему слайду.
                            engine?.next()
                        }
                    }
                }
                // Страховка от вечного чёрного экрана: декод/загрузка зависли.
                lifecycleScope.launch {
                    delay(PHOTO_LOAD_WATCHDOG_MS)
                    if (gen == generation && lastShownGen != gen) engine?.next()
                }
            }
            MediaItem.Kind.VIDEO -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val uri = source.open(item)
                    withContext(Dispatchers.Main) {
                        if (uri != null) {
                            slideshowView.player.setPlaybackSpeed(engine?.speedMultiplier ?: 1f)
                            slideshowView.showVideo(uri, item.name)
                            startPrefetch((engine?.currentIndex ?: 0) + 1)
                        }
                    }
                }
            }
        }
    }

    private fun onEngineStateChanged(state: SlideshowEngine.State) {
        if (currentItem?.kind == MediaItem.Kind.VIDEO) {
            when (state) {
                SlideshowEngine.State.PLAYING -> slideshowView.resumeVideo()
                SlideshowEngine.State.PAUSED -> slideshowView.pauseVideo()
                else -> {}
            }
        }
        showOsd()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && engine != null) showOsd()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = engine ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { e.previous(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { e.next(); true }
            KeyEvent.KEYCODE_DPAD_UP -> { e.speedUp(); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { e.speedDown(); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { e.toggle(); true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                slideshowView.seekVideoBy(-SEEK_STEP_MS)
                showOsd(force = true)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                slideshowView.seekVideoBy(SEEK_STEP_MS)
                showOsd(force = true)
                true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // --- OSD ---

    /**
     * Показывает OSD. При выключенной настройке [showOsdEnabled] плашка
     * показывается только принудительно ([force]) — как отклик на смену
     * скорости или перемотку — и скрывается по таймауту как обычно.
     */
    private fun showOsd(force: Boolean = false) {
        if (!osdEnabled && !force) return
        osdHideHandler.removeCallbacks(osdHideRunnable)
        updateOsdContent()
        osdOverlay.visibility = View.VISIBLE
        osdHideHandler.postDelayed(osdHideRunnable, OSD_TIMEOUT_MS)
    }

    private fun hideOsd() {
        osdOverlay.visibility = View.GONE
    }

    private fun updateOsdContent() {
        val e = engine ?: return
        osdTitle.text = currentItem?.name ?: ""
        osdCounter.text = getString(R.string.osd_counter, e.currentIndex + 1, e.count)
        osdSpeed.text = getString(R.string.speed_value, formatSpeed(e.speedMultiplier))
        osdState.text = if (e.state == SlideshowEngine.State.PAUSED) {
            getString(R.string.osd_paused)
        } else {
            ""
        }
        val isVideo = currentItem?.kind == MediaItem.Kind.VIDEO
        osdProgress.visibility = if (isVideo) View.VISIBLE else View.GONE
        if (!isVideo) osdTime.text = ""
    }

    private fun startOsdTicker() {
        osdJob?.cancel()
        osdJob = lifecycleScope.launch {
            while (isActive) {
                if (currentItem?.kind == MediaItem.Kind.VIDEO &&
                    osdOverlay.visibility == View.VISIBLE
                ) {
                    updateVideoProgress()
                }
                delay(250)
            }
        }
    }

    private fun updateVideoProgress() {
        val p = slideshowView.player
        if (p.currentMediaItem == null) return
        val dur = p.duration.coerceAtLeast(0L)
        val pos = p.currentPosition.coerceIn(0L, dur)
        osdProgress.progress = if (dur > 0L) ((pos * 1000) / dur).toInt() else 0
        osdTime.text = "${formatTime(pos)} / ${formatTime(dur)}"
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun formatSpeed(v: Float): String {
        return if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    override fun onDestroy() {
        super.onDestroy()
        osdJob?.cancel()
        osdHideHandler.removeCallbacks(osdHideRunnable)
        prefetchJob?.cancel()
        prefetchedBitmap?.recycle()
        prefetchedBitmap = null
        engine?.release()
        mediaSession?.release()
        mediaSession = null
        if (::slideshowView.isInitialized) slideshowView.stop()
    }

    companion object {
        const val EXTRA_FOLDER_PATH = "extra_folder_path"
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val EXTRA_YANDEX_PATH = "extra_yandex_path"
        const val EXTRA_ALBUM_URL = "extra_album_url"
        const val EXTRA_ALBUM_IDS = "extra_album_ids"
        const val SOURCE_LOCAL = "local"
        const val SOURCE_YANDEX = "yandex"
        const val SOURCE_ALBUM = "album"
        const val SOURCE_ALBUMS = "albums"
        const val SEEK_STEP_MS = 10_000L
        const val OSD_TIMEOUT_MS = 3000L

        /** Если фото не показалось за это время — пропускаем (защита от зависания). */
        const val PHOTO_LOAD_WATCHDOG_MS = 30_000L
    }
}
