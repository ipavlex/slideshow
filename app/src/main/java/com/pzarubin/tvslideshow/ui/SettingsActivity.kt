package com.pzarubin.tvslideshow.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pzarubin.tvslideshow.R
import com.pzarubin.tvslideshow.data.settings.SettingsStore
import com.pzarubin.tvslideshow.domain.PlaylistBuilder
import com.pzarubin.tvslideshow.domain.SlideMode
import kotlin.math.abs

/**
 * Экран настроек (DESIGN.md §4 «07 Настройки», прототип 07): вертикальный
 * список строк-карточек «название слева — контрол справа».
 *
 * Управление с пульта: ↑/↓ — фокус между строками (стандартная навигация),
 * ←/→ в фокусной строке — изменение значения контрола (сегмент, степпер,
 * тумблер), клик (ОК) по строке с тумблером — переключает его.
 * Значения сохраняются в SettingsStore сразу при изменении.
 *
 * Строки: анимация (сегмент Кен Бёрнс/Классика), скорость слайда (степпер
 * Авто/3–15 сек), зациклить, сортировка (Имя/Дата изменения), случайный порядок,
 * имя файла на экране (OSD), автозапуск последней папки, вложенные папки.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore

    /** Шаги «Скорости слайда», сек; 0 — «Авто» (режим по умолчанию). */
    private val durationSteps = listOf(0L, 3L, 5L, 7L, 10L, 15L)

    // Контролы строк.
    private lateinit var segModeKb: TextView
    private lateinit var segModeClassic: TextView
    private lateinit var tvDuration: TextView
    private lateinit var swLoop: Switch
    private lateinit var segSortName: TextView
    private lateinit var segSortDate: TextView
    private lateinit var swShuffle: Switch
    private lateinit var swFilename: Switch
    private lateinit var swAutostart: Switch
    private lateinit var swRecursive: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = SettingsStore(this)

        // Миграция: старый порядок RANDOM теперь задаётся тумблером
        // «Случайный порядок»; сегмент сортировки работает только с Имя/Дата.
        if (settings.order == PlaylistBuilder.Order.RANDOM) {
            settings.shuffle = true
            settings.order = PlaylistBuilder.Order.NAME
        }

        val rowAnimation = findViewById<View>(R.id.row_animation)
        val rowSpeed = findViewById<View>(R.id.row_speed)
        val rowLoop = findViewById<View>(R.id.row_loop)
        val rowSort = findViewById<View>(R.id.row_sort)
        val rowShuffle = findViewById<View>(R.id.row_shuffle)
        val rowFilename = findViewById<View>(R.id.row_filename)
        val rowAutostart = findViewById<View>(R.id.row_autostart)
        val rowRecursive = findViewById<View>(R.id.row_recursive)

        segModeKb = findViewById(R.id.setting_mode_ken_burns)
        segModeClassic = findViewById(R.id.setting_mode_classic)
        tvDuration = findViewById(R.id.setting_duration)
        swLoop = findViewById(R.id.setting_loop)
        segSortName = findViewById(R.id.setting_sort_name)
        segSortDate = findViewById(R.id.setting_sort_date)
        swShuffle = findViewById(R.id.setting_shuffle)
        swFilename = findViewById(R.id.setting_filename)
        swAutostart = findViewById(R.id.setting_autostart)
        swRecursive = findViewById(R.id.setting_recursive)

        val btnMinus = findViewById<Button>(R.id.setting_duration_minus)
        val btnPlus = findViewById<Button>(R.id.setting_duration_plus)
        // Одиночные символы «−»/«+» — строк-токенов в strings.xml нет.
        btnMinus.text = MINUS
        btnPlus.text = PLUS
        btnMinus.setOnClickListener { stepDuration(-1) }
        btnPlus.setOnClickListener { stepDuration(1) }

        // Клик (ОК) по строке с тумблером — переключает его.
        rowLoop.setOnClickListener { swLoop.isChecked = !swLoop.isChecked }
        rowShuffle.setOnClickListener { swShuffle.isChecked = !swShuffle.isChecked }
        rowFilename.setOnClickListener { swFilename.isChecked = !swFilename.isChecked }
        rowAutostart.setOnClickListener { swAutostart.isChecked = !swAutostart.isChecked }
        rowRecursive.setOnClickListener { swRecursive.isChecked = !swRecursive.isChecked }

        swLoop.isChecked = settings.loop
        swLoop.setOnCheckedChangeListener { _, v -> settings.loop = v }
        swShuffle.isChecked = settings.shuffle
        swShuffle.setOnCheckedChangeListener { _, v -> settings.shuffle = v }
        // «Имя файла на экране» — та же OSD-настройка (showOsd), новый id строки.
        swFilename.isChecked = settings.showOsd
        swFilename.setOnCheckedChangeListener { _, v -> settings.showOsd = v }
        swAutostart.isChecked = settings.autostart
        swAutostart.setOnCheckedChangeListener { _, v -> settings.autostart = v }
        swRecursive.isChecked = settings.recursive
        swRecursive.setOnCheckedChangeListener { _, v -> settings.recursive = v }

        refreshAll()

        // Фокус по умолчанию — первая строка (data-focus-default в прототипе).
        rowAnimation.post { rowAnimation.requestFocus() }
    }

    /** ←/→ в фокусной строке меняют значение её контрола (прототип 07). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return super.onKeyDown(keyCode, event)
        }
        if (applyHorizontalStep(currentFocus, dir)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun applyHorizontalStep(row: View?, dir: Int): Boolean {
        return when (row?.id) {
            R.id.row_animation -> { stepMode(dir); true }
            R.id.row_speed -> { stepDuration(dir); true }
            R.id.row_sort -> { stepSort(dir); true }
            R.id.row_loop -> { swLoop.isChecked = !swLoop.isChecked; true }
            R.id.row_shuffle -> { swShuffle.isChecked = !swShuffle.isChecked; true }
            R.id.row_filename -> { swFilename.isChecked = !swFilename.isChecked; true }
            R.id.row_autostart -> { swAutostart.isChecked = !swAutostart.isChecked; true }
            R.id.row_recursive -> { swRecursive.isChecked = !swRecursive.isChecked; true }
            else -> false
        }
    }

    // --- Анимация: сегмент [Кен Бёрнс | Классика] ---

    private fun stepMode(dir: Int) {
        val idx = if (settings.mode == SlideMode.KEN_BURNS) 0 else 1
        val next = (idx + dir).coerceIn(MODE_INDICES)
        if (next == idx) return
        settings.mode = if (next == 0) SlideMode.KEN_BURNS else SlideMode.CLASSIC
        refreshMode()
    }

    // --- Скорость слайда: степпер, шаги Авто/3/5/7/10/15 сек ---

    private fun stepDuration(dir: Int) {
        // Края диапазона не закольцовываются (как в прототипе).
        val idx = (durationIndex() + dir).coerceIn(durationSteps.indices)
        settings.photoDurationMs = durationSteps[idx] * 1000L
        refreshDuration()
    }

    /** Текущий шаг степпера; прочие legacy-значения — на ближайший шаг. */
    private fun durationIndex(): Int {
        val ms = settings.photoDurationMs
        durationSteps.indexOfFirst { it * 1000L == ms }.takeIf { it >= 0 }?.let { return it }
        return durationSteps.indices.minByOrNull { abs(durationSteps[it] * 1000L - ms) }
            ?: DEFAULT_DURATION_INDEX
    }

    // --- Сортировка: сегмент [Имя | Дата изменения] ---

    private fun stepSort(dir: Int) {
        val idx = if (settings.order == PlaylistBuilder.Order.DATE) 1 else 0
        val next = (idx + dir).coerceIn(MODE_INDICES)
        if (next == idx) return
        settings.order = if (next == 0) PlaylistBuilder.Order.NAME else PlaylistBuilder.Order.DATE
        refreshSort()
    }

    // --- Отрисовка контролов ---

    private fun refreshAll() {
        refreshMode()
        refreshDuration()
        refreshSort()
    }

    private fun refreshMode() {
        val kb = settings.mode == SlideMode.KEN_BURNS
        bindSegment(segModeKb, kb)
        bindSegment(segModeClassic, !kb)
    }

    private fun refreshSort() {
        val byName = settings.order != PlaylistBuilder.Order.DATE
        bindSegment(segSortName, byName)
        bindSegment(segSortDate, !byName)
    }

    /** Активный сегмент: заливка акцентом, белый жирный текст (стиль из css). */
    private fun bindSegment(item: TextView, active: Boolean) {
        item.setBackgroundResource(
            if (active) R.drawable.bg_segment_active else R.drawable.bg_segment_item
        )
        item.setTextColor(getColor(if (active) R.color.text_primary else R.color.text_secondary))
        item.setTypeface(item.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun refreshDuration() {
        val step = durationSteps[durationIndex()]
        tvDuration.text = if (step == 0L) {
            getString(R.string.duration_auto)
        } else {
            getString(R.string.duration_seconds, step)
        }
    }

    private companion object {
        /** Fallback для legacy-значений — 10 сек. */
        const val DEFAULT_DURATION_INDEX = 4
        val MODE_INDICES = 0..1
        const val MINUS = "−"
        const val PLUS = "+"
    }
}
