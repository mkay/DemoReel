// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.singular.player.audio.Artwork
import de.singular.player.audio.AudioDecoder
import de.singular.player.audio.AudioTarget
import de.singular.player.audio.Envelope
import de.singular.player.audio.Mp3
import de.singular.player.audio.NormalizeMode
import de.singular.player.audio.Tags
import de.singular.player.storage.Defaults
import de.singular.player.storage.Favourites
import de.singular.player.storage.Folder
import de.singular.player.storage.ImageClipboard
import de.singular.player.storage.Listing
import de.singular.player.storage.Mix
import de.singular.player.storage.MixStore
import de.singular.player.storage.Recents
import de.singular.player.storage.StorageFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * A favourite, with the key it is marked under.
 *
 * The key doubles as the mix's whereabouts: it is a path under the root, so its parent is the folder
 * to show beside the name — the whole point of that list being that the mixes in it come from all
 * over the tree and the name alone does not say where from.
 */
data class FavouriteMix(val mix: Mix, val key: String) {
    /** The folder this sits in, or null at the root, where there is nothing useful to say. */
    val folder: String? get() = key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
}

/** Where the user is in their folder, and what is in it. */
data class LibraryState(
    /** Root first, current folder last; empty until a root has been granted. */
    val path: List<Folder> = emptyList(),
    val listing: Listing? = null,
    val loading: Boolean = false,
    /**
     * No usable folder: none has ever been picked, or the one that was is gone — revoked,
     * unmounted, deleted. Either way there is nothing to show until one is chosen.
     */
    val rootMissing: Boolean = false,
    /** Whether a folder had been picked before, which is what distinguishes gone from never-set. */
    val rootWasSet: Boolean = false,
) {
    val current: Folder? get() = path.lastOrNull()
    val canGoUp: Boolean get() = path.size > 1
}

/**
 * A move being aimed: what is going, and where in the tree the user has got to looking for somewhere
 * to put it.
 *
 * The destination is browsed rather than typed, and browsed separately from the library itself: the
 * whole point of moving a mix is to put it somewhere other than where you are standing, and walking
 * the list out from under the user to find that place would lose their selection on the way.
 */
data class MovePicker(
    val uris: List<Uri>,
    /** Root first, the folder being looked at last. */
    val path: List<Folder>,
    val folders: List<Folder> = emptyList(),
    val loading: Boolean = true,
) {
    val destination: Folder get() = path.last()
    val canGoUp: Boolean get() = path.size > 1
}

/**
 * The mix loaded for playback, if any — which outlives playing it.
 *
 * Stopping keeps the mix and the position, so the mini player can stay on screen with something to
 * resume. It is cleared outright by [PlayerViewModel.stopPlayback], for when the mix is going away:
 * deleted, moved, or the app shutting down.
 */
data class PlaybackState(
    val mix: Mix? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    /**
     * The mix played all the way through rather than being stopped part-way. It stays loaded either
     * way — that is what lets it be played again from where it sits — but the two are not the same
     * thing to look at: a mix someone paused is one they are in the middle of, and a mix that ran
     * out is one they are done with. Only the first is worth a bar across the bottom of every other
     * screen.
     */
    val finished: Boolean = false,
) {
    val uri: Uri? get() = mix?.uri
}

/**
 * One mix opened in the player, with the envelope it is drawn from.
 *
 * A plain data class, which it could not be while this held a bare `FloatArray`: an array's
 * `equals` is identity, so the generated one compared the wrong thing and had to be written out by
 * hand. [Envelope] is an ordinary class, so *its* identity comparison is what the generated
 * `equals` already does — which is exactly right here, since an envelope is read once and then
 * passed around by reference.
 */
data class OpenMix(
    val mix: Mix,
    /** Null while still being read, and after a read that found nothing drawable. */
    val envelope: Envelope? = null,
    val loadingWaveform: Boolean = true,
    /**
     * What is written against this mix — its comment tag, empty for nothing.
     *
     * Not taken from [Mix.tags], which is filled by the platform's reader and has no comment in it
     * at all; this is read out of the container itself when the mix is opened. See [TagIo].
     */
    val note: String = "",
)

/**
 * One mix open in the tag editor, and the form as it currently stands.
 *
 * The form lives here rather than in the composable, which is the opposite of where a text field's
 * state usually goes. Three things pull it up: applying the defaults rewrites every field at once
 * and is a suspending read of the stored cover; picking artwork is an activity result that arrives
 * from outside the composition entirely; and a rewrite is a job that must survive the screen
 * rotating under it. Any one of those could be worked around; all three together mean the form is
 * simply state the ViewModel owns.
 *
 * [tags] and [cover] start as what the file actually holds and are edited from there — so saving
 * writes back exactly what was read plus the changes, and a field this app does not show is not a
 * field it can quietly drop.
 */
data class TagEdit(
    val mix: Mix,
    val loading: Boolean = true,
    /** Whether this app can write tags into this format at all — see [de.singular.player.audio.TagFormat]. */
    val supported: Boolean = true,
    /**
     * Whether the file parsed. False means it is not the format its extension claims, which is
     * worth saying plainly rather than presenting an empty form that would fail on save.
     */
    val readable: Boolean = true,
    val tags: Tags = Tags(),
    val cover: Artwork? = null,
    /** Whether anything has been changed since it was read — what the back gesture asks about. */
    val dirty: Boolean = false,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val store = MixStore(application)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val favouriteStore = Favourites(application)
    private val recentStore = Recents(application)
    private val defaultsStore = Defaults(application)

    /** The store, for whatever else needs to read files — the cover loader, chiefly. */
    val mixStore: MixStore get() = store

    /**
     * The mixes opened most recently, as keys — see [Recents]. What the drawer lists.
     *
     * Keys rather than mixes, and nothing resolved here: the last segment of a key is the filename,
     * so the drawer draws from this alone and opens full rather than filling in. See [openRecent]
     * for what a tap costs.
     */
    private val _recents = MutableStateFlow(recentStore.all())
    val recents: StateFlow<List<String>> = _recents.asStateFlow()

    /**
     * The favourites, as keys — see [Favourites]. Held as a flow so the list can reorder itself and
     * the row can fill its star in the same frame the tap lands, rather than waiting for a refresh
     * of the folder to come back from the provider.
     */
    private val _favourites = MutableStateFlow(favouriteStore.all())
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

    /**
     * The same marks, as the *order* sees them — [_favourites] a beat later.
     *
     * The star fills the moment it is tapped, but the row it is on stays put for [REORDER_DELAY_MS]
     * before rising to the top of the folder. A row that leaves under your thumb reads as having
     * marked the wrong mix: the confirmation and the movement arrive together, so there is no moment
     * where the mix you touched is still where you left it and visibly marked. Waiting separates the
     * two, and the delay is short enough that the list is in its settled order long before you have
     * finished looking at it.
     */
    private val _orderMarks = MutableStateFlow(favouriteStore.all())

    private var reorderJob: Job? = null

    /** Let the order catch up with the marks, after the pause described on [_orderMarks]. */
    private fun reorderAfterPause() {
        reorderJob?.cancel()
        reorderJob = viewModelScope.launch {
            delay(REORDER_DELAY_MS)
            _orderMarks.value = _favourites.value
        }
    }

    /**
     * Order by the marks as they stand, now — for a listing that has just been fetched, where there
     * is no row under a thumb to be considerate of and a stale order would simply be wrong.
     */
    private fun reorderNow() {
        reorderJob?.cancel()
        reorderJob = null
        _orderMarks.value = _favourites.value
    }

    /**
     * The favourites, resolved, for the Favourites tab. Null until they have been looked up once —
     * which is what tells the screen to say "loading" rather than "nothing marked yet".
     */
    private val _favouriteMixes = MutableStateFlow<List<FavouriteMix>?>(null)
    val favouriteMixes: StateFlow<List<FavouriteMix>?> = _favouriteMixes.asStateFlow()

    private var favouritesJob: Job? = null

    /**
     * Resolve every favourite key into a mix, dropping the ones that no longer exist.
     *
     * That dropping is the index's only garbage collection. A mark can be orphaned by a rename or a
     * delete done outside the app, which nothing in here can be told about; going to look is the
     * only way to find out, and this is the one place that has reason to look at every key at once.
     */
    fun loadFavourites() {
        favouritesJob?.cancel()
        favouritesJob = viewModelScope.launch {
            val keys = _favourites.value.sorted()
            val found = keys.mapNotNull { key -> store.mixAt(key)?.let { FavouriteMix(it, key) } }
            val gone = keys.toSet() - found.map { it.key }.toSet()
            if (gone.isNotEmpty()) _favourites.value = favouriteStore.removeAll(gone)
            _favouriteMixes.value = found.sortedByDescending { it.mix.modifiedAt }
        }
    }

    /** The key [uri] is marked under, or null if it is not under the granted root. */
    fun favouriteKey(uri: Uri): String? = Favourites.keyFor(store.root, uri)

    fun toggleFavourite(uri: Uri) {
        val key = favouriteKey(uri) ?: return
        _favourites.value = favouriteStore.toggle(key)
        // Unmarking from inside the Favourites tab should take the row out of it, rather than
        // leaving an unmarked entry sitting in a list of favourites until the screen is reopened.
        _favouriteMixes.value = _favouriteMixes.value?.filter { it.key in _favourites.value }
        reorderAfterPause()
    }

    private val _settings = MutableStateFlow(
        Settings(
            favouritesFirst = prefs.getBoolean(KEY_FAVOURITES_FIRST, true),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false),
            themeMode = runCatching {
                ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, null) ?: "")
            }.getOrDefault(ThemeMode.SYSTEM),
        ),
    )
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _library = MutableStateFlow(LibraryState())

    /**
     * The folder on screen, with favourites brought to the front.
     *
     * Ordering happens here rather than in [MixStore] because a favourite is not a property of
     * storage — the store lists what is in a folder, and what a favourite is is none of its
     * business. It also cannot happen there: a mark can be given and taken back without the folder
     * changing at all, so the order has to be able to move without a listing being fetched again.
     * Combining the two flows is what gives that; [_library] keeps the provider's own order and is
     * what the rest of this class works from.
     *
     * The marks it orders by are [_orderMarks] — the tapped ones, a moment later — so a mix does not
     * leave from under the thumb that marked it. The rows themselves are drawn from [favourites],
     * which is immediate.
     */
    val library: StateFlow<LibraryState> = combine(
        _library,
        _orderMarks,
        _settings,
    ) { state, marks, settings ->
        if (settings.favouritesFirst) state.favouritesFirst(marks) else state
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    /**
     * Favourites first, then newest first within each group — which is the order the provider
     * already gives, so unmarking a mix drops it back exactly where it would have been. Sorting is
     * stable, so nothing else shifts around it.
     */
    private fun LibraryState.favouritesFirst(marks: Set<String>): LibraryState {
        val listing = listing ?: return this
        if (marks.isEmpty() || listing.mixes.size < 2) return this
        return copy(
            listing = listing.copy(
                mixes = listing.mixes.sortedWith(
                    compareByDescending<Mix> { favouriteKey(it.uri) in marks }
                        .thenByDescending { it.modifiedAt },
                ),
            ),
        )
    }

    private val _playback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = _playback.asStateFlow()

    /** One-shot user-facing notices ("Moved 3 mixes", "Could not create the folder"). */
    private val _message = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = _message.asStateFlow()

    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    /** Opening a mix for playback — the decode check and the prepare. See [play]. */
    private var playJob: Job? = null

    // Only what is declared above this line may be touched here — see the second init block, down
    // with the defaults, and the launch crash that put it there.
    init {
        openRoot()
    }

    // ---- storage ----------------------------------------------------------------------------

    /** Remember the folder the system picker just handed back, and show it. */
    fun onFolderPicked(uri: Uri) {
        if (!store.setRoot(uri)) {
            _message.value = Message.Text(R.string.msg_folder_not_kept)
            return
        }
        openRoot()
    }

    private fun openRoot() {
        val remembered = store.rootWasSet
        val root = store.rootFolder() ?: run {
            _library.value = LibraryState(rootMissing = true, rootWasSet = remembered)
            return
        }
        viewModelScope.launch {
            val name = store.rootName() ?: "Mixes"
            _library.value = LibraryState(
                path = listOf(Folder(root, name)),
                loading = true,
                rootWasSet = remembered,
            )
            refresh()
        }
    }

    fun refresh() {
        val folder = _library.value.current ?: return
        viewModelScope.launch {
            _library.value = _library.value.copy(loading = true)
            val listing = store.list(folder.uri)
            // A root that will not list is as good as gone — the card it lived on was pulled, or it
            // was deleted from under us. Sub-folders failing only means that sub-folder went.
            val rootGone = !_library.value.canGoUp && listing.error != null
            // A listing that has just arrived is drawn in its settled order: the pause is for the
            // row that was tapped, and by now there is a new set of rows.
            reorderNow()
            _library.value = _library.value.copy(
                listing = listing,
                loading = false,
                rootMissing = rootGone,
            )
        }
    }

    fun openFolder(folder: Folder) {
        _library.value = _library.value.copy(path = _library.value.path + folder, listing = null)
        refresh()
    }

    fun goUp() {
        if (!_library.value.canGoUp) return
        _library.value = _library.value.copy(path = _library.value.path.dropLast(1), listing = null)
        refresh()
    }

    /** Jump to an ancestor by its position in the breadcrumb. */
    fun goTo(index: Int) {
        val path = _library.value.path
        if (index !in path.indices || index == path.lastIndex) return
        _library.value = _library.value.copy(path = path.take(index + 1), listing = null)
        refresh()
    }

    fun createFolder(name: String) {
        val parent = _library.value.current ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            val created = store.createFolder(parent.uri, name)
            if (created == null) _message.value = Message.Text(R.string.msg_folder_not_created)
            refresh()
        }
    }

    fun renameDocument(uri: Uri, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            val before = favouriteKey(uri)
            val renamed = store.rename(uri, newName)
            if (renamed == null) {
                _message.value = Message.Text(R.string.msg_not_renamed)
            } else {
                // A mark is kept against the document id, which is the path — so renaming moves it.
                // Folders matter more than files here: renaming one changes the id of everything
                // inside it, and [Favourites.rename] rewrites the prefix so those marks come along.
                val after = favouriteKey(renamed)
                if (before != null && after != null && before != after) {
                    _favourites.value = favouriteStore.rename(before, after)
                    // As does a place in the recents list — a mix renamed from the player is very
                    // often the one at the top of it.
                    _recents.value = recentStore.rename(before, after)
                }
            }
            refresh()
            if (_favouriteMixes.value != null) loadFavourites()
        }
    }

    // ---- moving -----------------------------------------------------------------------------

    private val _movePicker = MutableStateFlow<MovePicker?>(null)
    val movePicker: StateFlow<MovePicker?> = _movePicker.asStateFlow()

    /** Open the destination browser for [uris], starting at the root of the granted folder. */
    fun startMove(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val root = _library.value.path.firstOrNull() ?: run {
            _message.value = Message.Text(R.string.msg_choose_folder_first)
            return
        }
        _movePicker.value = MovePicker(uris, path = listOf(root))
        loadMoveFolders()
    }

    fun openMoveFolder(folder: Folder) {
        val picker = _movePicker.value ?: return
        _movePicker.value = picker.copy(
            path = picker.path + folder, folders = emptyList(), loading = true,
        )
        loadMoveFolders()
    }

    fun moveGoUp() {
        val picker = _movePicker.value ?: return
        if (!picker.canGoUp) return
        _movePicker.value = picker.copy(
            path = picker.path.dropLast(1), folders = emptyList(), loading = true,
        )
        loadMoveFolders()
    }

    private fun loadMoveFolders() {
        val at = _movePicker.value?.destination ?: return
        viewModelScope.launch {
            val folders = store.folders(at.uri)
            // The user may have walked on while the provider was answering.
            _movePicker.update { picker ->
                if (picker == null || picker.destination != at) picker
                else picker.copy(folders = folders, loading = false)
            }
        }
    }

    fun cancelMove() {
        _movePicker.value = null
    }

    /**
     * Move what the picker holds into the folder it is showing.
     *
     * A mark travels with its mix — the key is the path, so moving one rewrites it, exactly as a
     * rename does. So does the player: if the mix on screen is one of the ones that moved, it is
     * re-read at its new home rather than left pointing at a file that is no longer there.
     */
    fun confirmMove() {
        val picker = _movePicker.value ?: return
        _movePicker.value = null
        val destination = picker.destination
        viewModelScope.launch {
            var moved = 0
            var failure: Message? = null
            picker.uris.forEach { uri ->
                val before = favouriteKey(uri)
                store.move(uri, destination.uri)
                    .onSuccess { newUri ->
                        moved++
                        val after = favouriteKey(newUri)
                        if (before != null && after != null && before != after) {
                            _favourites.value = favouriteStore.rename(before, after)
                            _recents.value = recentStore.rename(before, after)
                        }
                        if (_playback.value.uri == uri) stopPlayback()
                        if (_openMix.value?.mix?.uri == uri) {
                            store.mix(newUri)?.let {
                                _openMix.value = OpenMix(it, _openMix.value?.envelope, false)
                            }
                        }
                    }
                    .onFailure { failure = Message.of(it, R.string.msg_not_moved) }
            }
            val failed = failure
            _message.value = when {
                failed != null && moved == 0 -> failed
                failed != null -> Message.Quantity(R.plurals.msg_moved_partly, moved)
                moved == 1 -> Message.Text(R.string.msg_moved_one, listOf(destination.name))
                else -> Message.Quantity(
                    R.plurals.msg_moved_many, moved, listOf(destination.name),
                )
            }
            refresh()
            if (_favouriteMixes.value != null) loadFavourites()
        }
    }

    fun deleteDocument(uri: Uri) {
        viewModelScope.launch {
            if (uri == _playback.value.uri) stopPlayback()
            if (!store.delete(uri)) {
                _message.value = Message.Text(R.string.msg_not_deleted)
            } else {
                // Deleting a folder takes the marks inside it with it, which is what
                // [Favourites.remove] does with a prefix — otherwise they would sit in the index
                // forever, pointing at nothing and costing a failed lookup every time it is read.
                favouriteKey(uri)?.let {
                    _favourites.value = favouriteStore.remove(it)
                    _recents.value = recentStore.remove(it)
                }
            }
            refresh()
            if (_favouriteMixes.value != null) loadFavourites()
        }
    }

    // ---- the player -------------------------------------------------------------------------

    private val _openMix = MutableStateFlow<OpenMix?>(null)
    val openMix: StateFlow<OpenMix?> = _openMix.asStateFlow()

    private var waveformJob: Job? = null

    /**
     * Open a mix from the recents list, by the key it is remembered under.
     *
     * The one place a recent key is resolved, and so the one place a stale one is found: a mix
     * deleted or renamed from outside the app leaves a key pointing at nothing. That key is dropped
     * here rather than by a sweep — see [Recents] — and [onOpened] is not called, so a dead entry
     * costs a message and a row disappearing rather than an empty player.
     */
    fun openRecent(key: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            val mix = store.mixAt(key)
            if (mix == null) {
                _recents.value = recentStore.remove(key)
                _message.value = Message.Text(R.string.msg_mix_gone)
                return@launch
            }
            openMix(mix)
            onOpened()
        }
    }

    /** Open [mix] in the player and start reading its waveform. Does not start playing it. */
    fun openMix(mix: Mix) {
        // Every route into the player comes through here — a library row, the mini player, the
        // drawer — so this is the one place the recents list has to be stamped.
        favouriteKey(mix.uri)?.let { _recents.value = recentStore.touch(it) }
        // Whatever was playing was a different mix, or the same one from the mini player; either way
        // the player screen takes over the transport from here.
        if (_playback.value.uri != mix.uri) stopPlayback()
        _openMix.value = OpenMix(mix)
        waveformJob?.cancel()
        waveformJob = viewModelScope.launch {
            // The note first: it is one small read against a whole decode, and the chip that shows
            // it is on screen from the start.
            val note = store.readTags(mix)?.tags?.comment.orEmpty()
            if (_openMix.value?.mix?.uri == mix.uri) {
                _openMix.update { it?.copy(note = note) }
            }
            val envelope = store.waveform(mix)
            // The user may have backed out or opened another mix while we were reading.
            if (_openMix.value?.mix?.uri == mix.uri) {
                _openMix.update { it?.copy(envelope = envelope, loadingWaveform = false) }
            }
        }
    }

    /**
     * Rename the open mix and stay on it — the library list is refreshed behind us.
     *
     * A rename can mint a new uri, and it always changes the name the player is titled with, so the
     * mix is re-read rather than patched in place.
     */
    fun renameOpenMix(newName: String) {
        val mix = _openMix.value?.mix ?: return
        if (newName.isBlank()) return
        viewModelScope.launch {
            val before = favouriteKey(mix.uri)
            val renamed = store.rename(mix.uri, newName)
            if (renamed == null) {
                _message.value = Message.Text(R.string.msg_not_renamed)
                return@launch
            }
            favouriteKey(renamed)?.let { after ->
                if (before != null && before != after) {
                    _favourites.value = favouriteStore.rename(before, after)
                    _recents.value = recentStore.rename(before, after)
                }
            }
            // The file the player has open is the file that moved; drop it rather than leave the
            // system player holding a uri that no longer resolves.
            if (_playback.value.uri == mix.uri) stopPlayback()
            store.mix(renamed)?.let { _openMix.value = OpenMix(it, _openMix.value?.envelope, false) }
            refresh()
        }
    }

    /** Leave the player. Playback carries on in the mini player rather than being cut off. */
    fun closeMix() {
        waveformJob?.cancel()
        waveformJob = null
        _openMix.value = null
    }

    // ---- playback ---------------------------------------------------------------------------

    /**
     * Stop [mix] if it is the one playing; otherwise play it.
     *
     * A [fromMs] of -1 means "wherever this mix was left" — resuming the position the mini player is
     * showing. The player screen passes its playhead instead, because there the user has just
     * pointed at a spot.
     */
    fun togglePlayback(mix: Mix, fromMs: Long = -1) {
        val current = _playback.value
        if (current.uri == mix.uri && current.playing) {
            pausePlayback()
            return
        }
        val resume = when {
            fromMs >= 0 -> fromMs
            current.uri == mix.uri -> current.positionMs
            else -> 0
        }
        play(mix, resume)
    }

    /** Give up the audio device but keep the mix and where we were in it. */
    private fun pausePlayback() {
        // A mix that is still opening counts as playing to the user, so stop has to reach it too.
        playJob?.cancel()
        playJob = null
        progressJob?.cancel()
        progressJob = null
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        _playback.value = _playback.value.copy(playing = false)
    }

    /**
     * Start [mix] at [fromMs] — or say plainly that it cannot be played.
     *
     * **Why a decode comes first.** [MediaPlayer] is willing to prepare and start a file whose audio
     * never reaches the speaker: an ALAC file does exactly that on some devices, because the system
     * hands the compressed stream to a DSP offload path that fails on every write while the
     * transport reports itself playing and the position runs past the file's own duration. Silence
     * that looks like playback is the worst answer available, so a buffer is decoded first and the
     * press is refused if that fails. See [AudioDecoder.canDecode] for why the codec list is no
     * substitute.
     *
     * The check goes here rather than only on the player screen because a mix is started from the
     * library list and the mini player too, neither of which has read a waveform.
     *
     * All of it off the main thread, which is where [MediaPlayer.prepare] belonged anyway.
     */
    private fun play(mix: Mix, fromMs: Long) {
        pausePlayback()
        playJob?.cancel()
        playJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val decodable = withContext(Dispatchers.IO) { AudioDecoder.canDecode(app, mix.uri) }
            if (!decodable) {
                _message.value = Message.of(StorageFailure.NO_DECODER)
                return@launch
            }
            val mp = MediaPlayer()
            mp.isLooping = _looping.value
            val started = withContext(Dispatchers.IO) {
                runCatching {
                    mp.setDataSource(app, mix.uri)
                    mp.prepare()
                    // Seek before starting, so a mix opened at a mark does not blurt out its first
                    // half-second before jumping.
                    if (fromMs > 0) mp.seekTo(fromMs.toInt())
                    mp.start()
                }.isSuccess
            }
            if (!started) {
                runCatching { mp.release() }
                _message.value = Message.Text(R.string.msg_not_played)
                return@launch
            }
            // A second press while the first was still opening: that press owns the transport now,
            // and this one has a started player to put down.
            if (!isActive) {
                runCatching { mp.stop() }
                runCatching { mp.release() }
                return@launch
            }
            start(mix, mp, fromMs)
        }
    }

    /** The bookkeeping around a [MediaPlayer] that is already playing — see [play]. */
    private fun start(mix: Mix, mp: MediaPlayer, fromMs: Long) {
        // Reaching the end leaves the mix loaded at its start, ready to go again — but marked as
        // done with, so nothing has to keep reporting on it. Playing it again clears the mark, since
        // [play] builds the state fresh.
        mp.setOnCompletionListener {
            pausePlayback()
            _playback.value = _playback.value.copy(positionMs = 0, finished = true)
        }
        player = mp
        _playback.value = PlaybackState(
            mix = mix,
            positionMs = fromMs.coerceAtLeast(0),
            durationMs = mp.duration.toLong().coerceAtLeast(0),
            playing = true,
        )
        progressJob = viewModelScope.launch {
            while (true) {
                delay(PROGRESS_TICK_MS)
                val current = player ?: break
                val at = runCatching { current.currentPosition.toLong() }.getOrNull() ?: break
                _playback.value = _playback.value.copy(positionMs = at)
            }
        }
    }

    /**
     * Whether playback repeats. A property of listening rather than of a mix: you turn it on to sit
     * with something, and it stays on until you turn it off.
     */
    private val _looping = MutableStateFlow(false)
    val looping: StateFlow<Boolean> = _looping.asStateFlow()

    fun toggleLoop() {
        val on = !_looping.value
        _looping.value = on
        // Takes effect on what is already playing, not only on the next press.
        player?.let { runCatching { it.isLooping = on } }
        _message.value = Message.Text(if (on) R.string.msg_looping else R.string.msg_loop_off)
    }

    /** Play [mix] from its first sample, whatever it was doing before. */
    fun restartPlayback(mix: Mix) = play(mix, 0)

    /** The mini player's transport, which always has a mix to act on. */
    fun toggleCurrent() {
        _playback.value.mix?.let { togglePlayback(it) }
    }

    fun seekTo(ms: Long) {
        player?.let { runCatching { it.seekTo(ms.toInt()) } }
        _playback.value = _playback.value.copy(positionMs = ms)
    }

    /** Unload the mix entirely — for when it is going away, not merely pausing. */
    fun stopPlayback() {
        pausePlayback()
        _playback.value = PlaybackState()
    }

    // ---- the tag editor ---------------------------------------------------------------------

    private val _editing = MutableStateFlow<TagEdit?>(null)
    val editing: StateFlow<TagEdit?> = _editing.asStateFlow()

    /** True while files are being rewritten — the editor and the bulk apply both disable on it. */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var editorJob: Job? = null

    /**
     * Open [mix] in the tag editor, reading what it actually carries.
     *
     * Read through [MixStore.readTags] rather than from the [Mix] already in hand: the listing's
     * copy came from the platform's reader, which cannot see a comment at all, and an editor that
     * opened onto a blank comment field would blank a real comment the moment it saved. See [TagIo].
     */
    fun openEditor(mix: Mix) {
        editorJob?.cancel()
        val supported = mix.tagFormat != null
        _editing.value = TagEdit(mix = mix, supported = supported)
        if (!supported) {
            _editing.value = _editing.value?.copy(loading = false)
            return
        }
        editorJob = viewModelScope.launch {
            val content = store.readTags(mix)
            // The user may have backed out while the file was being read.
            if (_editing.value?.mix?.uri != mix.uri) return@launch
            _editing.value = TagEdit(
                mix = mix,
                loading = false,
                supported = true,
                readable = content != null,
                tags = content?.tags ?: Tags(),
                cover = content?.cover?.let { Artwork.of(it) },
            )
        }
    }

    fun closeEditor() {
        editorJob?.cancel()
        editorJob = null
        _editing.value = null
    }

    /** Change one field of the form. Everything the editor types goes through here. */
    fun editTags(transform: (Tags) -> Tags) {
        _editing.update { edit ->
            edit?.copy(tags = transform(edit.tags), dirty = true)
        }
    }

    /**
     * Put the image at [uri] into the form as its cover, or drop the cover when [uri] is null.
     *
     * Re-encoded on the way in rather than on the way out — see [Artwork.encodeForEmbedding] for
     * why a picked image is never embedded as it stands. Doing it here rather than at save also
     * means the editor previews the artwork that will actually be written, at the size it will be
     * written at.
     */
    fun setEditorCover(uri: Uri?) {
        if (uri == null) {
            _editing.update { it?.copy(cover = null, dirty = true) }
            return
        }
        viewModelScope.launch {
            val artwork = readImage(uri)
            if (artwork == null) {
                _message.value = Message.Text(R.string.msg_image_unreadable)
                return@launch
            }
            _editing.update { it?.copy(cover = artwork, dirty = true) }
        }
    }

    /**
     * Put [mix]'s cover on the system clipboard.
     *
     * The bytes are re-read from the file rather than taken from whatever the screen is showing:
     * what is on screen is a *decoded, downsampled* bitmap for display, and copying that would hand
     * out a worse image than the one in the file.
     */
    fun copyCover(mix: Mix) {
        viewModelScope.launch {
            val bytes = store.cover(mix)
            if (bytes == null) {
                _message.value = Message.Text(R.string.msg_no_cover_to_copy)
                return@launch
            }
            val copied = withContext(Dispatchers.IO) {
                ImageClipboard.copy(getApplication(), bytes, mix.displayTitle)
            }
            _message.value = when {
                !copied -> Message.Text(R.string.msg_cover_not_copied)
                // Android 13 and up pops its own clipboard confirmation, complete with a preview of
                // what was copied. Saying it again underneath would be the app talking over the
                // system. Below that there is no confirmation at all, so this is the only sign
                // anything happened. Same split the About screen's version-copy makes.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> null
                else -> Message.Text(R.string.msg_cover_copied)
            }
        }
    }

    /** Take whatever image is on the clipboard as the editor's cover. */
    fun pasteCoverInEditor() {
        viewModelScope.launch {
            val artwork = clipboardArtwork() ?: return@launch
            _editing.update { it?.copy(cover = artwork, dirty = true) }
        }
    }

    /**
     * The image on the clipboard, ready to embed — or null, having already said why.
     *
     * Shared by the two places a cover can be pasted, which is the point: an editor and a default
     * that disagreed about what counts as a usable image, or that reported the same failure in
     * different words, would be two features rather than one.
     */
    private suspend fun clipboardArtwork(): Artwork? {
        val bytes = withContext(Dispatchers.IO) { ImageClipboard.read(getApplication()) }
        if (bytes == null) {
            _message.value = Message.Text(R.string.msg_no_image_on_clipboard)
            return null
        }
        val artwork = withContext(Dispatchers.Default) { Artwork.encodeForEmbedding(bytes) }
        if (artwork == null) _message.value = Message.Text(R.string.msg_image_unreadable)
        return artwork
    }

    /** Fill the form from the defaults, exactly as a bulk apply would fill the file. */
    fun applyDefaultsInEditor(mode: ApplyMode) {
        val edit = _editing.value ?: return
        val defaults = _defaults.value
        viewModelScope.launch {
            val cover = chooseCover(edit.cover, defaultsStore.cover(), mode)
            _editing.update { current ->
                if (current?.mix?.uri != edit.mix.uri) return@update current
                current.copy(
                    tags = defaults.applyTo(current.tags, mode, edit.mix.baseName),
                    cover = cover,
                    dirty = true,
                )
            }
        }
    }

    /** Write the form back into the file. */
    fun saveEditor() {
        val edit = _editing.value ?: return
        if (_busy.value || !edit.supported) return
        viewModelScope.launch {
            _busy.value = true
            // The file is about to be rebuilt under the system player; let go of it first rather
            // than leave MediaPlayer reading a document whose bytes are being replaced.
            if (_playback.value.uri == edit.mix.uri) stopPlayback()
            store.writeTags(edit.mix, edit.tags, edit.cover)
                .onSuccess { written ->
                    _message.value = Message.Text(R.string.msg_tags_saved)
                    _editing.value = null
                    // The player is showing what the file said a moment ago, and it has changed.
                    if (_openMix.value?.mix?.uri == written.uri) {
                        _openMix.value = OpenMix(written, _openMix.value?.envelope, false)
                    }
                    refresh()
                    if (_favouriteMixes.value != null) loadFavourites()
                }
                .onFailure { _message.value = Message.of(it, R.string.msg_tags_not_saved) }
            _busy.value = false
        }
    }

    // ---- editing the audio ------------------------------------------------------------------

    /**
     * The formats a mix can be written out as here, minus any this device cannot manage.
     *
     * Asked of the audio layer rather than listed in the UI, because MP3 depends on a native
     * encoder having loaded — see [Mp3]. A target that would fail is better missing than greyed.
     */
    val targets: List<AudioTarget> = AudioTarget.available()

    /**
     * Lift the open mix to full level, as [target], over the original or beside it.
     *
     * A whole-file rewrite, so it takes the same [busy] lock the tag editor does: two of these at
     * once would be two decodes competing for the same storage, and an overwrite racing a save is
     * not something to find out about afterwards.
     */
    fun normalizeOpenMix(mode: NormalizeMode, target: AudioTarget, overwrite: Boolean) {
        val mix = _openMix.value?.mix ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            // The bytes under the system player are about to be replaced; let go of the file first.
            if (overwrite && _playback.value.uri == mix.uri) stopPlayback()
            store.normalize(mix, mode, target, overwrite)
                .onSuccess { rendered ->
                    _message.value = when {
                        rendered.gainDb <= 0f -> Message.Text(R.string.msg_level_already_full)
                        overwrite -> Message.Text(
                            R.string.msg_level_lifted,
                            listOf(formatDb(rendered.gainDb)),
                        )

                        else -> Message.Text(
                            R.string.msg_level_lifted_copy,
                            listOf(formatDb(rendered.gainDb), rendered.mix.name),
                        )
                    }
                    if (rendered.mix.uri == mix.uri) reopen(rendered.mix)
                    afterEdit()
                }
                .onFailure { _message.value = Message.of(it, R.string.msg_render_failed) }
            _busy.value = false
        }
    }

    /** Write the open mix out as [target], beside it. Never touches the original. */
    fun convertOpenMix(target: AudioTarget) {
        val mix = _openMix.value?.mix ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            store.convert(mix, target)
                .onSuccess {
                    _message.value = Message.Text(R.string.msg_converted, listOf(it.mix.name))
                    afterEdit()
                }
                .onFailure { _message.value = Message.of(it, R.string.msg_render_failed) }
            _busy.value = false
        }
    }

    /**
     * Write [note] into the open mix's comment tag — empty text clearing it.
     *
     * Into the file rather than into a database of ours: a note about a mix belongs to the mix, and
     * a sidecar would be lost the first time the folder was synced to a desktop. Everything else in
     * the file is read and written back untouched, so this is the tag editor's save with one field
     * changed — which is exactly what it is made of.
     */
    fun saveNote(note: String) {
        val mix = _openMix.value?.mix ?: return
        if (_busy.value || mix.tagFormat == null) return
        viewModelScope.launch {
            _busy.value = true
            val content = store.readTags(mix)
            if (content == null) {
                _message.value = Message.Text(R.string.msg_note_not_saved)
                _busy.value = false
                return@launch
            }
            val trimmed = note.trim()
            if (_playback.value.uri == mix.uri) stopPlayback()
            store.writeTags(mix, content.tags.copy(comment = trimmed), content.cover?.let(Artwork::of))
                .onSuccess { written ->
                    _message.value = Message.Text(
                        if (trimmed.isEmpty()) R.string.msg_note_cleared else R.string.msg_note_saved,
                    )
                    reopen(written, note = trimmed)
                    afterEdit()
                }
                .onFailure { _message.value = Message.of(it, R.string.msg_note_not_saved) }
            _busy.value = false
        }
    }

    /** The player is showing a file that has just been rewritten; show what it is now. */
    private fun reopen(mix: Mix, note: String? = null) {
        _openMix.update { open ->
            if (open == null) null else open.copy(mix = mix, note = note ?: open.note)
        }
    }

    /** Every edit leaves the folder holding something different. */
    private suspend fun afterEdit() {
        refresh()
        if (_favouriteMixes.value != null) loadFavourites()
    }

    /** "+3.4" — a boost, said the way a fader is labelled. */
    private fun formatDb(db: Float): String = String.format(Locale.US, "+%.1f", db)

    // ---- the defaults -----------------------------------------------------------------------

    private val _defaults = MutableStateFlow(defaultsStore.load())
    val defaults: StateFlow<TagDefaults> = _defaults.asStateFlow()

    /**
     * The default cover, decoded once and held, so the settings screen can draw it.
     *
     * Separate from [defaults] because it is bytes rather than a preference and reading it touches
     * the disk — [TagDefaults.hasCover] is the cheap flag, and this is the picture.
     */
    private val _defaultCover = MutableStateFlow<Artwork?>(null)
    val defaultCover: StateFlow<Artwork?> = _defaultCover.asStateFlow()

    /**
     * The first read, and it has to be **here** rather than in the init block at the top of the
     * class.
     *
     * Kotlin runs property initialisers and `init` blocks in declaration order, so anything the
     * class's first `init` touches must be declared above it. [_defaultCover] is declared down here
     * with the rest of the defaults, and calling this from up there crashed on launch — not with a
     * null flow value, which would have been harmless, but with a null *flow*.
     *
     * What made it a crash rather than a no-op is where the suspension point falls. In
     * `_defaultCover.value = defaultsStore.cover()` the receiver is evaluated first and stored in
     * the continuation, then the suspending call runs; by the time it resumes the property has been
     * assigned, but the continuation is still holding the null it captured before. So the reference
     * that was fine a microsecond later was already wrong.
     *
     * Declaring the load beside the thing it loads makes the ordering self-evident and survives
     * anyone moving either one.
     */
    init {
        reloadDefaultCover()
    }

    private fun reloadDefaultCover() {
        viewModelScope.launch {
            // Read first, assign second — so the flow is dereferenced after the suspension point
            // rather than before it. Belt and braces alongside the init block above.
            val cover = defaultsStore.cover()
            _defaultCover.value = cover
        }
    }

    fun setDefaults(defaults: TagDefaults) {
        defaultsStore.save(defaults)
        // Re-read rather than trusting what was passed in: `hasCover` is a fact about the disk and
        // is not [setDefaults]' to change.
        _defaults.value = defaultsStore.load()
    }

    /** Set the default cover from the image at [uri], or clear it when that is null. */
    fun setDefaultCover(uri: Uri?) {
        viewModelScope.launch {
            if (uri == null) {
                storeDefaultCover(null)
                return@launch
            }
            val artwork = readImage(uri)
            if (artwork == null) {
                _message.value = Message.Text(R.string.msg_image_unreadable)
                return@launch
            }
            storeDefaultCover(artwork)
        }
    }

    /** Set the default cover from whatever image is on the clipboard. */
    fun pasteDefaultCover() {
        viewModelScope.launch {
            storeDefaultCover(clipboardArtwork() ?: return@launch)
        }
    }

    /**
     * Write [artwork] through to the store and to both flows that describe it.
     *
     * One place, because the flag and the picture have to move together: [TagDefaults.hasCover]
     * drives whether the Remove button is offered and whether the defaults count as empty, and
     * [_defaultCover] is what the screen draws. Updating one without the other leaves a settings
     * page showing artwork it claims not to have.
     */
    private suspend fun storeDefaultCover(artwork: Artwork?) {
        defaultsStore.setCover(artwork)
        _defaults.value = defaultsStore.load()
        _defaultCover.value = artwork
    }

    /**
     * Stamp the defaults onto every one of [uris] — the whole point of having defaults.
     *
     * Each file is read, filled and rewritten in turn. Sequential rather than parallel on purpose:
     * these are whole-file rewrites through a content provider, so running several at once competes
     * for the same storage rather than going faster, and a failure part-way is far easier to report
     * honestly when the files went one at a time.
     *
     * A folder is not left half done silently — the count that succeeded is always reported, and
     * files this app cannot re-tag are counted as skipped rather than as failures, since a folder
     * holding an m4a alongside the mp3s is an ordinary thing and not an error.
     */
    fun applyDefaultsTo(uris: List<Uri>, mode: ApplyMode) {
        if (uris.isEmpty() || _busy.value) return
        val defaults = _defaults.value
        viewModelScope.launch {
            _busy.value = true
            val cover = defaultsStore.cover()
            var written = 0
            var skipped = 0
            var failed = 0

            uris.forEach { uri ->
                val mix = store.mix(uri)
                if (mix == null || mix.tagFormat == null) {
                    skipped++
                    return@forEach
                }
                if (_playback.value.uri == uri) stopPlayback()
                val content = store.readTags(mix)
                if (content == null) {
                    failed++
                    return@forEach
                }
                val existing = content.cover?.let { Artwork.of(it) }
                store.writeTags(
                    mix = mix,
                    tags = defaults.applyTo(content.tags, mode, mix.baseName),
                    cover = chooseCover(existing, cover, mode),
                ).onSuccess { written++ }.onFailure { failed++ }
            }

            _message.value = when {
                written == 0 && failed == 0 -> Message.Text(R.string.msg_defaults_none_applicable)
                failed > 0 -> Message.Quantity(R.plurals.msg_defaults_partly, written)
                skipped > 0 -> Message.Quantity(R.plurals.msg_defaults_applied_some, written)
                else -> Message.Quantity(R.plurals.msg_defaults_applied, written)
            }
            refresh()
            if (_favouriteMixes.value != null) loadFavourites()
            _busy.value = false
        }
    }

    /**
     * The image at [uri], read and re-encoded for embedding. Null if it will not decode.
     *
     * The read is the only place this app touches a uri outside the granted folder, and it holds
     * nothing: the picker's grant lasts for this read, the bytes are re-encoded, and the uri is
     * never stored.
     */
    private suspend fun readImage(uri: Uri): Artwork? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                it.readBytes()
            }
        }.getOrNull() ?: return@withContext null
        Artwork.encodeForEmbedding(bytes)
    }

    // ---- settings ---------------------------------------------------------------------------

    fun setFavouritesFirst(on: Boolean) {
        _settings.value = _settings.value.copy(favouritesFirst = on)
        prefs.edit { putBoolean(KEY_FAVOURITES_FIRST, on) }
    }

    fun setKeepScreenOn(on: Boolean) {
        _settings.value = _settings.value.copy(keepScreenOn = on)
        prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, on) }
    }

    fun setThemeMode(mode: ThemeMode) {
        _settings.value = _settings.value.copy(themeMode = mode)
        prefs.edit { putString(KEY_THEME_MODE, mode.name) }
    }

    fun clearMessage() {
        _message.value = null
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }

    private companion object {
        /**
         * How long a freshly marked mix stays where it is before the list re-sorts around it. Long
         * enough to watch the star fill on the row you touched, short enough that the order is
         * settled by the time you look back at the list.
         */
        const val REORDER_DELAY_MS = 1_200L

        /**
         * How often the playhead is read back off the system player. Ten times a second: fast enough
         * that the waveform's playhead moves smoothly at 1x, slow enough that it is not a poll worth
         * thinking about.
         */
        const val PROGRESS_TICK_MS = 100L

        const val KEY_FAVOURITES_FIRST = "favourites_first"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
