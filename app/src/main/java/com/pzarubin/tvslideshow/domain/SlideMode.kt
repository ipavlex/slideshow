package com.pzarubin.tvslideshow.domain

/**
 * Режим показа слайдшоу.
 * [photoDurationMs] — длительность показа одного фото в этом режиме.
 */
enum class SlideMode(val photoDurationMs: Long) {
    KEN_BURNS(7000L),
    CLASSIC(5000L)
}
