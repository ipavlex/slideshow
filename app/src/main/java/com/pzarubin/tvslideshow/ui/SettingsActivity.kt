package com.pzarubin.tvslideshow.ui

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.domain.PlaylistBuilder

/**
 * Экран настроек слайдшоу: порядок, перемешивание, длительность фото,
 * рекурсивный обход подпапок. Значения переключаются кликом (OK на пульте).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private val durations = listOf(0L, 3000L, 5000L, 7000L, 10000L, 15000L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = SettingsStore(this)

        val btnOrder = findViewById<Button>(R.id.setting_order)
        val btnShuffle = findViewById<Button>(R.id.setting_shuffle)
        val btnDuration = findViewById<Button>(R.id.setting_duration)
        val btnRecursive = findViewById<Button>(R.id.setting_recursive)

        btnOrder.setOnClickListener {
            settings.order = when (settings.order) {
                PlaylistBuilder.Order.NAME -> PlaylistBuilder.Order.DATE
                PlaylistBuilder.Order.DATE -> PlaylistBuilder.Order.RANDOM
                PlaylistBuilder.Order.RANDOM -> PlaylistBuilder.Order.NAME
            }
            updateOrder(btnOrder)
        }
        btnShuffle.setOnClickListener {
            settings.shuffle = !settings.shuffle
            updateShuffle(btnShuffle)
        }
        btnDuration.setOnClickListener {
            val idx = durations.indexOfFirst { it == settings.photoDurationMs }
                .let { if (it < 0) 0 else it }
            settings.photoDurationMs = durations[(idx + 1) % durations.size]
            updateDuration(btnDuration)
        }
        btnRecursive.setOnClickListener {
            settings.recursive = !settings.recursive
            updateRecursive(btnRecursive)
        }

        updateOrder(btnOrder)
        updateShuffle(btnShuffle)
        updateDuration(btnDuration)
        updateRecursive(btnRecursive)
    }

    private fun updateOrder(btn: Button) {
        val label = when (settings.order) {
            PlaylistBuilder.Order.NAME -> getString(R.string.order_name)
            PlaylistBuilder.Order.DATE -> getString(R.string.order_date)
            PlaylistBuilder.Order.RANDOM -> getString(R.string.order_random)
        }
        btn.text = getString(R.string.setting_order, label)
    }

    private fun updateShuffle(btn: Button) {
        btn.text = getString(R.string.setting_shuffle, onOff(settings.shuffle))
    }

    private fun updateDuration(btn: Button) {
        val label = if (settings.photoDurationMs == 0L) {
            getString(R.string.duration_auto)
        } else {
            getString(R.string.duration_seconds, settings.photoDurationMs / 1000L)
        }
        btn.text = getString(R.string.setting_duration, label)
    }

    private fun updateRecursive(btn: Button) {
        btn.text = getString(R.string.setting_recursive, onOff(settings.recursive))
    }

    private fun onOff(v: Boolean): String =
        getString(if (v) R.string.setting_on else R.string.setting_off)
}
