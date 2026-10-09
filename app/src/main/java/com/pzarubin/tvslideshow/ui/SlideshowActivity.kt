package com.pzarubin.tvslideshow.ui

import android.app.PendingIntent
import android.content.Intent
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
import com.pzarubin.tvslideshow.data.auth.TokenStore
import com.pzarubin.tvslideshow.data.cache.SlideCache
import com.pzarubin.tvslideshow.data.source.LocalFolderSource
import com.pzarubin.tvslideshow.data.source.MediaItem
import com.pzarubin.tvslideshow.data.source.MediaSource
import com.pzarubin.tvslideshow.data.source.YandexClient
import com.pzarubin.tvslideshow.data.source.YandexDiskSource
import com.pzarubin.tvslideshow.domain.SlideMode
import com.pzarubin.tvslideshow.domain.SlideshowEngine
import com.pzarubin.tvslideshow.playback.BitmapLoader
import com.pzarubin.tvslideshow.playback.SlideshowView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var currentItem: MediaItem? = null

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

        val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: SOURCE_LOCAL
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
        } else {
            @Suppress("DEPRECATION")
            val treeUri = intent.getParcelableExtra<Uri>(EXTRA_TREE_URI)
                ?: run { finish(); return }
            source = LocalFolderSource(this, treeUri)
        }

        mode = SlideMode.valueOf(
            intent.getStringExtra(EXTRA_MODE) ?: SlideMode.KEN_BURNS.name
        )

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

        mediaSession = MediaSession.Builder(this, slideshowView.player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, SlideshowActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()

        startOsdTicker()

        lifecycleScope.launch {
            val items = source.list()
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
        val engine = SlideshowEngine(items, lifecycleScope) { mode.photoDurationMs }
        this.engine = engine
        engine.onSlide = { showSlide(it) }
        engine.onStateChanged = { onEngineStateChanged(it) }
        engine.start()
    }

    private fun showSlide(item: MediaItem) {
        currentItem = item
        showOsd()
        when (item.kind) {
            MediaItem.Kind.PHOTO -> {
                val gen = ++generation
                val targetW = resources.displayMetrics.widthPixels
                val targetH = resources.displayMetrics.heightPixels
                val duration = mode.photoDurationMs
                lifecycleScope.launch(Dispatchers.IO) {
                    val uri = source.open(item)
                    val bmp = if (uri != null) {
                        BitmapLoader.decode(contentResolver, uri, targetW, targetH)
                    } else {
                        null
                    }
                    if (gen == generation && bmp != null) {
                        withContext(Dispatchers.Main) {
                            slideshowView.showPhoto(bmp, duration)
                        }
                    } else {
                        bmp?.recycle()
                    }
                }
            }
            MediaItem.Kind.VIDEO -> {
                lifecycleScope.launch(Dispatchers.IO) {
                    val uri = source.open(item)
                    withContext(Dispatchers.Main) {
                        if (uri != null) slideshowView.showVideo(uri, item.name)
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
            KeyEvent.KEYCODE_MEDIA_REWIND -> { slideshowView.seekVideoBy(-SEEK_STEP_MS); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { slideshowView.seekVideoBy(SEEK_STEP_MS); true }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // --- OSD ---

    private fun showOsd() {
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
        engine?.release()
        mediaSession?.release()
        mediaSession = null
        if (::slideshowView.isInitialized) slideshowView.stop()
    }

    companion object {
        const val EXTRA_TREE_URI = "extra_tree_uri"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val EXTRA_YANDEX_PATH = "extra_yandex_path"
        const val SOURCE_LOCAL = "local"
        const val SOURCE_YANDEX = "yandex"
        const val SEEK_STEP_MS = 10_000L
        const val OSD_TIMEOUT_MS = 3000L
    }
}
