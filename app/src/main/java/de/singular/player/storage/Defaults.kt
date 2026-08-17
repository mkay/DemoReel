// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.storage

import android.content.Context
import androidx.core.content.edit
import de.singular.player.TagDefaults
import de.singular.player.audio.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The tag defaults, kept across launches — see [TagDefaults] for what they are for.
 *
 * The text fields are preferences and go in the preference file. The cover is not: it is a hundred
 * kilobytes of JPEG, which is not what a preference file is for, so it lives as a file in the app's
 * own storage and the preferences only record that it exists.
 *
 * **The stored cover is already encoded for embedding.** The re-encode happens once, when the user
 * picks the image — see [Artwork.encodeForEmbedding] — rather than every time it is stamped onto a
 * file. That matters for a bulk apply: over thirty mixes the naive version would scale and
 * re-compress the same photograph thirty times, and every one of them could produce slightly
 * different bytes.
 */
class Defaults(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("defaults", Context.MODE_PRIVATE)

    /** Where the default cover lives. One image; picking another replaces it. */
    private val coverFile get() = File(appContext.filesDir, COVER_FILE)

    fun load(): TagDefaults = TagDefaults(
        artist = prefs.getString(KEY_ARTIST, "").orEmpty(),
        albumArtist = prefs.getString(KEY_ALBUM_ARTIST, "").orEmpty(),
        album = prefs.getString(KEY_ALBUM, "").orEmpty(),
        genre = prefs.getString(KEY_GENRE, "").orEmpty(),
        year = prefs.getString(KEY_YEAR, "").orEmpty(),
        comment = prefs.getString(KEY_COMMENT, "").orEmpty(),
        // Read off the disk rather than out of a flag: a file that failed to write, or was cleared
        // by the system reclaiming space, would otherwise leave the settings screen offering a cover
        // that is not there.
        hasCover = coverFile.exists() && coverFile.length() > 0,
        titleFromFilename = prefs.getBoolean(KEY_TITLE_FROM_FILENAME, true),
    )

    /** Store the text fields. The cover is [setCover]'s business — [TagDefaults.hasCover] is read. */
    fun save(defaults: TagDefaults) {
        prefs.edit {
            putString(KEY_ARTIST, defaults.artist.trim())
            putString(KEY_ALBUM_ARTIST, defaults.albumArtist.trim())
            putString(KEY_ALBUM, defaults.album.trim())
            putString(KEY_GENRE, defaults.genre.trim())
            putString(KEY_YEAR, defaults.year.trim())
            putString(KEY_COMMENT, defaults.comment.trim())
            putBoolean(KEY_TITLE_FROM_FILENAME, defaults.titleFromFilename)
        }
    }

    /**
     * The default cover, ready to embed, or null if none has been set.
     *
     * Re-described rather than trusted: the dimensions FLAC's picture block wants are not stored
     * beside the file, and a bounds-only decode is cheap enough to do on the way past.
     */
    suspend fun cover(): Artwork? = withContext(Dispatchers.IO) {
        val file = coverFile
        if (!file.exists() || file.length() <= 0) return@withContext null
        runCatching { Artwork.of(file.readBytes()) }.getOrNull()
    }

    /**
     * Replace the default cover with [artwork], or drop it entirely when that is null.
     *
     * [artwork] is expected to have been through [Artwork.encodeForEmbedding] already — this stores
     * bytes, it does not shrink them. Returns whether the store now holds a cover.
     */
    suspend fun setCover(artwork: Artwork?): Boolean = withContext(Dispatchers.IO) {
        val file = coverFile
        if (artwork == null) {
            file.delete()
            return@withContext false
        }
        runCatching { file.writeBytes(artwork.bytes) }.isSuccess
    }

    private companion object {
        const val COVER_FILE = "default-cover.jpg"

        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM_ARTIST = "album_artist"
        const val KEY_ALBUM = "album"
        const val KEY_GENRE = "genre"
        const val KEY_YEAR = "year"
        const val KEY_COMMENT = "comment"
        const val KEY_TITLE_FROM_FILENAME = "title_from_filename"
    }
}
