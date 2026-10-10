package com.pzarubin.tvslideshow.playback

/**
 * Выбор способа показа фото на экране:
 * - пропорции близки к экрану → «cover» (заполнить с обрезкой, эффектно);
 * - сильно отличаются (вертикальные, квадратные, панорамы) → «letterbox»
 *   (фото целиком с полями) — иначе CENTER_CROP слишком сильно приближает.
 */
object SlideFit {

    /** Порог несовпадения пропорций, выше которого включается letterbox. */
    const val THRESHOLD = 1.5f

    fun useLetterbox(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Boolean {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return false
        val src = srcW.toFloat() / srcH
        val dst = dstW.toFloat() / dstH
        val mismatch = if (src > dst) src / dst else dst / src
        return mismatch > THRESHOLD
    }
}
