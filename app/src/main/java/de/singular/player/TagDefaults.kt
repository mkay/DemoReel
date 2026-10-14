// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player

import de.singular.player.audio.Artwork
import de.singular.player.audio.Tags

/**
 * The fields to stamp onto a mix that has none — set once, applied to as many files as you like.
 *
 * This is the reason the app exists rather than being a player. A folder of bounces arrives with no
 * metadata at all: the artist is the same person every time, the album is the same project, the year
 * is this year, and the cover is the one image the project has. Typing that into thirty files one at
 * a time is what nobody does, so thirty files stay untagged and every one of them shows up as
 * "bounce_04" wherever it is played.
 *
 * There is no default **title** and no default **track number**, and there cannot be: they are the
 * two fields that differ per file by definition. [titleFromFilename] is what stands in for the
 * first — see there.
 *
 * The cover is not held here. It is bytes rather than a preference, so it lives in a file — see
 * [de.singular.player.storage.Defaults]. [hasCover] is the flag the settings screen reads to know
 * whether there is one without loading it.
 */
data class TagDefaults(
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val comment: String = "",
    val hasCover: Boolean = false,
    /**
     * Where a mix has no title, use its filename.
     *
     * On by default, and it is the single most useful thing here. The whole problem with an
     * untagged folder is that every player falls back to the filename *silently* — so the file is
     * already being called `2026-03-11 rough 2` everywhere, and writing that into the title costs
     * nothing and makes it survive being copied somewhere that does not fall back so kindly.
     *
     * Only ever fills an empty title. Replacing a title someone typed with a filename is
     * destructive in a way none of the other fields are, so it does not happen even under
     * [ApplyMode.REPLACE].
     */
    val titleFromFilename: Boolean = true,
) {
    /** Whether there is anything to apply at all — an empty set of defaults is not worth offering. */
    val isEmpty: Boolean
        get() = artist.isBlank() && albumArtist.isBlank() && album.isBlank() &&
            genre.isBlank() && year.isBlank() && comment.isBlank() &&
            !hasCover && !titleFromFilename
}

/**
 * What applying the defaults should do to a field that already has something in it.
 *
 * Two modes rather than one because both are genuinely wanted and they are not the same job.
 * Filling gaps is the safe sweep over a folder — it can never lose anything, so it can be run over
 * a mixed folder of tagged and untagged files without reading them first. Replacing is for the case
 * where the existing tags are *wrong*: a batch exported by a DAW that stamped its own project name
 * into every album field, say.
 *
 * Neither mode ever clears a field. An empty default means "no opinion about this", not "make it
 * empty" — otherwise setting only an artist would wipe the album off every file it touched.
 */
enum class ApplyMode { FILL_GAPS, REPLACE }

/**
 * [tags] with these defaults applied, [fallbackTitle] standing in for a missing title when
 * [TagDefaults.titleFromFilename] asks for it.
 *
 * Pure, and deliberately so: the same function fills in the editor's form and drives the bulk apply
 * over a selection, so what the editor previews is exactly what a batch would write.
 *
 * The defaults are trimmed here rather than when they are saved, so the settings screen can keep a
 * space someone has only just typed; a default of nothing but spaces is no opinion at all.
 */
fun TagDefaults.applyTo(tags: Tags, mode: ApplyMode, fallbackTitle: String): Tags {
    fun pick(current: String, typed: String): String {
        val default = typed.trim()
        return when {
            default.isEmpty() -> current // no opinion — see [ApplyMode]
            mode == ApplyMode.REPLACE -> default
            current.isEmpty() -> default
            else -> current
        }
    }
    return tags.copy(
        // The title never takes a default and is never replaced; it only ever gets the filename,
        // and only when there is nothing there. See [TagDefaults.titleFromFilename].
        title = if (tags.title.isEmpty() && titleFromFilename) fallbackTitle else tags.title,
        artist = pick(tags.artist, artist),
        albumArtist = pick(tags.albumArtist, albumArtist),
        album = pick(tags.album, album),
        genre = pick(tags.genre, genre),
        year = pick(tags.year, year),
        comment = pick(tags.comment, comment),
    )
}

/**
 * Which cover a file should end up with: its own, or the default.
 *
 * Same rule as the text fields — no default means no opinion, so a file keeps whatever it had.
 */
fun chooseCover(current: Artwork?, default: Artwork?, mode: ApplyMode): Artwork? = when {
    default == null -> current
    mode == ApplyMode.REPLACE -> default
    current == null -> default
    else -> current
}
