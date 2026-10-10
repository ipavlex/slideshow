package com.pzarubin.tvslideshow.domain

import com.pzarubin.tvslideshow.data.source.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Машина состояний слайдшоу: таймер, перемотка, скорость, цикл.
 *
 * Для фото длительность вычисляется через [durationResolver] и делится на
 * множитель скорости. Видео проигрывается до конца и продвигает слайдшоу
 * через [notifyCurrentFinished].
 *
 * Таймер фото-слайда стартует не при переключении, а после фактического показа
 * ([notifyPhotoShown]): декодирование (особенно HEIC) может длиться дольше
 * длительности слайда — иначе слайд пропускался бы, не успев показаться.
 * Активность вызывает [beginPhotoLoad] перед декодированием.
 */
class SlideshowEngine(
    private val items: List<MediaItem>,
    private val scope: CoroutineScope,
    private val durationResolver: (MediaItem) -> Long
) {

    enum class State { IDLE, PLAYING, PAUSED }

    var state = State.IDLE
        private set

    var speedMultiplier = 1f
        private set

    private var index = 0
    private var tickJob: Job? = null
    private var tickStartedAt = 0L
    private var tickDurationMs = 0L

    /** Идёт декодирование текущего фото — таймер ждёт [notifyPhotoShown]. */
    private var loadingPhoto = false

    var onSlide: ((MediaItem) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    /** Скорость изменилась — активность применяет её к видео (playbackSpeed). */
    var onSpeedChanged: ((Float) -> Unit)? = null

    val currentItem: MediaItem get() = items[index]
    val hasItems: Boolean get() = items.isNotEmpty()
    val currentIndex: Int get() = index
    val count: Int get() = items.size

    fun start() {
        if (items.isEmpty()) return
        index = 0
        setState(State.PLAYING)
        onSlide?.invoke(currentItem)
        // Фото: таймер стартует после показа; видео: ждём завершения playback.
    }

    fun pause() {
        if (state != State.PLAYING) return
        setState(State.PAUSED)
        tickJob?.cancel()
    }

    fun resume() {
        if (state != State.PAUSED) return
        setState(State.PLAYING)
        if (currentItem.kind == MediaItem.Kind.PHOTO && loadingPhoto) {
            return // таймер придёт из notifyPhotoShown после декодирования
        }
        scheduleNext()
    }

    fun toggle() {
        if (state == State.PLAYING) pause() else resume()
    }

    fun next() = moveBy(1)

    fun previous() = moveBy(-1)

    fun speedUp() = stepSpeed(+1)

    fun speedDown() = stepSpeed(-1)

    /**
     * Вызывается извне по завершении текущего видео (или ошибке воспроизведения),
     * чтобы перейти к следующему слайду.
     */
    fun notifyCurrentFinished() {
        if (state != State.PLAYING) return
        if (currentItem.kind != MediaItem.Kind.VIDEO) return
        moveBy(1)
    }

    /**
     * Активность начинает декодирование текущего фото: таймер замораживается
     * до фактического показа ([notifyPhotoShown]) или перехода к следующему.
     */
    fun beginPhotoLoad() {
        if (currentItem.kind != MediaItem.Kind.PHOTO) return
        loadingPhoto = true
        tickJob?.cancel()
        tickJob = null
    }

    /** Фото показано на экране — теперь можно отсчитывать его длительность. */
    fun notifyPhotoShown() {
        if (currentItem.kind != MediaItem.Kind.PHOTO) return
        loadingPhoto = false
        if (state == State.PLAYING) scheduleNext()
    }

    fun release() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun moveBy(delta: Int) {
        if (items.isEmpty()) return
        index = (index + delta + items.size) % items.size
        onSlide?.invoke(currentItem)
        // Фото: таймер стартует после показа (notifyPhotoShown); видео: ждём завершения.
        if (state == State.PLAYING && currentItem.kind == MediaItem.Kind.VIDEO) scheduleNext()
    }

    private fun scheduleNext() {
        val item = currentItem
        if (item.kind == MediaItem.Kind.VIDEO) return  // видео: ждём завершения
        val durationMs = durationResolver(item) / speedMultiplier
        startTick(durationMs.toLong().coerceAtLeast(MIN_TICK_MS))
    }

    /** Запускает таймер перехода на [delayMs] и запоминает момент старта. */
    private fun startTick(delayMs: Long) {
        tickJob?.cancel()
        tickDurationMs = delayMs
        tickStartedAt = android.os.SystemClock.elapsedRealtime()
        tickJob = scope.launch {
            delay(delayMs)
            moveBy(1)
        }
    }

    private fun stepSpeed(delta: Int) {
        val current = SPEEDS.indexOfFirst { it == speedMultiplier }
            .let { if (it < 0) SPEEDS.indexOf(1f) else it }
        val next = SPEEDS[(current + delta + SPEEDS.size) % SPEEDS.size]
        setSpeed(next)
    }

    private fun setSpeed(value: Float) {
        val old = speedMultiplier
        speedMultiplier = value
        onSpeedChanged?.invoke(value)
        // Во время загрузки фото таймера нет — новая скорость будет учтена
        // в notifyPhotoShown; у видео таймера нет.
        if (state != State.PLAYING || loadingPhoto ||
            currentItem.kind != MediaItem.Kind.PHOTO || tickJob == null
        ) return
        // Пересчитываем ОСТАТОК времени показа под новую скорость, а не
        // перезапускаем полную длительность: иначе «быстрее» посреди слайда
        // внезапно продлевало его показ.
        val elapsed = android.os.SystemClock.elapsedRealtime() - tickStartedAt
        val remaining = (tickDurationMs - elapsed).coerceAtLeast(0L)
        val newDelay = (remaining * old / value).toLong().coerceAtLeast(MIN_TICK_MS)
        startTick(newDelay)
    }

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    private companion object {
        val SPEEDS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)
        const val MIN_TICK_MS = 250L
    }
}
