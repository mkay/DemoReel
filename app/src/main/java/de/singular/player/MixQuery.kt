// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player

import de.singular.player.audio.Tags
import java.text.Normalizer
import java.util.Locale

/**
 * What the library search box asks for, as a test on one mix.
 *
 * A mix matches when every word typed appears somewhere in its key or its tags. The key is the path
 * under the granted folder, so it carries the filename *and* every folder above it: "wilhelm" finds
 * everything in a Wilhelm folder, and "wilhelm rough" narrows that to the rough mix. The tags are
 * there because the library shows them — a row's name is its tagged title wherever there is one,
 * and a search that could not find the words written on a row would be a search for something else.
 *
 * Title, artist, album, album artist, genre and year. Not the track number, which is a digit or two
 * and would match half the tree on its own; not the comment, which a listing never reads (see
 * [Tags.comment]) and which would cost a second, slower open of every file to find.
 *
 * Words rather than one phrase, in any order, because nobody remembers the order of a name they
 * half remember. Case and accents are ignored on both sides: a mix titled "Übung" is found by
 * "ubung", which is what a thumb on a keyboard without the umlaut to hand will type.
 *
 * Pure Kotlin, so the matching is a unit test rather than something checked by typing on a phone.
 */
class MixQuery private constructor(private val words: List<String>) {

    /**
     * Whether the mix at [key] matches, given [tags] — or given its key alone when [tags] is null,
     * which is how a search asks before it has opened the file. A match on the key alone is a match;
     * a miss on it is only a miss once the tags have had their say.
     */
    fun matches(key: String, tags: Tags?): Boolean {
        val haystack = fold(if (tags == null) key else key + "\n" + tags.searchable())
        return words.all { it in haystack }
    }

    companion object {
        /** The query typed as [text], or null if there is nothing in it to search for. */
        fun of(text: String): MixQuery? =
            fold(text).split(Regex("\\s+")).filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
                ?.let(::MixQuery)

        private val marks = Regex("\\p{Mn}+")

        /** Lower-cased, with accents taken off — "Übung" and "ubung" fold to the same thing. */
        private fun fold(s: String): String =
            marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

        /**
         * One field to a line, so a word cannot be found across the join between two of them —
         * "Demo" as a title and "Mixes" as an album is not a match for "demomixes".
         */
        private fun Tags.searchable(): String =
            listOf(title, artist, album, albumArtist, genre, year).joinToString("\n")
    }
}
