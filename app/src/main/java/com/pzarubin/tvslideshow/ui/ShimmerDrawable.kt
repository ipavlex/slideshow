package com.pzarubin.tvslideshow.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/**
 * Заглушка «загружается» для миниатюр (прототип 04, .shimmer): по серой
 * подложке бесконечно скользит едва заметный блик, 1,6 с на проход.
 * Запускается в конструкторе; [stop] — когда превью загружено или холдер
 * ушел из переиспользования.
 */
class ShimmerDrawable : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: LinearGradient? = null
    private val matrix = Matrix()
    private var fraction = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SHIMMER_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            fraction = it.animatedValue as Float
            invalidateSelf()
        }
    }

    init {
        animator.start()
    }

    /**
     * Возобновляет анимацию (холдер переиспользован под новую загрузку).
     * Безопасно вызывать на уже работающей анимации.
     */
    fun start() {
        if (!animator.isRunning) {
            fraction = 0f
            animator.start()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        var s = shader
        if (s == null) {
            val w = b.width().toFloat()
            s = LinearGradient(
                -w, 0f, w, 0f,
                intArrayOf(Color.TRANSPARENT, HIGHLIGHT, Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            shader = s
        }
        matrix.setTranslate(((fraction * 2f) - 1f) * b.width(), 0f)
        s.setLocalMatrix(matrix)
        paint.shader = s
        canvas.drawRect(b, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Останавливает анимацию (превью загружено / холдер переиспользуется). */
    fun stop() {
        animator.cancel()
    }

    private companion object {
        const val SHIMMER_DURATION_MS = 1600L

        /** rgba(255,255,255,0.09) — блик из прототипа. */
        const val HIGHLIGHT = 0x17FFFFFF
    }
}
