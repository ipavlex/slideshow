package com.pzarubin.tvslideshow.ui

import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.domain.PlaylistBuilder
import com.pzarubin.tvslideshow.domain.SlideMode

/**
 * Экран настроек слайдшоу: порядок, перемешивание, длительность фото,
 * рекурсивный обход подпапок. Порядок/режим/длительность переключаются кликом
 * (OK на пульте), вложенные папки и перемешивание — тумблерами.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private val durations = listOf(0L, 3000L, 5000L, 7000L, 10000L, 15000L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = SettingsStore(this)

        val btnMode = findViewById<Button>(R.id.setting_mode)
        val btnOrder = findViewById<Button>(R.id.setting_order)
        val btnDuration = findViewById<Button>(R.id.setting_duration)
        val swRecursive = findViewById<Switch>(R.id.setting_recursive)
        val swShuffle = findViewById<Switch>(R.id.setting_shuffle)
        val swOsd = findViewById<Switch>(R.id.setting_osd)

        btnMode.setOnClickListener {
            settings.mode = if (settings.mode == SlideMode.KEN_BURNS) {
                SlideMode.CLASSIC
            } else {
                SlideMode.KEN_BURNS
            }
            updateMode(btnMode)
        }
        btnOrder.setOnClickListener {
            settings.order = when (settings.order) {
                PlaylistBuilder.Order.NAME -> PlaylistBuilder.Order.DATE
                PlaylistBuilder.Order.DATE -> PlaylistBuilder.Order.RANDOM
                PlaylistBuilder.Order.RANDOM -> PlaylistBuilder.Order.NAME
            }
            updateOrder(btnOrder)
        }
        btnDuration.setOnClickListener {
            val idx = durations.indexOfFirst { it == settings.photoDurationMs }
                .let { if (it < 0) 0 else it }
            settings.photoDurationMs = durations[(idx + 1) % durations.size]
            updateDuration(btnDuration)
        }

        swRecursive.isChecked = settings.recursive
        swRecursive.setOnCheckedChangeListener { _, checked -> settings.recursive = checked }
        swShuffle.isChecked = settings.shuffle
        swShuffle.setOnCheckedChangeListener { _, checked -> settings.shuffle = checked }
        swOsd.isChecked = settings.showOsd
        swOsd.setOnCheckedChangeListener { _, checked -> settings.showOsd = checked }

        updateMode(btnMode)
        updateOrder(btnOrder)
        updateDuration(btnDuration)
    }

    private fun updateMode(btn: Button) {
        val value = if (settings.mode == SlideMode.KEN_BURNS) {
            getString(R.string.choose_ken_burns)
        } else {
            getString(R.string.choose_classic)
        }
        btn.text = getString(R.string.setting_row_format, getString(R.string.setting_mode_label), value)
    }

    private fun updateOrder(btn: Button) {
        val value = when (settings.order) {
            PlaylistBuilder.Order.NAME -> getString(R.string.order_name)
            PlaylistBuilder.Order.DATE -> getString(R.string.order_date)
            PlaylistBuilder.Order.RANDOM -> getString(R.string.order_random)
        }
        btn.text = getString(R.string.setting_row_format, getString(R.string.setting_order_label), value)
    }

    private fun updateDuration(btn: Button) {
        val value = if (settings.photoDurationMs == 0L) {
            getString(R.string.duration_auto)
        } else {
            getString(R.string.duration_seconds, settings.photoDurationMs / 1000L)
        }
        btn.text = getString(R.string.setting_row_format, getString(R.string.setting_duration_label), value)
    }
}
