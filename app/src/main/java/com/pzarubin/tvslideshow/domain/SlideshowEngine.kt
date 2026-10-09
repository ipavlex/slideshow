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

    var onSlide: ((MediaItem) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    val currentItem: MediaItem get() = items[index]
    val hasItems: Boolean get() = items.isNotEmpty()

    fun start() {
        if (items.isEmpty()) return
        index = 0
        setState(State.PLAYING)
        onSlide?.invoke(currentItem)
        scheduleNext()
    }

    fun pause() {
        if (state != State.PLAYING) return
        setState(State.PAUSED)
        tickJob?.cancel()
    }

    fun resume() {
        if (state != State.PAUSED) return
        setState(State.PLAYING)
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

    fun release() {
        tickJob?.cancel()
        tickJob = null
    }

    private fun moveBy(delta: Int) {
        if (items.isEmpty()) return
        index = (index + delta + items.size) % items.size
        onSlide?.invoke(currentItem)
        if (state == State.PLAYING) scheduleNext()
    }

    private fun scheduleNext() {
        tickJob?.cancel()
        tickJob = null
        val item = currentItem
        if (item.kind == MediaItem.Kind.VIDEO) return  // видео: ждём завершения
        tickJob = scope.launch {
            val durationMs = durationResolver(item) / speedMultiplier
            delay(durationMs.toLong().coerceAtLeast(250L))
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
        speedMultiplier = value
        if (state == State.PLAYING) scheduleNext()
    }

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    private companion object {
        val SPEEDS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)
    }
}
