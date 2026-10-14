// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.singular.player.MixQuery
import de.singular.player.R
import de.singular.player.storage.Mix
import de.singular.player.storage.MixStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The folder tree as a head unit browses it.
 *
 * A car asks for a node's children and draws them as rows; there is no scrolling through a
 * thousand, no typing, and no second chance if a row does nothing. So this is deliberately flat in
 * spirit even where the tree is deep: a folder's rows are its actions first, then its subfolders,
 * then its mixes, and every row does something on the first press.
 *
 * ## Media ids carry the whole address
 *
 * A browser hands back only the id of the row that was pressed, so an id has to say what the row
 * *was*. They are prefixed strings: a folder carries its document id, a mix carries its uri, and
 * the three run-starting rows carry the folder they apply to. Nothing is held in memory between the
 * browse and the press, which matters because the two can be minutes and a process death apart.
 *
 * ## Why the actions are rows and not custom commands
 *
 * Media3 has custom commands, and a head unit may or may not surface them. A row is drawn by every
 * browser there is, reads as what it does, and costs one press. For "shuffle everything" at the
 * wheel that is the whole requirement.
 *
 * ## Asking out loud
 *
 * The one way to find a mix without browsing is to say it — see [playbackForSearch], which answers
 * with the same search the library's field uses.
 */
class Library(context: Context, private val store: MixStore) {

    private val res = context.applicationContext

    /** What a browser is handed when it asks what the root is. */
    fun root(): MediaItem = browsable(ROOT, res.getString(R.string.app_name))

    /**
     * The rows under [parentId].
     *
     * Empty for anything unrecognised rather than an error: an id can outlive the tree it described,
     * and a browser that walked into a folder since deleted should find nothing there, not a crash.
     */
    suspend fun children(parentId: String): List<MediaItem> {
        val rootFolder = store.rootFolder()
            ?: return listOf(notice(NEEDS_FOLDER, res.getString(R.string.auto_needs_folder)))
        return when {
            parentId == ROOT -> folderRows(rootFolder, atRoot = true)
            parentId.startsWith(FOLDER) -> {
                val uri = folderUri(parentId.removePrefix(FOLDER)) ?: return emptyList()
                folderRows(uri, atRoot = false)
            }
            else -> emptyList()
        }
    }

    /** One row, for a browser that asks about an id directly rather than walking to it. */
    suspend fun item(mediaId: String): MediaItem? = when {
        mediaId == ROOT -> root()
        mediaId.startsWith(MIX) -> {
            val uri = mediaId.removePrefix(MIX).toUri()
            store.mix(uri)?.let(::playableMix)
        }
        mediaId.startsWith(FOLDER) ->
            folderUri(mediaId.removePrefix(FOLDER))?.let { browsable(mediaId, nameOf(it)) }
        else -> null
    }

    /**
     * What pressing [mediaId] should actually play, in order.
     *
     * One mix for a mix; a whole run for one of the action rows. Empty means the press cannot be
     * honoured — a mix that has since gone, or an id from an older version of the tree — and the
     * caller leaves the transport alone rather than stopping what is already playing.
     */
    suspend fun playbackFor(mediaId: String): List<Mix> = when {
        mediaId.startsWith(MIX) -> listOfNotNull(store.mix(mediaId.removePrefix(MIX).toUri()))
        mediaId == SHUFFLE_ALL -> Queue.shuffled(store.allMixes())
        mediaId.startsWith(PLAY) ->
            folderUri(mediaId.removePrefix(PLAY))?.let { Queue.inOrder(store.list(it).mixes) }
                ?: emptyList()
        else -> emptyList()
    }

    /**
     * What a spoken request — "play Anaconda on Demo Reel" — should play, in order.
     *
     * Answered by the library's own search ([MixStore.search] with a [MixQuery]) over the whole
     * tree, so a mix is found by its file name, its folders or its tags, exactly as typing the same
     * words into the search field would find it. Newest first: a demo is bounced again and again,
     * and the one asked for by name is the latest of them.
     *
     * The first batch of matches is played, not the search's whole answer. The search shows what
     * matches by name at once and only then opens the rest of the tree for its tags, which on a
     * cold start is twenty seconds for a library of four hundred mixes — twenty seconds of silence
     * after asking, which from the driver's seat is a request that was not heard. A mix found by
     * name starts in about a second; one found only by its tags waits until the first such turns
     * up. What the rest of the search would have added does not join the run, which for a request
     * by name is no loss.
     *
     * And a request that finds nothing at all gives up after [SPOKEN_SEARCH_LIMIT_MS] rather than
     * reading the whole tree to be sure: on a cold start that is a quarter of a minute before the
     * answer "nothing", with every request after it queued behind. The price is that on a cold
     * start a mix named only in its tags, in a folder not yet read, can be missed.
     *
     * The assistant often splits what was said into fields as well — title, artist, album — and
     * those are used where they arrive, because the raw sentence carries joining words ("Anaconda
     * by Kreuder") that every word of a [MixQuery] would then have to find.
     *
     * Nothing said at all ("play Demo Reel") is a request for music rather than for a mix, and gets
     * the same run as "shuffle everything". Empty means nothing matched; see the caller for what
     * happens then.
     */
    suspend fun playbackForSearch(query: String, extras: Bundle?): List<Mix> {
        val root = store.rootFolder() ?: return emptyList()
        val fields = listOf(
            MediaStore.EXTRA_MEDIA_TITLE,
            MediaStore.EXTRA_MEDIA_ARTIST,
            MediaStore.EXTRA_MEDIA_ALBUM,
        ).mapNotNull { extras?.getString(it)?.takeIf(String::isNotBlank) }
        val spoken = MixQuery.of(fields.joinToString(" ").ifEmpty { query })
            ?: return Queue.shuffled(store.allMixes())
        Log.d(VOICE_TAG, "search: \"$query\" as \"${fields.joinToString(" ").ifEmpty { query }}\"")
        val started = System.currentTimeMillis()
        val found = withTimeoutOrNull(SPOKEN_SEARCH_LIMIT_MS) {
            store.search(root, spoken::matches).firstOrNull { it.isNotEmpty() }
        }.orEmpty()
        Log.d(VOICE_TAG, "search: ${found.size} found in ${System.currentTimeMillis() - started} ms")
        return found
    }

    /**
     * A folder's rows: what can be started here, then where else to go, then what is here.
     *
     * Actions come first because they are the reason a driver opened the folder at all, and because
     * the top of a list is the only part reachable without looking for long.
     *
     * "Shuffle everything" appears only at the root. Repeating it in every folder would put the one
     * row that ignores where you are standing next to the two that do not.
     */
    private suspend fun folderRows(folder: Uri, atRoot: Boolean): List<MediaItem> {
        val listing = store.list(folder)
        val id = documentId(folder) ?: return emptyList()
        val rows = ArrayList<MediaItem>()
        // One action row per folder, not two. On a car screen every row is a track you cannot see:
        // two of these left barely a couple of mixes visible. Playing the folder is the one worth
        // the space — pressing a mix already plays that mix, and shuffling everything lives at the
        // root, where it costs nothing here.
        if (listing.mixes.isNotEmpty()) {
            rows += action(PLAY + id, res.getString(R.string.auto_play_folder))
        }
        if (atRoot) rows += action(SHUFFLE_ALL, res.getString(R.string.auto_shuffle_all))
        // Most recently touched first, which is the opposite of the library's own name order and
        // right here for one reason: a head unit shows the first few rows and hides the rest behind
        // "More". The folders worth putting there are the ones just recorded into, not the ones
        // whose names happen to start with A.
        listing.folders
            .sortedByDescending { it.modifiedAt }
            .take(LIMIT)
            .forEach { rows += browsable(FOLDER + documentId(it.uri), it.name) }
        listing.mixes.take(LIMIT).forEach { rows += playableMix(it) }
        return rows
    }

    private fun browsable(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    /** A row that starts a run. Playable, but with no uri of its own — see `playbackFor`. */
    private fun action(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build(),
        )
        .build()

    /**
     * A row that says something and does nothing, for the one state the car cannot fix.
     *
     * Android Auto cannot run the system folder picker, so a fresh install reached from the car has
     * no way forward. Saying so is the only honest row to draw; an empty list would read as a
     * broken app rather than an unfinished setup.
     */
    private fun notice(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build(),
        )
        .build()

    private fun playableMix(mix: Mix) = MediaItem.Builder()
        .setMediaId(MIX + mix.uri)
        .setUri(mix.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(mix.displayTitle)
                .setArtist(mix.tags.artist.ifEmpty { null })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()

    private fun documentId(uri: Uri): String? =
        runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    private fun folderUri(documentId: String): Uri? {
        val tree = store.root ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        }.getOrNull()
    }

    private suspend fun nameOf(uri: Uri): String = store.nameOf(uri) ?: ""

    private fun String.toUri(): Uri = Uri.parse(this)

    companion object {
        const val ROOT = "root"

        private const val FOLDER = "folder:"
        private const val MIX = "mix:"
        private const val PLAY = "play:"
        private const val SHUFFLE_ALL = "shuffle-all"
        private const val NEEDS_FOLDER = "needs-folder"

        /**
         * How many folders, and how many mixes, a single node will draw.
         *
         * Android Auto limits what it shows anyway, and a list past this length is not something
         * anyone is reading at the wheel. Truncating quietly is the right failure: the folder is
         * still browsable on the phone, where scrolling is free.
         */
        private const val LIMIT = 100

        /** How long a spoken request searches before answering that nothing matches. */
        private const val SPOKEN_SEARCH_LIMIT_MS = 3_000L
    }
}

/**
 * The log tag for a spoken request's way through the app — Activity, view model, session, search —
 * so `adb logcat -s DemoReelVoice` shows the whole of one request and nothing else. A request said
 * in a car cannot be watched any other way.
 */
internal const val VOICE_TAG = "DemoReelVoice"

/**
 * The log tag for what the player is told and what it does — who sent a play, a pause or a seek,
 * and the reason the player gives for each change. `adb logcat -s DemoReelPlayback`.
 */
internal const val PLAYBACK_TAG = "DemoReelPlayback"
