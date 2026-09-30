package app.opencodesentry

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * The single MediaPlayer used for alerts, kept in one place so playback can be
 * stopped from anywhere: the notification's "停止响铃" action, the settings
 * screen, or the app coming to the foreground.
 *
 * A hard time cap is always applied as well, so a long alarm ringtone can
 * never keep playing just because nobody acknowledged the notification.
 */
object AlertPlayer {

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var capRunnable: Runnable? = null

    val isPlaying: Boolean get() = player != null

    /**
     * Plays [uris] back to back with the given linear gain.
     *
     * @param maxMillis total playback budget; 0 disables the cap.
     */
    fun play(context: Context, uris: List<Uri>, gain: Float, maxMillis: Long) {
        stop()
        if (uris.isEmpty()) return

        val appContext = context.applicationContext
        val iterator = uris.iterator()

        fun advance() {
            if (!iterator.hasNext()) {
                clearCap()
                return
            }
            val uri = iterator.next()
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK)
                mp.setDataSource(appContext, uri)
                mp.setVolume(gain, gain)
                mp.setOnCompletionListener { finished ->
                    finished.release()
                    if (player === finished) player = null
                    advance()
                }
                mp.setOnErrorListener { failed, _, _ ->
                    failed.release()
                    if (player === failed) player = null
                    advance()
                    true
                }
                mp.prepare()
                player = mp
                mp.start()
            } catch (_: Exception) {
                // Missing or unreadable URI: fall through to the next clip.
                advance()
            }
        }

        advance()

        if (maxMillis > 0) {
            val cap = Runnable {
                Logx.i("alert hit the ${maxMillis}ms cap; stopping playback")
                stop()
            }
            capRunnable = cap
            handler.postDelayed(cap, maxMillis)
        }
    }

    fun stop() {
        clearCap()
        val current = player ?: return
        player = null
        runCatching { if (current.isPlaying) current.stop() }
        runCatching { current.release() }
    }

    private fun clearCap() {
        capRunnable?.let { handler.removeCallbacks(it) }
        capRunnable = null
    }
}
