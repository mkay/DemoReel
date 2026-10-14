package de.singular.player.media

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import de.singular.player.R
import de.singular.player.storage.Mix
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
        exo.addListener(TransportLog)
        player = exo
        session = MediaLibrarySession.Builder(this, exo, Callback())
            .setCustomLayout(listOf(repeatButton(exo.repeatMode)))
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

    /**
     * The repeat toggle the car draws in its playback view.
     *
     * The phone puts repeat on a long press of the transport, which a head unit has no equivalent
     * of — there is one row of buttons and no gestures. A custom layout button is how a session
     * asks for one more, and it carries the same lemniscate the player screen uses so the two
     * surfaces mean the same thing by the same mark.
     *
     * Its label says what pressing it will do rather than what is currently true, because that is
     * the question someone glancing at a car screen is actually asking.
     */
    private fun repeatButton(mode: Int): CommandButton {
        val on = mode == Player.REPEAT_MODE_ONE
        return CommandButton.Builder(
            // Media3's own repeat glyphs rather than the player screen's lemniscate. The phone can
            // afford a mark that means "repeat" in general, because the icon sits on a button whose
            // label already says what a press does. A car draws the icon and little else, so the
            // icon has to carry the state — and these two are drawn differently enough, by the head
            // unit's own hand, to tell on from off at a glance.
            if (on) CommandButton.ICON_REPEAT_ONE else CommandButton.ICON_REPEAT_OFF,
        )
            .setSessionCommand(SessionCommand(REPEAT, Bundle.EMPTY))
            .setDisplayName(getString(if (on) R.string.auto_repeat_off else R.string.auto_repeat_on))
            .build()
    }

    private fun toggleRepeat() {
        val exo = player ?: return
        // REPEAT_MODE_ONE repeats until it is turned off, which is what repeating a mix means here:
        // you put a take on to sit with it, not to hear it exactly twice.
        exo.repeatMode =
            if (exo.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF
            else Player.REPEAT_MODE_ONE
        session?.setCustomLayout(listOf(repeatButton(exo.repeatMode)))
    }

    private inner class Callback : MediaLibrarySession.Callback {

        /**
         * Grant the repeat command, on top of everything a controller gets by default.
         *
         * Without this the button is drawn and does nothing: a custom command has to be in the
         * controller's available set before the session will accept it.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    default.availableSessionCommands.buildUpon()
                        .add(SessionCommand(REPEAT, Bundle.EMPTY))
                        .add(SessionCommand(SPOKEN, Bundle.EMPTY))
                        .build(),
                )
                .setCustomLayout(listOf(repeatButton(player?.repeatMode ?: Player.REPEAT_MODE_OFF)))
                .build()
        }

        /** Who asked the player to do what — see [TransportLog] for what it then did. */
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            if (playerCommand in LoggedCommands) {
                val name = LoggedCommands[playerCommand]
                Log.d(PLAYBACK_TAG, "command $name from ${controller.packageName}")
            }
            return super.onPlayerCommandRequest(session, controller, playerCommand)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            REPEAT -> {
                toggleRepeat()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            SPOKEN -> future("spoken") {
                val query = args.getString(KEY_QUERY).orEmpty()
                Log.d(VOICE_TAG, "session: spoken command from ${controller.packageName}")
                val run = spokenRun(query, args.getBundle(KEY_EXTRAS))
                if (run.isEmpty()) {
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                } else {
                    startRun(run)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }

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
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
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
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future("set") {
            val first = mediaItems.firstOrNull()
            if (first?.isSpokenRequest == true) {
                Log.d(VOICE_TAG, "session: set, spoken, from ${controller.packageName}")
            }
            val run = when {
                first == null -> emptyList()
                first.isSpokenRequest -> spoken(session, controller, first)
                else -> library.playbackFor(first.mediaId)
            }
            if (run.isEmpty()) {
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            } else {
                runStarting()
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
        ): ListenableFuture<MutableList<MediaItem>> = future("add") {
            if (mediaItems.any { it.isSpokenRequest }) {
                Log.d(VOICE_TAG, "session: add, spoken, from ${controller.packageName}")
            }
            mediaItems.flatMap { item ->
                if (item.localConfiguration != null) return@flatMap listOf(item)
                val mixes = if (item.isSpokenRequest) {
                    spoken(session, controller, item)
                } else {
                    library.playbackFor(item.mediaId)
                }
                mixes.map { MediaItem.fromUri(it.uri) }
            }.toMutableList()
        }
    }

    /**
     * About to replace whatever is playing with a run of mixes: the session's half of starting
     * one, whether a car set the items or [startRun] is about to.
     */
    private fun runStarting() {
        // Repeat-one is the phone's hold-the-transport gesture, for sitting with a take.
        // Inheriting it here would strand a run on its first mix, which from the driver's seat is
        // indistinguishable from a shuffle that does not work.
        player?.repeatMode = Player.REPEAT_MODE_OFF
        // The button's label says what a press will do, so it has to follow the mode it was just
        // silently taken out of.
        session?.setCustomLayout(listOf(repeatButton(Player.REPEAT_MODE_OFF)))
    }

    /** Load [run] into the player and start it from its first mix. */
    private fun startRun(run: List<Mix>) {
        val exo = player ?: return
        runStarting()
        exo.setMediaItems(run.map { MediaItem.fromUri(it.uri) }, 0, C.TIME_UNSET)
        exo.prepare()
        exo.play()
    }

    /**
     * A request said out loud to the car — or to anything else that asks the standard way. It
     * arrives as an item with no id and no uri, only the words, in its request metadata. The app's
     * own Activity asks with [SPOKEN] instead.
     */
    private val MediaItem.isSpokenRequest: Boolean
        get() = localConfiguration == null && requestMetadata.searchQuery != null

    /** What [query] plays, logged — see [Library.playbackForSearch]. */
    private suspend fun spokenRun(query: String, extras: Bundle?): List<Mix> {
        val run = library.playbackForSearch(query, extras)
        Log.d(VOICE_TAG, "session: \"$query\" plays ${run.size}")
        return run
    }

    /**
     * What a spoken request plays — see [Library.playbackForSearch].
     *
     * A request that matches nothing says so, to whoever asked, and then fails. Passing it through
     * would hand the player an item with nothing in it to play, which stops the music and then
     * shows nothing at all: from the driver's seat, a request that was not heard.
     */
    private suspend fun spoken(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        item: MediaItem,
    ): List<Mix> {
        val query = item.requestMetadata.searchQuery.orEmpty()
        val run = spokenRun(query, item.requestMetadata.extras)
        if (run.isEmpty()) {
            val said = getString(R.string.voice_nothing_found, query)
            session.sendError(controller, SessionError(SessionError.ERROR_BAD_VALUE, said))
            // Failing the future is what makes the session drop the command and leave the transport
            // as it was.
            throw NoSuchElementException("Nothing matches \"$query\"")
        }
        return run
    }

    /**
     * Run [block] off the caller's thread and hand back the future the session API wants.
     *
     * Every browse answer here touches the document provider, which is a round trip that must not
     * happen on the main thread — and the car is waiting, so it must not take long either. See the
     * limits in [Library].
     */
    companion object {
        /** The custom action id for the repeat toggle. */
        private const val REPEAT = "de.singular.player.REPEAT"

        /**
         * The custom action id for a spoken request from the app's own Activity: the words in
         * [KEY_QUERY], the assistant's extras in [KEY_EXTRAS].
         *
         * A command rather than a set of media items, which is how a car asks. A controller that
         * sets items applies them to its own copy of the player at once, before the session has
         * answered — so a request that matches nothing leaves that copy holding an item with
         * nothing in it while the real player plays on, and the phone reads its position and its
         * mix from the wrong one until something else changes. A command changes nothing on the
         * asking side; the session loads the run, or answers that there is none.
         */
        const val SPOKEN = "de.singular.player.SPOKEN"
        const val KEY_QUERY = "query"
        const val KEY_EXTRAS = "extras"

        /** The commands that start, stop or move playback, by name, for [onPlayerCommandRequest]. */
        private val LoggedCommands = mapOf(
            Player.COMMAND_PLAY_PAUSE to "play/pause",
            Player.COMMAND_PREPARE to "prepare",
            Player.COMMAND_STOP to "stop",
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM to "seek",
            Player.COMMAND_SEEK_TO_MEDIA_ITEM to "seek to item",
            Player.COMMAND_SET_MEDIA_ITEM to "set item",
            Player.COMMAND_CHANGE_MEDIA_ITEMS to "change items",
        )
    }

    /**
     * What the player did, and the reason it gives — the other half of the commands logged in
     * [Callback.onPlayerCommandRequest]. A pause nobody asked for shows up here with its cause:
     * audio focus, headphones out, or the end of the run.
     */
    private object TransportLog : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val why = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "asked"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio focus lost"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "headphones out"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end of item"
                Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed too long"
                else -> "reason $reason"
            }
            Log.d(PLAYBACK_TAG, "player: ${if (playWhenReady) "play" else "pause"} ($why)")
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                val from = oldPosition.positionMs
                Log.d(PLAYBACK_TAG, "player: seek $from -> ${newPosition.positionMs} ms")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(PLAYBACK_TAG, "player: now ${mediaItem?.localConfiguration?.uri?.lastPathSegment}")
        }
    }

    private fun <T> future(name: String = "", block: suspend () -> T): ListenableFuture<T> {
        val settable = SettableFuture.create<T>()
        scope.launch {
            runCatching { block() }
                .onSuccess { settable.set(it) }
                .onFailure {
                    // Named, so a session command that fails can be told from one that never
                    // answers — the second leaves nothing in the log at all.
                    if (name.isNotEmpty()) Log.d(VOICE_TAG, "session: $name failed: $it")
                    settable.setException(it)
                }
        }
        return settable
    }
}
