package com.pzarubin.tvslideshow.playback

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.pzarubin.tvslideshow.domain.SlideMode

/**
 * Контейнер слайдшоу: два слоя (передний/задний) с кроссфейдом между ними.
 * В режиме CLASSIC слои — обычный ImageView (fit), в KEN_BURNS — [KenBurnsView].
 *
 * [showSlide] выводит новый слайд поверх текущего и плавно проявляет его.
 */
class SlideshowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val mode: SlideMode = SlideMode.CLASSIC
) : FrameLayout(context, attrs) {

    private val layers = arrayOf(createLayer(), createLayer())
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var frontIndex = 0
    private var seq = 0

    init {
        layers.forEach { addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        layers[1].alpha = 0f
    }

    private fun createLayer(): View = when (mode) {
        SlideMode.CLASSIC -> ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        SlideMode.KEN_BURNS -> KenBurnsView(context)
    }

    fun showSlide(bitmap: Bitmap, durationMs: Long) {
        val mySeq = ++seq
        // Отменяем предыдущие фейды, чтобы устаревшие end-действия не мешали.
        layers.forEach { it.animate().cancel() }

        val incomingIdx = 1 - frontIndex
        val outgoingIdx = frontIndex
        val incoming = layers[incomingIdx]
        val outgoing = layers[outgoingIdx]

        // Переиспользуем «спрятанный» слой: освобождаем его старый битмап.
        bitmaps[incomingIdx]?.takeIf { !it.isRecycled }?.recycle()
        bitmaps[incomingIdx] = bitmap
        setLayerBitmap(incoming, bitmap, durationMs)

        // Новый слайд поверх старого: начинаем прозрачным и проявляем.
        outgoing.alpha = 1f
        incoming.alpha = 0f
        incoming.bringToFront()

        incoming.animate()
            .alpha(1f)
            .setDuration(FADE_MS)
            .withEndAction {
                if (mySeq != seq) return@withEndAction
                outgoing.alpha = 0f
                clearLayer(outgoing)
                bitmaps[outgoingIdx]?.takeIf { !it.isRecycled }?.recycle()
                bitmaps[outgoingIdx] = null
                frontIndex = incomingIdx
            }
            .start()
    }

    private fun setLayerBitmap(layer: View, bitmap: Bitmap, durationMs: Long) {
        when (layer) {
            is ImageView -> layer.setImageBitmap(bitmap)
            is KenBurnsView -> {
                layer.setImageBitmap(bitmap)
                layer.startKenBurns(durationMs)
            }
        }
    }

    private fun clearLayer(layer: View) {
        when (layer) {
            is ImageView -> layer.setImageBitmap(null)
            is KenBurnsView -> {
                layer.cancel()
                layer.setImageBitmap(null)
            }
        }
    }

    /** Останавливает анимации и освобождает все битмапы. */
    fun stop() {
        layers.forEach { it.animate().cancel() }
        (layers[frontIndex] as? KenBurnsView)?.cancel()
        bitmaps.forEach { it?.takeIf { b -> !b.isRecycled }?.recycle() }
        bitmaps.fill(null)
    }

    companion object {
        const val FADE_MS = 600L
    }
}
