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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Кастомная View с эффектом «Кен Бёрнс»: изображение заполняет экран
 * (CENTER_CROP) и медленно масштабируется + сдвигается (pan) в случайном
 * направлении. Направление зума и сдвига выбирается случайно для разнообразия.
 *
 * Отрисовка — прямой [Canvas.drawBitmap] с матрицей: вариант через
 * [BitmapShader] приводил к чёрным кадрам на ТВ-устройствах — шейдер
 * обращается к пикселям bitmap'а на RenderThread, а приложение интенсивно
 * переиспользует и recycle-ит bitmap'ы при каждой смене слайда.
 *
 * Амплитуда pan клампится по фактическому запасу каждой оси (поле в
 * letterbox-режиме или переполнение в cover-режиме), чтобы край изображения
 * никогда не пересекал границу экрана — иначе при zoom-out появляются
 * «дёргающиеся» чёрные полосы.
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
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val zoomIn = Random.nextBoolean()
        val start = if (zoomIn) 1f else KEN_BURNS_SCALE
        val end = if (zoomIn) KEN_BURNS_SCALE else 1f

        val base = baseScale(bmp, vw, vh)
        val ampX = panAmplitude(bmp.width.toFloat(), base, start, end, vw)
        val ampY = panAmplitude(bmp.height.toFloat(), base, start, end, vh)
        val dirX = if (Random.nextBoolean()) 1f else -1f
        val dirY = if (Random.nextBoolean()) 1f else -1f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                zoom = start + (end - start) * t
                panX = dirX * ampX * t
                panY = dirY * ampY * t
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
        val scale = baseScale(bmp, vw, vh) * zoom

        // Целочисленное выравнивание прямоугольника отрисовки: дробные
        // координаты краёв дают субпиксельное «дрожание» кромки изображения
        // (особенно заметно на вертикальных слайдах, где кромка ходит
        // вдоль экрана при зуме). Размер и сдвиг округляем до пикселя.
        val drawW = (bw * scale).roundToInt().coerceAtLeast(1)
        val drawH = (bh * scale).roundToInt().coerceAtLeast(1)
        val left = ((vw - drawW) / 2f + panX).roundToInt()
        val top = ((vh - drawH) / 2f + panY).roundToInt()

        matrix.reset()
        matrix.setScale(drawW / bw, drawH / bh)
        matrix.postTranslate(left.toFloat(), top.toFloat())
        canvas.drawBitmap(bmp, matrix, paint)
    }

    /** Базовый масштаб показа: cover или letterbox — см. [SlideFit]. */
    private fun baseScale(bmp: Bitmap, vw: Float, vh: Float): Float {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        return if (SlideFit.useLetterbox(bmp.width, bmp.height, width, height)) {
            min(vw / bw, vh / bh)
        } else {
            max(vw / bw, vh / bh)
        }
    }

    /**
     * Максимальная амплитуда pan по одной оси: на протяжении всей анимации
     * |pan·t| не должен превышать запас |dim·base·zoom(t) − viewDim| / 2 —
     * иначе край изображения пересечёт границу экрана. Запас нелинейный,
     * поэтому берём минимум по выборке моментов времени.
     */
    private fun panAmplitude(
        dim: Float,
        base: Float,
        start: Float,
        end: Float,
        viewDim: Float
    ): Float {
        var bound = Float.MAX_VALUE
        for (i in 1..PAN_SAMPLES) {
            val t = i.toFloat() / PAN_SAMPLES
            val zoom = start + (end - start) * t
            // Запас с зазором в пиксель: округление отрисовки до целых
            // пикселей не должно обнажить фон у кромки.
            val slack = (abs(dim * base * zoom - viewDim) / 2f - EDGE_SAFETY_PX)
                .coerceAtLeast(0f)
            bound = min(bound, slack / t)
        }
        return (PAN_FRACTION * viewDim).coerceAtMost(bound)
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

        /** Выборка моментов времени для расчёта лимита pan. */
        private const val PAN_SAMPLES = 24

        /** Зазор между кромкой изображения и границей экрана, px. */
        private const val EDGE_SAFETY_PX = 1f
    }
}
