// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import de.singular.player.R
import de.singular.player.audio.Artwork
import de.singular.player.audio.AudioDecoder
import de.singular.player.audio.AudioTarget
import de.singular.player.audio.Envelope
import de.singular.player.audio.Gain
import de.singular.player.audio.Mp3
import de.singular.player.audio.NormalizeMode
import de.singular.player.audio.Render
import de.singular.player.audio.TagFormat
import de.singular.player.audio.TagIo
import de.singular.player.audio.Tags
import de.singular.player.audio.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * A sub-folder of the granted root, for organising mixes.
 *
 * [modifiedAt] is the folder's own last-modified time, which on a filesystem-backed provider moves
 * when a file is added to it or removed from it — so it answers "where did I last put something?".
 * The library sorts folders by name and ignores it; the car browse tree sorts by it, because a head
 * unit shows only the first few rows and the useful few are the ones just worked in.
 *
 * Zero where the provider does not report one, which sorts such folders last rather than hiding
 * them.
 */
data class Folder(val uri: Uri, val name: String, val modifiedAt: Long = 0)

/**
 * One demo mix on disk, with whatever it says about itself.
 *
 * [tags] is never null — a file that will not open at all gets an empty one — so nothing downstream
 * has to keep asking whether the tags were readable before it can put a row on screen.
 */
data class Mix(
    val uri: Uri,
    /** The filename, extension and all. */
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val tags: Tags = Tags(),
) {
    /** The filename without its extension — what a rename edits and what a bare row shows. */
    val baseName: String get() = name.substringBeforeLast('.')

    /**
     * What to call this mix: its tagged title, falling back to the filename.
     *
     * The tag leads because a mix's title is the thing the person made, and a filename is very
     * often a date and a bounce number. The fallback is what makes an untagged folder — the state
     * every folder starts in — still readable rather than a column of blanks.
     */
    val displayTitle: String get() = tags.title.ifEmpty { baseName }

    val durationMs: Long get() = tags.durationMs

    /** Whether this app can write tags back into this file — see [TagFormat]. */
    val tagFormat: TagFormat? get() = TagFormat.of(name)
}

/** What one folder holds: its sub-folders and its mixes, each already sorted for display. */
data class Listing(
    val folder: Uri,
    val folders: List<Folder> = emptyList(),
    val mixes: List<Mix> = emptyList(),
    /** Why there is nothing to show, if there isn't. Rendered by the UI — see [StorageFailure]. */
    val error: StorageFailure? = null,
)

/**
 * The user's demo-mixes folder, reached through the Storage Access Framework.
 *
 * **This is the app's whole reason for existing.** A player that asks for `READ_MEDIA_AUDIO` gets
 * the device's entire audio library — the ringtones, the podcasts, the voice memos, every track in
 * every other app's download folder — and then spends its life filtering that back down to the
 * handful of files the user actually meant. Demo Reel asks for no media permission at all. The user
 * points at one folder, and that folder is the library: nothing else on the device can appear in it,
 * because nothing else was ever granted.
 *
 * A tree grant, not a path: the folder can be internal storage, an SD card, or something synced from
 * a desktop — the app has no opinion. The grant is persisted so it survives reboots. In return we
 * work in document ids rather than files, which is why folders are passed around as `content://`
 * *document* uris built against the tree.
 *
 * Every call here touches a ContentProvider: call them off the main thread (they already move
 * themselves to [Dispatchers.IO]).
 */
class MixStore(context: Context) {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val prefs = appContext.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /**
     * Tags keyed by identity-of-content, so re-entering a folder does not re-open every file.
     *
     * Reading tags means a [android.media.MediaMetadataRetriever] open per file, which is cheap on
     * its own and adds up over a folder of thirty. Size and mtime together catch every rewrite: a
     * file whose tags changed changed length or changed time, and usually both.
     *
     * Sized for a whole tree rather than a folder, because [search] reads every mix it cannot match
     * by path, and what one search reads is what makes the next one instant. Least recently used goes
     * first when it is full, so the folders being worked in stay. Locked, because a search walking
     * the tree and a folder listing can both be filling it at once.
     */
    private val tagCache = object : LinkedHashMap<String, Tags>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tags>) =
            size > CACHE_LIMIT
    }

    /** The granted root, or null until the user has picked one. */
    val root: Uri?
        get() = prefs.getString(KEY_ROOT, null)
            ?.let(Uri::parse)
            ?.takeIf { hasGrant(it) }

    /**
     * Whether a folder was ever picked, grant or no grant. Together with [root] being null this
     * says the folder is *gone* rather than never chosen, which is a different thing to tell the
     * user.
     */
    val rootWasSet: Boolean
        get() = prefs.getString(KEY_ROOT, null) != null

    /** The root as a document uri — what [list] and the rest want. */
    fun rootFolder(): Uri? = root?.let { documentUri(it, DocumentsContract.getTreeDocumentId(it)) }

    /** Human-readable name of the root, for the breadcrumb and the settings row. */
    suspend fun rootName(): String? = withContext(Dispatchers.IO) {
        rootFolder()?.let { nameOf(it) }
    }

    /**
     * Remember the tree the user just granted, and hold onto the grant across restarts. Returns
     * false if the system refused to persist it, in which case the pick has to be repeated.
     */
    fun setRoot(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching { resolver.takePersistableUriPermission(uri, flags) }.isSuccess
        if (ok) {
            prefs.edit().putString(KEY_ROOT, uri.toString()).apply()
            // A different root is a different set of files; the old folder's tags are not wrong,
            // but keeping them costs memory for rows that will never be drawn again.
            synchronized(tagCache) { tagCache.clear() }
        }
        return ok
    }

    /** Whether we still hold a persisted grant on [uri] — the user can revoke it in Settings. */
    private fun hasGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    /** Everything in [folder]: sub-folders first, then mixes, newest first. */
    suspend fun list(folder: Uri): Listing = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext Listing(folder, error = StorageFailure.NO_FOLDER_CHOSEN)
        val rows = children(tree, folder).getOrElse {
            val failure = (it as? StorageException)?.failure ?: StorageFailure.FOLDER_UNREADABLE
            return@withContext Listing(folder, error = failure)
        }
        val folders = ArrayList<Folder>()
        val mixes = ArrayList<Mix>()
        rows.forEach { row ->
            if (row.isFolder) {
                folders += Folder(row.uri, row.name, row.modified)
            } else if (row.isAudio) {
                mixes += row.toMix()
            }
        }
        Listing(
            folder = folder,
            folders = folders.sortedBy { f -> f.name.lowercase() },
            mixes = mixes.sortedByDescending { m -> m.modifiedAt },
        )
    }

    /**
     * Every mix under the root, folders and subfolders alike, for "shuffle everything".
     *
     * ## Why this does not simply walk [list]
     *
     * [list] reads the tags of every file it returns, through a [Tags.read] that opens the file. For
     * one folder on screen that is the right trade — the library shows titles and durations. For a
     * walk of the whole tree it is the wrong one by an order of magnitude: opening several hundred
     * files to decide what order to play them in, when the only thing needed is a uri.
     *
     * So this queries the same columns and builds each [Mix] with empty [Tags]. Nothing is lost:
     * [Mix.displayTitle] already falls back to the filename, and once a mix is actually playing the
     * player reads the real metadata off the file itself — which is where the notification's title
     * and artwork come from.
     *
     * ## Breadth-first, and bounded
     *
     * Breadth-first so a deep tree cannot recurse the stack away, and bounded on both folders
     * visited and mixes collected so that a root accidentally pointed at something enormous fails
     * slowly rather than never. Both limits are far above what this app is for.
     *
     * Not cached. This is reached from a play action rather than a browse, where a pause of a second
     * or two is the ordinary cost of starting something; if the browse tree ever needs the same walk
     * it will need a cache, and that is the point to add one.
     */
    suspend fun allMixes(): List<Mix> = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext emptyList()
        val start = rootFolder() ?: return@withContext emptyList()
        val found = ArrayList<Mix>()
        val pending = ArrayDeque<Uri>()
        pending += start
        var folders = 0
        while (pending.isNotEmpty() && folders < FOLDER_WALK_LIMIT && found.size < MIX_WALK_LIMIT) {
            // A walk of a large tree is exactly the kind of thing someone backs out of.
            currentCoroutineContext().ensureActive()
            folders++
            children(tree, pending.removeFirst()).getOrNull()?.forEach { row ->
                if (row.isFolder) {
                    pending += row.uri
                } else if (row.isAudio) {
                    found += Mix(row.uri, row.name, row.size, row.modified, Tags())
                }
            }
        }
        found
    }

    /**
     * Every mix in [folder] or any folder beneath it that [matches] accepts, newest first — as a
     * flow of the answer so far, complete when the flow is.
     *
     * ## Two passes, because a mix is found by its tags as well as its path
     *
     * The walk is one provider query per folder, and the names and keys come with it, so the walk
     * itself is cheap. Tags are not: they are a file open each. So the first pass asks [matches]
     * with the key alone (tags null) and, where this store already has a file's tags, with those too
     * — and says what that found before opening anything. Only the mixes it could not decide are
     * then opened, in the second pass, and the answer grows as they turn up.
     *
     * The first search over a large tree is the slow one: every file not matched by its path has to
     * be read once. What it reads goes into [tagCache], so the words typed after it, and the next
     * search, start from the first pass with most of the tree already known. A search cancelled by
     * the next keystroke has not wasted what it read.
     *
     * Bounded by the same limits as [allMixes]. A folder that will not list is skipped rather than
     * failing the search: the rest of the tree still answers.
     */
    fun search(folder: Uri, matches: (key: String, tags: Tags?) -> Boolean): Flow<List<Mix>> = flow {
        val tree = root ?: run {
            emit(emptyList())
            return@flow
        }
        val found = ArrayList<Mix>()
        val unread = ArrayList<Pair<Child, String>>()
        val pending = ArrayDeque(listOf(folder))
        var folders = 0
        var seen = 0
        while (pending.isNotEmpty() && folders < FOLDER_WALK_LIMIT && seen < MIX_WALK_LIMIT) {
            currentCoroutineContext().ensureActive()
            folders++
            children(tree, pending.removeFirst()).getOrNull()?.forEach { row ->
                if (row.isFolder) {
                    pending += row.uri
                } else if (row.isAudio) {
                    seen++
                    val key = Favourites.keyFor(tree, row.uri) ?: return@forEach
                    val known = cachedTags(row.uri, row.size, row.modified)
                    when {
                        matches(key, null) || (known != null && matches(key, known)) ->
                            found += row.toMix()

                        known == null -> unread += row to key
                    }
                }
            }
        }
        emit(found.sortedByDescending { it.modifiedAt })

        // Results are let out in batches rather than one emission per file: every emission redraws
        // the list, and a list that re-sorts itself thirty times a second cannot be read.
        var shownAt = System.nanoTime()
        var news = false
        unread.forEach { (row, key) ->
            currentCoroutineContext().ensureActive()
            val mix = row.toMix()
            if (matches(key, mix.tags)) {
                found += mix
                news = true
            }
            if (news && System.nanoTime() - shownAt >= SEARCH_BATCH_NS) {
                emit(found.sortedByDescending { it.modifiedAt })
                shownAt = System.nanoTime()
                news = false
            }
        }
        if (news) emit(found.sortedByDescending { it.modifiedAt })
    }.flowOn(Dispatchers.IO)

    /** One entry in a folder, as the provider describes it, before anything is opened. */
    private inner class Child(
        val uri: Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
    ) {
        val isFolder get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val isAudio get() = !isFolder && this@MixStore.isAudio(name, mime)

        /** The mix, tags and all — read from the file unless [tagCache] already has them. */
        fun toMix() = Mix(uri, name, size, modified, tags(uri, size, modified))
    }

    /**
     * What sits directly in [folder], dotfiles left out, or the [StorageFailure] that stopped it —
     * the one query [list], [allMixes] and [search] all make of a folder.
     */
    private fun children(tree: Uri, folder: Uri): Result<List<Child>> {
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(folder),
            )
        }.getOrNull() ?: return Result.failure(StorageException(StorageFailure.FOLDER_UNREACHABLE))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = runCatching { resolver.query(children, columns, null, null, null) }.getOrNull()
            ?: return Result.failure(StorageException(StorageFailure.FOLDER_UNREADABLE))
        val rows = ArrayList<Child>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                if (isHidden(name)) continue
                rows += Child(
                    uri = documentUri(tree, id),
                    name = name,
                    mime = it.getString(2) ?: "",
                    size = if (it.isNull(3)) 0L else it.getLong(3),
                    modified = if (it.isNull(4)) 0L else it.getLong(4),
                )
            }
        }
        return Result.success(rows)
    }

    /**
     * The mix at [key] — a path under the granted root, as [Favourites] stores them — or null if
     * nothing is there any more.
     *
     * One query per file rather than a walk of the tree. A favourite can be anywhere in the folder
     * tree, and listing every folder to find a handful of them would cost a provider round trip per
     * folder; this costs one per favourite, which for the tens a person keeps is the cheaper side of
     * the trade by a wide margin.
     *
     * Null is the ordinary answer, not an error: a mix favourited months ago may since have been
     * renamed or deleted from a desktop, and the caller uses that to drop it from the index.
     */
    suspend fun mixAt(key: String): Mix? = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext null
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return@withContext null
        val uri = documentUri(tree, if (key.isEmpty()) rootId else "$rootId/$key")
        describe(uri)
    }

    /** Create a sub-folder under [parent]. Returns its uri, or null if the provider refused. */
    suspend fun createFolder(parent: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.createDocument(
                resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name.trim(),
            )
        }.getOrNull()
    }

    /**
     * Rename a mix or a folder. Returns the new uri (some providers mint one).
     *
     * What the user types is a *title*, not a filename: the rename field never shows the extension,
     * because renaming is not a way to change what a file is. Passing the typed name straight to
     * the provider would take the extension off the file, leaving a mix that nothing will open and
     * that this app no longer lists as audio at all — so it is put back here. Folders, which have
     * no extension to keep, get exactly what was typed.
     */
    suspend fun rename(uri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        val wanted = newName.trim()
        if (wanted.isEmpty()) return@withContext null
        runCatching {
            DocumentsContract.renameDocument(resolver, uri, keepExtension(uri, wanted))
        }.getOrNull()
    }

    /** [newName] with the document's own extension restored — see [rename]. */
    private fun keepExtension(uri: Uri, newName: String): String {
        val row = runCatching {
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(uri, columns, null, null, null)?.use {
                if (it.moveToFirst()) (it.getString(0) ?: "") to (it.getString(1) ?: "") else null
            }
        }.getOrNull() ?: return newName
        val (oldName, mime) = row
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) return newName
        val ext = oldName.substringAfterLast('.', "")
        // A typed name that already ends in the file's own extension is left alone; anything else
        // the user typed after a dot is part of the title ("Mix 2.1"), not a new file type.
        if (ext.isEmpty() || newName.endsWith(".$ext", ignoreCase = true)) return newName
        return "$newName.$ext"
    }

    /** Just the sub-folders of [folder] — for aiming a move, where the mixes are not the point. */
    suspend fun folders(folder: Uri): List<Folder> = withContext(Dispatchers.IO) {
        val tree = root ?: return@withContext emptyList()
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(folder),
            )
        }.getOrNull() ?: return@withContext emptyList()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = runCatching { resolver.query(children, columns, null, null, null) }.getOrNull()
            ?: return@withContext emptyList()
        val found = ArrayList<Folder>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                if (isHidden(name)) continue
                if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    found += Folder(documentUri(tree, id), name)
                }
            }
        }
        found.sortedBy { f -> f.name.lowercase() }
    }

    /**
     * Move [uri] into [destination], and say where it ended up.
     *
     * Asks the provider to move it first, which is instant and keeps the file's own bytes and
     * timestamps. That needs the folder it is coming *out* of, which the Storage Access Framework
     * does not hand back for a document — [parentOf] works it out of the document id, which for the
     * providers this app meets is a path. When either of those does not hold, the mix is copied
     * across and the original deleted, which costs a read and a write but always works.
     */
    suspend fun move(uri: Uri, destination: Uri): Result<Uri> = withContext(Dispatchers.IO) {
        val parent = parentOf(uri)
        if (parent == destination) return@withContext Result.success(uri)
        if (parent != null) {
            val moved = runCatching {
                DocumentsContract.moveDocument(resolver, uri, parent, destination)
            }.getOrNull()
            if (moved != null) return@withContext Result.success(moved)
        }
        copyInto(uri, destination)
    }

    /**
     * The long way round: write the document into [destination] byte for byte, then delete the
     * original.
     *
     * Files only — a folder would mean walking everything under it, and a move that has to fall
     * back this far is better refused than half done. Nothing is deleted until the copy is whole,
     * and a delete that fails takes the copy back out again rather than leaving the mix in two
     * places for the user to work out.
     */
    private fun copyInto(uri: Uri, destination: Uri): Result<Uri> {
        val row = runCatching {
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            resolver.query(uri, columns, null, null, null)?.use {
                if (it.moveToFirst()) (it.getString(0) ?: "") to (it.getString(1) ?: "") else null
            }
        }.getOrNull() ?: return Result.failure(StorageException(StorageFailure.CANNOT_READ_FILE))
        val (name, mime) = row
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            return Result.failure(StorageException(StorageFailure.FOLDERS_CANNOT_MOVE))
        }

        val copy = runCatching {
            DocumentsContract.createDocument(resolver, destination, mime.ifEmpty { MIME_AUDIO }, name)
        }.getOrNull() ?: return Result.failure(
            StorageException(StorageFailure.CANNOT_WRITE_INTO_FOLDER),
        )

        val written = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                resolver.openOutputStream(copy, "w")?.use { input.copyTo(it) }
                    ?: throw StorageException(StorageFailure.COPY_NOT_WRITTEN)
            } ?: throw StorageException(StorageFailure.CANNOT_READ_FILE)
        }
        if (written.isFailure) {
            runCatching { DocumentsContract.deleteDocument(resolver, copy) }
            return Result.failure(written.exceptionOrNull()!!)
        }

        val gone = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        if (!gone) {
            runCatching { DocumentsContract.deleteDocument(resolver, copy) }
            return Result.failure(StorageException(StorageFailure.ORIGINAL_NOT_REMOVED))
        }
        return Result.success(copy)
    }

    /**
     * Copy a file this app does not own into [destination], leaving the original untouched.
     *
     * The other half of [handedOver]: a mix another app passed in lives only as long as the grant
     * on its uri, and this is how the user keeps it. Not [copyInto], which is a move's fallback and
     * ends by deleting what it copied — a file in someone else's storage is not this app's to
     * remove, and the sender rarely grants the write that would take.
     *
     * [name] comes from the caller rather than from a query, because the source may be no kind of
     * document at all — a `file://` uri or a provider that answers no columns — and [handedOver]
     * has already worked out the best name available for exactly that case. Where the destination
     * already holds that name the provider mints a distinct one; the copy is never written over a
     * mix that is already in the library.
     *
     * Returns the mix as it now sits under the grant, read back from disk so the caller can open
     * the copy rather than go on holding the uri that is about to expire.
     */
    suspend fun importInto(source: Uri, name: String, destination: Uri): Result<Mix> =
        withContext(Dispatchers.IO) {
            val mime = resolver.getType(source)
                ?.takeUnless { it == DocumentsContract.Document.MIME_TYPE_DIR }
                ?: MIME_AUDIO
            val copy = runCatching {
                DocumentsContract.createDocument(resolver, destination, mime, name)
            }.getOrNull() ?: return@withContext Result.failure(
                StorageException(StorageFailure.CANNOT_WRITE_INTO_FOLDER),
            )

            val written = runCatching {
                resolver.openInputStream(source)?.use { input ->
                    openTruncating(copy).use { input.copyTo(it, BLOCK) }
                } ?: throw StorageException(StorageFailure.CANNOT_READ_FILE)
            }
            if (written.isFailure) {
                // Half a file in the library is worse than none: it would list, play to the break
                // and look like a bad recording rather than a failed copy.
                runCatching { DocumentsContract.deleteDocument(resolver, copy) }
                return@withContext Result.failure(written.exceptionOrNull()!!)
            }

            forget(copy)
            describe(copy)?.let { Result.success(it) }
                ?: Result.failure(StorageException(StorageFailure.COPY_NOT_WRITTEN))
        }

    /**
     * The folder [uri] sits in, worked out from its document id — a path under the granted tree for
     * the providers this app meets. Null when the id is not path-shaped or leads outside the grant,
     * which is [move]'s cue to take the long way round.
     */
    private fun parentOf(uri: Uri): Uri? {
        val tree = root ?: return null
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if (!id.startsWith("$rootId/")) return null
        val parentId = id.substringBeforeLast('/', "")
        if (parentId != rootId && !parentId.startsWith("$rootId/")) return null
        return documentUri(tree, parentId)
    }

    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    /**
     * The envelope of [mix], for drawing it. Null only when the file has no audio this device can
     * decode at all — the player then says so rather than drawing a flat line.
     *
     * Cancelling the caller stops the decode between buffers rather than leaving it running for a
     * screen nobody is looking at.
     */
    suspend fun waveform(mix: Mix): Envelope? = withContext(Dispatchers.IO) {
        AudioDecoder.envelope(appContext, mix.uri, Waveform.DETAIL) { isActive }
    }

    /** The cover embedded in [mix], or null if it carries none. */
    suspend fun cover(mix: Mix): ByteArray? = withContext(Dispatchers.IO) {
        Tags.picture(appContext, mix.uri)
    }

    // ---- tagging --------------------------------------------------------------------------------

    /**
     * Exactly what [mix] carries, parsed out of the container rather than asked of the platform —
     * what the editor opens onto. See [TagIo] for why the two readers exist.
     *
     * Null for a format this app cannot re-tag, and for a file that is not what its name says.
     */
    suspend fun readTags(mix: Mix): TagIo.Content? = withContext(Dispatchers.IO) {
        val format = mix.tagFormat ?: return@withContext null
        runCatching {
            resolver.openInputStream(mix.uri)?.buffered(BLOCK)?.use { TagIo.read(format, it) }
        }.getOrNull()
    }

    /**
     * Write [tags] and [cover] into [mix], and hand back the mix as it now stands.
     *
     * **Through the cache, always.** Every tag format in use puts its text block in front of the
     * audio, so changing a field moves every byte after it and there is no such thing as a partial
     * rewrite: the whole file is rebuilt. Doing that straight onto the original would mean a failed
     * read, a full storage volume or a killed process leaves the user with half a file and no
     * original — for a change of artist name. So the new file is built in the cache directory first
     * and only replaces the original once it is whole on disk.
     *
     * The audio is copied byte for byte and never decoded; see [TagIo.write]. Whatever else the
     * file carries is kept, or [carry] instead when it is given — see there.
     */
    suspend fun writeTags(
        mix: Mix,
        tags: Tags,
        cover: Artwork?,
        carry: TagIo.Kept? = null,
    ): Result<Mix> = withContext(Dispatchers.IO) {
        val format = mix.tagFormat
            ?: return@withContext Result.failure(
                StorageException(StorageFailure.TAGS_UNSUPPORTED_FORMAT),
            )

        val scratch = File(appContext.cacheDir, "tags.tmp")
        val built = runCatching {
            scratch.outputStream().buffered(BLOCK).use { out ->
                resolver.openInputStream(mix.uri)?.buffered(BLOCK)?.use { input ->
                    TagIo.write(format, input, out, tags, cover, carry)
                } ?: throw StorageException(StorageFailure.CANNOT_READ_FILE)
            }
        }
        // `false` rather than an exception means the file is not the format its name claims. The
        // half-built result is discarded either way; nothing has touched the original yet.
        if (built.getOrNull() == false) {
            scratch.delete()
            return@withContext Result.failure(StorageException(StorageFailure.TAGS_FILE_MALFORMED))
        }
        built.exceptionOrNull()?.let {
            scratch.delete()
            return@withContext Result.failure(it)
        }

        val replaced = runCatching {
            scratch.inputStream().use { src ->
                openTruncating(mix.uri).use { dest -> src.copyTo(dest, BLOCK) }
            }
        }
        scratch.delete()
        replaced.exceptionOrNull()?.let { return@withContext Result.failure(it) }

        // The file on disk is a different length and a different age now, so anything remembered
        // about the old one is stale by definition — including its cover.
        forget(mix.uri)
        val written = describe(mix.uri)
            ?: return@withContext Result.failure(StorageException(StorageFailure.TAGS_NOT_WRITTEN))
        Result.success(written)
    }

    // ---- rendering ------------------------------------------------------------------------------

    /**
     * The result of a normalise or a convert: the file it left behind, and how much it lifted.
     *
     * [gainDb] is 0 when nothing was lifted — a convert, or a mix that was already loud enough. The
     * two are told apart by [mix]: a convert always produces a new file, and a normalise that found
     * nothing to do hands back the mix it was given, untouched.
     */
    data class Rendered(val mix: Mix, val gainDb: Float)

    /**
     * Lift [mix] to full level and write the result as [target].
     *
     * With [overwrite] the original is replaced; otherwise a copy is written beside it, named for
     * what was done to it. A mix that is already loud enough is left alone either way — rewriting a
     * whole file, or making a second one, for a fraction of a dB is all cost and no difference.
     */
    suspend fun normalize(
        mix: Mix,
        mode: NormalizeMode,
        target: AudioTarget,
        overwrite: Boolean,
        into: Uri? = null,
    ): Result<Rendered> = render(mix, target, mode, overwrite, into)

    /** Write [mix] out as [target], beside it, at the level it already has. */
    suspend fun convert(
        mix: Mix,
        target: AudioTarget,
        into: Uri? = null,
    ): Result<Rendered> = render(mix, target, mode = null, overwrite = false, into = into)

    /**
     * The one path both of them take: decode, measure, encode, put the tags back.
     *
     * **The original is never opened for writing until the new audio exists.** An overwrite encodes
     * into the cache first and copies it over afterwards, for the same reason a re-tag does (see
     * [writeTags]): a decode that fails half way, a full volume or a killed process would otherwise
     * leave a mix that is neither the old file nor the new one. A copy is deleted again if anything
     * goes wrong, rather than being left in the folder for someone to work out.
     */
    private suspend fun render(
        mix: Mix,
        target: AudioTarget,
        mode: NormalizeMode?,
        overwrite: Boolean,
        into: Uri?,
    ): Result<Rendered> = withContext(Dispatchers.IO) {
        if (target == AudioTarget.MP3 && !Mp3.available) {
            return@withContext Result.failure(StorageException(StorageFailure.NO_ENCODER))
        }
        // Read before anything is written: an overwrite replaces the file these come from, and a
        // copy wants them from the original rather than from what the encoder just wrote.
        val carried = readableTags(mix)

        val pcm = File(appContext.cacheDir, "render.pcm")
        val decoded = runCatching { Render.decode(appContext, mix.uri, pcm) }
            .getOrNull()
            ?: run {
                pcm.delete()
                return@withContext Result.failure(StorageException(StorageFailure.NO_DECODER))
            }

        val gain = mode?.let { Gain.linearFor(it, decoded.peak, decoded.rms) } ?: 1f
        val gainDb = Gain.linearToDb(gain)
        if (mode != null && gainDb < Gain.MIN_USEFUL_BOOST_DB) {
            pcm.delete()
            return@withContext Result.success(Rendered(mix, 0f))
        }
        val softClip = mode != null && Gain.needsSoftClip(gain, decoded.peak)

        val encoded = File(appContext.cacheDir, "render.out")
        val wrote = runCatching {
            encoded.outputStream().buffered(BLOCK).use { out ->
                Render.encode(decoded, out, target, gain, softClip, appContext.cacheDir)
            }
        }
        pcm.delete()
        wrote.exceptionOrNull()?.let {
            encoded.delete()
            return@withContext Result.failure(it)
        }

        val destination = if (overwrite) {
            mix.uri
        } else {
            val folder = into ?: parentOf(mix.uri) ?: run {
                encoded.delete()
                return@withContext Result.failure(
                    StorageException(StorageFailure.CANNOT_WRITE_INTO_FOLDER),
                )
            }
            val name = renderedName(mix, target, normalised = mode != null)
            runCatching {
                DocumentsContract.createDocument(resolver, folder, target.mime, name)
            }.getOrNull() ?: run {
                encoded.delete()
                return@withContext Result.failure(
                    StorageException(StorageFailure.CANNOT_WRITE_INTO_FOLDER),
                )
            }
        }

        val placed = runCatching {
            encoded.inputStream().use { src ->
                openTruncating(destination).use { dest -> src.copyTo(dest, BLOCK) }
            }
        }
        encoded.delete()
        placed.exceptionOrNull()?.let {
            if (!overwrite) runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            return@withContext Result.failure(it)
        }

        forget(destination)
        val written = describe(destination)
            ?: return@withContext Result.failure(StorageException(StorageFailure.COPY_NOT_WRITTEN))

        // Tags last, and never fatal. The audio is on disk and correct at this point; a file that
        // arrived without its artist name is a nuisance, while throwing away the encode over it
        // would be a loss. WAV simply has nowhere to put them. What the original carried beyond
        // the editor's fields goes too, less what was measured on the audio this replaced.
        val tagged = carried
            ?.takeIf { written.tagFormat != null }
            ?.let {
                writeTags(written, it.tags, it.cover?.let(Artwork::of), it.kept?.forNewAudio())
                    .getOrNull()
            }

        Result.success(Rendered(tagged ?: written, if (mode == null) 0f else gainDb))
    }

    /**
     * What [mix] says about itself, however it has to be asked.
     *
     * The exact reader for a format this app parses, so a comment survives a convert; the platform's
     * reader for everything else, which is lossy but is the only thing that will open an m4a.
     */
    private fun readableTags(mix: Mix): TagIo.Content? {
        mix.tagFormat?.let { format ->
            runCatching {
                resolver.openInputStream(mix.uri)?.buffered(BLOCK)?.use { TagIo.read(format, it) }
            }.getOrNull()?.let { return it }
        }
        val tags = Tags.read(appContext, mix.uri) ?: return null
        return TagIo.Content(tags, Tags.picture(appContext, mix.uri))
    }

    /**
     * What to call the file a render leaves behind.
     *
     * A normalise says so in the name — the folder ends up holding both versions, and "mix.flac"
     * next to "mix.flac" distinguished only by loudness is a folder nobody can work in. A convert
     * keeps the name it had: the extension is already the difference, and it is the whole point.
     */
    private fun renderedName(mix: Mix, target: AudioTarget, normalised: Boolean): String {
        val suffix = if (normalised) " " + appContext.getString(R.string.file_suffix_normalised) else ""
        return "${mix.baseName}$suffix.${target.extension}"
    }

    /**
     * Truncating write.
     *
     * A re-tag almost always changes the file's length, and providers disagree about whether plain
     * `"w"` empties the document first. Without the truncate, shortening a file — dropping a cover,
     * say — would leave the tail of the old one behind the new content, which for MP3 is a stretch
     * of audio that plays after the end and for FLAC is a decode error.
     */
    private fun openTruncating(uri: Uri): OutputStream =
        runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw StorageException(StorageFailure.CANNOT_WRITE_FILE)

    /** Re-read one mix by uri — after a rename, or after anything else has moved under it. */
    suspend fun mix(uri: Uri): Mix? = withContext(Dispatchers.IO) { describe(uri) }

    /** Re-read one mix's row, tags and all. */
    private fun describe(uri: Uri): Mix? = runCatching {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, columns, null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            val name = it.getString(0) ?: return@use null
            val mime = it.getString(1) ?: ""
            if (!isAudio(name, mime)) return@use null
            val size = if (it.isNull(2)) 0L else it.getLong(2)
            val modified = if (it.isNull(3)) 0L else it.getLong(3)
            Mix(uri, name, size, modified, tags(uri, size, modified))
        }
    }.getOrNull()

    /**
     * A mix handed over from another app — a share, or an "open with".
     *
     * Deliberately not [describe], which asks for `DocumentsContract` columns. Those exist on a
     * document provider and need not exist anywhere else, and the file arriving here comes from
     * whatever app sent it: a file manager, a recorder, a messenger, each with its own provider.
     * `OpenableColumns` is the pair every openable content uri is required to answer, so that is
     * what this asks for, with the mime type taken from the resolver rather than a column.
     *
     * This reaches outside the granted folder, and is the only thing in the app that does. It is
     * not a wider grant: the sender attaches a read permission to the one uri it handed over, for
     * as long as this task lives. Nothing is copied, nothing is indexed, and the mix cannot be
     * favourited or resumed later, because [Favourites] keys are paths under the root and a file
     * from elsewhere has none.
     */
    suspend fun handedOver(uri: Uri): Mix? = withContext(Dispatchers.IO) {
        val fromFile = uri.scheme == "file"
        val mime = resolver.getType(uri) ?: ""
        var name = if (fromFile) uri.lastPathSegment.orEmpty() else ""
        var size = 0L
        if (!fromFile) {
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null,
                )?.use {
                    if (it.moveToFirst()) {
                        name = it.getString(0) ?: ""
                        size = if (it.isNull(1)) 0L else it.getLong(1)
                    }
                }
            }
        }
        // A provider that answers nothing still leaves the uri's own tail, which for almost every
        // sender is the filename — enough to name the mix and to tell the format from.
        if (name.isEmpty()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        if (name.isEmpty() || !isAudio(name, mime)) return@withContext null
        Mix(uri, name, size, 0L, tags(uri, size, 0L))
    }

    /** What [uri] says about itself, remembered against its size and mtime — see [tagCache]. */
    private fun tags(uri: Uri, size: Long, modified: Long): Tags {
        cachedTags(uri, size, modified)?.let { return it }
        // Read outside the lock: it is a file open, and a listing should not wait on a search's.
        val read = Tags.read(appContext, uri) ?: Tags()
        synchronized(tagCache) { tagCache[cacheKey(uri, size, modified)] = read }
        return read
    }

    /** What [tagCache] already knows about [uri], without going to the file for it. */
    private fun cachedTags(uri: Uri, size: Long, modified: Long): Tags? =
        synchronized(tagCache) { tagCache[cacheKey(uri, size, modified)] }

    private fun cacheKey(uri: Uri, size: Long, modified: Long) = "$uri|$size|$modified"

    /**
     * Forget what [uri] said, so the next read goes back to the file.
     *
     * For the tag editor: a rewrite changes the size and the mtime, so the cache key changes with
     * it and a stale entry could not be returned anyway — but the *old* entry would then sit there
     * for the rest of the session describing a file that no longer exists in that form. Dropping it
     * on write keeps the cache to the files that are actually on disk.
     */
    fun forget(uri: Uri) {
        val prefix = "$uri|"
        synchronized(tagCache) { tagCache.keys.removeAll { it.startsWith(prefix) } }
    }

    /** Display name of any document, or null if it has gone. */
    suspend fun nameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            resolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    private fun documentUri(tree: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

    /**
     * Whether this row is a file the app should list.
     *
     * Providers are inconsistent about audio mime types — a WAV is regularly reported as
     * `application/octet-stream` — so the extension gets a vote. The list is deliberately wider than
     * what the tag editor can write: a file this app cannot re-tag can still be played, favourited
     * and organised, and hiding it would make the library disagree with the folder it is showing.
     */
    private fun isAudio(name: String, mime: String): Boolean =
        mime.startsWith("audio/") ||
            AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    /**
     * Dotfiles are somebody else's bookkeeping — Syncthing's `.stfolder`, a desktop's `.DS_Store`
     * — and a music folder synced from a computer collects them. Nothing this app shows or plays
     * starts with a dot, so the library skips them the way a file manager does.
     */
    private fun isHidden(name: String): Boolean = name.startsWith(".")

    private companion object {
        const val KEY_ROOT = "root_tree_uri"

        /** Only ever used as a fallback when a provider will not say what a file is. */
        const val MIME_AUDIO = "audio/mpeg"

        /**
         * How many files' tags [tagCache] keeps. A [Tags] is a handful of short strings, so this is
         * a few megabytes at most — the price of a second search over a tree of a few thousand
         * mixes not opening any of them again.
         */
        const val CACHE_LIMIT = 4_096

        /**
         * Ceilings for [allMixes] and [search], so a root pointed somewhere enormous fails rather
         * than hangs.
         */
        const val FOLDER_WALK_LIMIT = 2_000
        const val MIX_WALK_LIMIT = 10_000

        /** How often a search still reading files shows what it has found so far. */
        const val SEARCH_BATCH_NS = 300_000_000L

        /** Read/write block for the whole-file passes a re-tag makes. */
        const val BLOCK = 64 * 1024

        val AUDIO_EXTENSIONS = listOf(".mp3", ".flac", ".m4a", ".wav", ".ogg", ".opus", ".aac")
    }
}
