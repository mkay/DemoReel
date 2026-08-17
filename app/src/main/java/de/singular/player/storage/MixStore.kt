// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import de.singular.player.audio.Artwork
import de.singular.player.audio.AudioDecoder
import de.singular.player.audio.Envelope
import de.singular.player.audio.TagFormat
import de.singular.player.audio.TagIo
import de.singular.player.audio.Tags
import de.singular.player.audio.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** A sub-folder of the granted root, for organising mixes. */
data class Folder(val uri: Uri, val name: String)

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
     */
    private val tagCache = HashMap<String, Tags>()

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
            tagCache.clear()
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
        val children = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getDocumentId(folder),
            )
        }.getOrNull() ?: return@withContext Listing(folder, error = StorageFailure.FOLDER_UNREACHABLE)

        val folders = ArrayList<Folder>()
        val mixes = ArrayList<Mix>()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = runCatching { resolver.query(children, columns, null, null, null) }.getOrNull()
            ?: return@withContext Listing(folder, error = StorageFailure.FOLDER_UNREADABLE)

        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2) ?: ""
                val size = if (it.isNull(3)) 0L else it.getLong(3)
                val modified = if (it.isNull(4)) 0L else it.getLong(4)
                val uri = documentUri(tree, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders += Folder(uri, name)
                } else if (isAudio(name, mime)) {
                    mixes += Mix(uri, name, size, modified, tags(uri, size, modified))
                }
            }
        }
        Listing(
            folder = folder,
            folders = folders.sortedBy { f -> f.name.lowercase() },
            mixes = mixes.sortedByDescending { m -> m.modifiedAt },
        )
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
     * The audio is copied byte for byte and never decoded; see [TagIo.write].
     */
    suspend fun writeTags(
        mix: Mix,
        tags: Tags,
        cover: Artwork?,
    ): Result<Mix> = withContext(Dispatchers.IO) {
        val format = mix.tagFormat
            ?: return@withContext Result.failure(
                StorageException(StorageFailure.TAGS_UNSUPPORTED_FORMAT),
            )

        val scratch = File(appContext.cacheDir, "tags.tmp")
        val built = runCatching {
            scratch.outputStream().buffered(BLOCK).use { out ->
                resolver.openInputStream(mix.uri)?.buffered(BLOCK)?.use { input ->
                    TagIo.write(format, input, out, tags, cover)
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

    /** What [uri] says about itself, remembered against its size and mtime — see [tagCache]. */
    private fun tags(uri: Uri, size: Long, modified: Long): Tags {
        val key = "$uri|$size|$modified"
        tagCache[key]?.let { return it }
        val read = Tags.read(appContext, uri) ?: Tags()
        if (tagCache.size > CACHE_LIMIT) tagCache.clear()
        tagCache[key] = read
        return read
    }

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
        tagCache.keys.filter { it.startsWith(prefix) }.forEach(tagCache::remove)
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

    private companion object {
        const val KEY_ROOT = "root_tree_uri"

        /** Only ever used as a fallback when a provider will not say what a file is. */
        const val MIME_AUDIO = "audio/mpeg"

        const val CACHE_LIMIT = 512

        /** Read/write block for the whole-file passes a re-tag makes. */
        const val BLOCK = 64 * 1024

        val AUDIO_EXTENSIONS = listOf(".mp3", ".flac", ".m4a", ".wav", ".ogg", ".opus", ".aac")
    }
}
