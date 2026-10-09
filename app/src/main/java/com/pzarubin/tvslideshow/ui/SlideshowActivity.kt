package com.pzarubin.tvslideshow.ui

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.source.LocalFolderSource
import com.pzarubin.tvslideshow.data.source.MediaItem
import com.pzarubin.tvslideshow.domain.SlideMode
import com.pzarubin.tvslideshow.domain.SlideshowEngine
import com.pzarubin.tvslideshow.playback.BitmapLoader
import com.pzarubin.tvslideshow.playback.SlideshowView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Полноэкранное слайдшоу. Получает URI дерева (SAF) и режим показа,
 * строит плейлист и запускает движок.
 */
class SlideshowActivity : AppCompatActivity() {

    private lateinit var slideshowView: SlideshowView
    private var engine: SlideshowEngine? = null
    private var generation = 0
    private lateinit var mode: SlideMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val treeUri = intent.getParcelableExtra<Uri>(EXTRA_TREE_URI)
            ?: run { finish(); return }
        mode = SlideMode.valueOf(
            intent.getStringExtra(EXTRA_MODE) ?: SlideMode.KEN_BURNS.name
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        slideshowView = SlideshowView(this, null, mode)
        setContentView(slideshowView)

        lifecycleScope.launch {
            val items = LocalFolderSource(this@SlideshowActivity, treeUri).list()
            if (items.isEmpty()) {
                Toast.makeText(
                    this@SlideshowActivity,
                    R.string.no_images_found,
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            startSlideshow(items)
        }
    }

    private fun startSlideshow(items: List<MediaItem>) {
        val engine = SlideshowEngine(items, lifecycleScope) { item ->
            // TODO(Фаза 2): для видео — реальная длительность ролика.
            if (item.kind == MediaItem.Kind.VIDEO) 0L else mode.photoDurationMs
        }
        this.engine = engine
        engine.onSlide = { showSlide(it) }
        engine.start()
    }

    private fun showSlide(item: MediaItem) {
        val uri = item.uri ?: return
        val gen = ++generation
        val targetW = resources.displayMetrics.widthPixels
        val targetH = resources.displayMetrics.heightPixels
        val duration = mode.photoDurationMs

        lifecycleScope.launch(Dispatchers.IO) {
            val bmp = BitmapLoader.decode(contentResolver, uri, targetW, targetH)
            if (gen == generation && bmp != null) {
                withContext(Dispatchers.Main) {
                    slideshowView.showSlide(bmp, duration)
                }
            } else {
                bmp?.recycle()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = engine ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { e.previous(); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { e.next(); true }
            KeyEvent.KEYCODE_DPAD_UP -> { e.speedUp(); showSpeedToast(e); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { e.speedDown(); showSpeedToast(e); true }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { e.toggle(); true }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun showSpeedToast(e: SlideshowEngine) {
        Toast.makeText(
            this,
            getString(R.string.speed_value, e.speedMultiplier),
            Toast.LENGTH_SHORT
        ).show()
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
        engine?.release()
        if (::slideshowView.isInitialized) slideshowView.stop()
    }

    companion object {
        const val EXTRA_TREE_URI = "extra_tree_uri"
        const val EXTRA_MODE = "extra_mode"
    }
}
