package de.singular.player.media

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import de.singular.player.storage.MixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Where playback actually lives, and what a car head unit talks to.
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
 * ## Why the runs live here and not in the ViewModel
 *
 * A head unit binds to this service and never starts the Activity, so there may be no ViewModel at
 * all when the car asks for a folder. Anything the car can reach has to be reachable from here:
 * the browse tree in [Library], and the three ways to start a run of mixes below.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null

    private val jobs: Job = SupervisorJob()

    /**
     * Main-threaded on purpose. ExoPlayer may only be touched from the thread it was built on, and
     * these blocks do touch it; every storage call inside them switches to IO on its own, so
     * nothing slow actually runs here. See [MixStore].
     */
    private val scope = CoroutineScope(Dispatchers.Main + jobs)

    private lateinit var library: Library

    override fun onCreate() {
        super.onCreate()
        library = Library(this, MixStore(this))
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
        session = MediaLibrarySession.Builder(this, exo, Callback()).build()
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
        val playing = player?.playWhenReady == true && player?.playbackState != Player.STATE_IDLE
        if (!playing) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(library.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(ImmutableList.copyOf(library.children(parentId)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            val item = library.item(mediaId)
            if (item == null) {
                LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItem(item, null)
            }
        }

        /**
         * Turn a pressed row into something the player can actually play.
         *
         * A browser hands back the row's media id and nothing else — no uri, and for the three
         * action rows no single mix either. This is where an id becomes a run: one mix for a mix,
         * a whole folder for "play this folder", the lot for "shuffle everything".
         *
         * An id that resolves to nothing returns the items unchanged rather than an empty list, so
         * a stale row stops whatever is playing no more than a mistyped one would.
         */
        override fun onSetMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val id = mediaItems.firstOrNull()?.mediaId
            val run = id?.let { library.playbackFor(it) }.orEmpty()
            if (run.isEmpty()) {
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            } else {
                // Repeat-one is the phone's hold-the-transport gesture, for sitting with a take.
                // Inheriting it here would strand a run on its first mix, which from the driver's
                // seat is indistinguishable from a shuffle that does not work.
                player?.repeatMode = Player.REPEAT_MODE_OFF
                MediaSession.MediaItemsWithStartPosition(
                    run.map { MediaItem.fromUri(it.uri) },
                    0,
                    C.TIME_UNSET,
                )
            }
        }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.flatMap { item ->
                if (item.localConfiguration != null) {
                    listOf(item)
                } else {
                    library.playbackFor(item.mediaId).map { MediaItem.fromUri(it.uri) }
                }
            }.toMutableList()
        }
    }

    /**
     * Run [block] off the caller's thread and hand back the future the session API wants.
     *
     * Every browse answer here touches the document provider, which is a round trip that must not
     * happen on the main thread — and the car is waiting, so it must not take long either. See the
     * limits in [Library].
     */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val settable = SettableFuture.create<T>()
        scope.launch {
            runCatching { block() }
                .onSuccess { settable.set(it) }
                .onFailure { settable.setException(it) }
        }
        return settable
    }
}
