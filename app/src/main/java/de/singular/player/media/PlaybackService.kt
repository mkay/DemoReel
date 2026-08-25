package de.singular.player.media

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/**
 * Where playback actually lives.
 *
 * The player used to be a field on [de.singular.player.PlayerViewModel], which tied a mix to the
 * Activity that started it: dismissing the app from recents cut the audio off mid-mix, and with no
 * service in front of it the process was a candidate for being killed while playing. It also meant
 * the app took no part in the conventions every other audio app on the device follows — it never
 * asked for audio focus, so it played over the top of whatever was already going and carried on
 * through a phone call; it never heard the noisy broadcast, so pulling the headphones switched to
 * the speaker; and with no session there was nothing for the lock screen, a headset button or a
 * steering wheel to talk to.
 *
 * All of that is one fix, and this is it. The session is the app's public face for playback, and
 * the UI is now a client of it rather than its owner.
 *
 * A [MediaLibraryService] rather than a plain `MediaSessionService`, which is a superset: it is
 * what a car head unit binds to and browses. The browse tree itself is not implemented yet — the
 * callback below is the default one — so today this behaves exactly as the simpler service would.
 * Filling in the tree is what turns this into Android Auto support.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            // handleAudioFocus, the second argument, is the whole point: it makes the player
            // request focus before it sounds, duck for a navigation prompt, pause for a call, and
            // give way when another app takes over — none of which the old MediaPlayer did.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            // Pause when the headphones come out, rather than playing a mix out loud to the room.
            .setHandleAudioBecomingNoisy(true)
            // Hold a wake lock while playing. Settings documents that "a mix is listened to with
            // the screen off as often as not", which is precisely the case where the CPU would
            // otherwise sleep out from under playback.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exo
        session = MediaLibrarySession.Builder(this, exo, object : MediaLibrarySession.Callback {})
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    /**
     * Dismissing the app from recents ends playback only if nothing is playing.
     *
     * Deliberately not the default `stopSelf`: a mix playing with the screen off and the app swiped
     * away is the ordinary way this app is used, and cutting it off there was the original defect.
     * A paused session has nothing to keep the service up for, so that one goes.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = player?.playWhenReady == true && player?.playbackState != androidx.media3.common.Player.STATE_IDLE
        if (!playing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
