package com.pzarubin.tvslideshow.playback

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Кастомная View с эффектом «Кен Бёрнс»: изображение заполняет экран
 * (CENTER_CROP) и медленно масштабируется + сдвигается (pan) в случайном
 * направлении. Направление зума и сдвига выбирается случайно для разнообразия.
 */
class KenBurnsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val matrix = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var animator: ValueAnimator? = null

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    fun setImageBitmap(bmp: Bitmap?) {
        cancel()
        bitmap = bmp
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    fun startKenBurns(durationMs: Long) {
        cancel()
        val bmp = bitmap ?: return
        if (bmp.isRecycled || durationMs <= 0L) return

        val zoomIn = Random.nextBoolean()
        val dirX = if (Random.nextBoolean()) 1f else -1f
        val dirY = if (Random.nextBoolean()) 1f else -1f
        val start = if (zoomIn) 1f else KEN_BURNS_SCALE
        val end = if (zoomIn) KEN_BURNS_SCALE else 1f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                zoom = start + (end - start) * t
                panX = dirX * PAN_FRACTION * width * t
                panY = dirY * PAN_FRACTION * height * t
                invalidate()
            }
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        // Близкие пропорции — заполняем экран (CENTER_CROP); сильно отличающиеся
        // (вертикальные/квадратные/панорамы) — показываем целиком (letterbox).
        val base = if (SlideFit.useLetterbox(bmp.width, bmp.height, width, height)) {
            min(vw / bw, vh / bh)
        } else {
            max(vw / bw, vh / bh)
        }
        val scale = base * zoom

        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            (vw - bw * scale) / 2f + panX,
            (vh - bh * scale) / 2f + panY
        )
        canvas.drawBitmap(bmp, matrix, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancel()
    }

    companion object {
        /** Максимальный зум (1.12x даёт ~6% запаса по краям). */
        const val KEN_BURNS_SCALE = 1.12f

        /** Доля панорамирования от размера View (в пределах запаса зума). */
        const val PAN_FRACTION = 0.04f
    }
}
