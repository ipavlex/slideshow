package com.pzarubin.tvslideshow.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pzarubin.tvslideshow.domain.SlideMode

/**
 * Поверхность слайдшоу: фото (кроссфейд двух слоёв) и видео (ExoPlayer).
 *
 * - [showPhoto] — фото с эффектом режима (Ken Burns / Classic) и кроссфейдом.
 * - [showVideo] — видео через один переиспользуемый ExoPlayer; по завершении
 *   вызывается [onVideoEnded], чтобы движок перешёл к следующему слайду.
 *
 * Плеер ([player]) создаётся сразу и доступен снаружи (для MediaSession).
 */
class SlideshowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val mode: SlideMode = SlideMode.CLASSIC
) : FrameLayout(context, attrs) {

    private val photoLayers = arrayOf(createPhotoLayer(), createPhotoLayer())
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    private var frontIndex = 0
    private var seq = 0

    val player: ExoPlayer
    private val playerView: PlayerView

    var onVideoEnded: (() -> Unit)? = null

    init {
        photoLayers.forEach { addView(it, LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        photoLayers[1].alpha = 0f

        playerView = PlayerView(context).apply {
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            visibility = GONE
        }
        addView(playerView)

        player = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> playerView.visibility = VISIBLE
                        Player.STATE_ENDED -> onVideoFinished()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    onVideoFinished()
                }
            })
        }
        playerView.player = player
    }

    private fun createPhotoLayer(): View = when (mode) {
        SlideMode.CLASSIC -> ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        SlideMode.KEN_BURNS -> KenBurnsView(context)
    }

    fun showPhoto(bitmap: Bitmap, durationMs: Long) {
        stopVideo()
        val mySeq = ++seq
        photoLayers.forEach { it.animate().cancel() }

        val incomingIdx = 1 - frontIndex
        val outgoingIdx = frontIndex
        val incoming = photoLayers[incomingIdx]
        val outgoing = photoLayers[outgoingIdx]

        // На время кроссфейда замораживаем уходящий слой: его Ken Burns
        // перестаёт инвалидировать кадр, и fade композитится дёшево —
        // каждый кадр перерисовывается только входящий слой.
        (outgoing as? KenBurnsView)?.cancel()

        // Сначала снимаем bitmap со слоя (сбрасывает шейдер в KenBurnsView),
        // и только потом освобождаем память — иначе слой мог бы отрисовать
        // уже освобождённый bitmap.
        clearPhotoLayer(incoming)
        bitmaps[incomingIdx]?.takeIf { !it.isRecycled }?.recycle()
        bitmaps[incomingIdx] = bitmap
        setPhotoBitmap(incoming, bitmap, durationMs)

        incoming.alpha = 0f
        incoming.bringToFront()
        incoming.animate()
            .alpha(1f)
            .setDuration(FADE_MS)
            .withEndAction {
                if (mySeq != seq) return@withEndAction
                outgoing.alpha = 0f
                clearPhotoLayer(outgoing)
                bitmaps[outgoingIdx]?.takeIf { !it.isRecycled }?.recycle()
                bitmaps[outgoingIdx] = null
                frontIndex = incomingIdx
            }
            .start()
    }

    fun showVideo(uri: Uri, title: String) {
        // Скрываем фото: при завершении видео следующий слайд появится из чёрного.
        photoLayers.forEach { it.alpha = 0f }
        playerView.bringToFront()
        playerView.visibility = GONE

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun seekVideoBy(deltaMs: Long) {
        if (player.currentMediaItem == null) return
        val newPos = (player.currentPosition + deltaMs)
            .coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(newPos)
    }

    fun pauseVideo() {
        player.playWhenReady = false
    }

    fun resumeVideo() {
        player.playWhenReady = true
    }

    private fun onVideoFinished() {
        playerView.visibility = GONE
        onVideoEnded?.invoke()
    }

    private fun stopVideo() {
        player.stop()
        playerView.visibility = GONE
    }

    private fun setPhotoBitmap(layer: View, bitmap: Bitmap, durationMs: Long) {
        when (layer) {
            is ImageView -> layer.setImageBitmap(bitmap)
            is KenBurnsView -> {
                layer.setImageBitmap(bitmap)
                layer.startKenBurns(durationMs)
            }
        }
    }

    private fun clearPhotoLayer(layer: View) {
        when (layer) {
            is ImageView -> layer.setImageBitmap(null)
            is KenBurnsView -> {
                layer.cancel()
                layer.setImageBitmap(null)
            }
        }
    }

    fun stop() {
        photoLayers.forEach { it.animate().cancel() }
        // Снимаем bitmap'ы со слоёв до recycle — слои не должны остаться
        // со ссылками на освобождённую память.
        photoLayers.forEach { clearPhotoLayer(it) }
        bitmaps.forEach { it?.takeIf { b -> !b.isRecycled }?.recycle() }
        bitmaps.fill(null)
        player.release()
    }

    companion object {
        const val FADE_MS = 600L
    }
}
