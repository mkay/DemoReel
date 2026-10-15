// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * The tag formats this app understands, and what a file's extension says it is.
 *
 * Reading is format-blind — [Tags.read] asks the platform, which handles far more than these — so
 * this list is about *writing*. A format that is not here can still be played, listed, favourited
 * and renamed; it simply cannot have its tags edited, and the editor says so rather than offering a
 * Save that would quietly do nothing.
 */
enum class TagFormat(val mime: String, val extensions: List<String>, val label: String) {
    /** ID3v2.4 at the head of the file. */
    MP3("audio/mpeg", listOf("mp3"), "MP3"),

    /** A Vorbis comment block, and a PICTURE block beside it. */
    FLAC("audio/flac", listOf("flac"), "FLAC"),
    ;

    companion object {
        /**
         * What [name] can be written as, or null for a file this app will not re-tag.
         *
         * From the extension rather than from the provider's mime type, which is unreliable for
         * audio in general and calls things `application/octet-stream` often enough that trusting
         * it would refuse files that are perfectly fine.
         */
        fun of(name: String): TagFormat? {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return null
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

/**
 * What a file says about itself.
 *
 * Read through [MediaMetadataRetriever] rather than by parsing containers by hand, because reading
 * is the easy half and the platform already does every format at once — ID3 in an mp3, a Vorbis
 * comment in a FLAC, `ilst` atoms in an m4a, an `ID3 ` chunk bolted onto a WAV. Writing is where a
 * format-by-format parser has to be written, and that is a separate job from this one.
 *
 * Every text field is empty rather than null for absent, so the editor's fields and the defaults
 * that fill them are the same kind of thing throughout and nothing has to keep asking which sort of
 * nothing it is holding. [durationMs] is 0 when unknown.
 */
data class Tags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val genre: String = "",
    val track: String = "",
    /**
     * Free text kept against the mix.
     *
     * **Only [de.singular.player.audio.TagIo] ever fills this.** A listing read through
     * [MediaMetadataRetriever] leaves it empty, because the platform exposes no comment key at all
     * — so a blank comment on a [Tags] from [read] means "not looked", not "not there". Nothing in
     * a listing shows it, and the editor reads the file properly before showing anything.
     */
    val comment: String = "",
    /**
     * Who wrote it, the tempo and the musical key — what a bounce from Logic carries and a demo is
     * often looked up by. Like [comment], only [de.singular.player.audio.TagIo] fills these: the
     * platform's reader has a composer key but no tempo or key, and a listing shows none of them.
     */
    val composer: String = "",
    val bpm: String = "",
    val key: String = "",
    val durationMs: Long = 0,
) {
    /** Whether the file carries any text at all, as against being an untagged bounce. */
    val isEmpty: Boolean
        get() = title.isEmpty() && artist.isEmpty() && album.isEmpty() &&
            albumArtist.isEmpty() && year.isEmpty() && genre.isEmpty() &&
            track.isEmpty() && comment.isEmpty() && composer.isEmpty() && bpm.isEmpty() &&
            key.isEmpty()

    companion object {

        /**
         * Everything [uri] says about itself, or null if it will not open at all.
         *
         * One retriever open per file, which also yields the duration — so a listing pays for the
         * tags and the length together rather than opening each file twice. Call it off the main
         * thread; [de.singular.player.storage.MixStore] caches the answer against size and mtime so
         * that re-entering a folder does not re-read every file.
         */
        fun read(context: Context, uri: Uri): Tags? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                Tags(
                    title = retriever.text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    albumArtist = retriever.text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    // The platform reports a whole date for some containers and a bare year for
                    // others; a demo mix wants the year, and the rest is noise in a four-character
                    // field. Anything that is not four digits is kept whole rather than mangled.
                    year = retriever.text(MediaMetadataRetriever.METADATA_KEY_YEAR)
                        .ifEmpty { retriever.text(MediaMetadataRetriever.METADATA_KEY_DATE) }
                        .let { YEAR.find(it)?.value ?: it },
                    genre = retriever.text(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    // "3/12" is a legitimate value in every one of these formats; only the number
                    // before the slash is the track, and the total belongs to the album rather than
                    // to the file.
                    track = retriever.text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        .substringBefore('/')
                        .trim(),
                    // No comment. The platform has no key for one — its nearest neighbours,
                    // WRITER and AUTHOR, mean the composer and the lyricist — so reading it here
                    // would either invent a value or leave the field permanently blank. Comments
                    // come from [de.singular.player.audio.TagIo], which the editor uses precisely
                    // because it round-trips; see the note on [comment] below.
                    durationMs = retriever.text(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        .toLongOrNull()
                        ?.coerceAtLeast(0)
                        ?: 0,
                )
            } catch (e: Exception) {
                // A file that is half-copied, or not audio at all despite its name. Nothing usable
                // comes out of distinguishing the cases: the caller shows the row without tags.
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

        /**
         * The cover embedded in [uri], as the JPEG or PNG bytes the file itself carries, or null
         * for a file with no picture in it.
         *
         * Kept apart from [read] deliberately. A cover is tens or hundreds of kilobytes and a
         * listing reads tags for every row, so pulling the artwork along with the text would mean a
         * folder of thirty mixes decoding thirty images nobody has asked to look at yet. The player
         * asks for one, for the mix on screen.
         */
        fun picture(context: Context, uri: Uri): ByteArray? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }

        /** Blank and absent are the same thing here — see the class doc. */
        private fun MediaMetadataRetriever.text(key: Int): String =
            runCatching { extractMetadata(key) }.getOrNull()?.trim().orEmpty()

        private val YEAR = Regex("""\d{4}""")
    }
}
