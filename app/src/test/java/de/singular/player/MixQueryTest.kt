// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player

import de.singular.player.audio.Tags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixQueryTest {

    private fun query(text: String) = MixQuery.of(text)!!

    @Test
    fun `nothing typed is no query`() {
        assertNull(MixQuery.of(""))
        assertNull(MixQuery.of("   "))
    }

    @Test
    fun `a folder name finds the mixes inside it`() {
        assertTrue(query("wilhelm").matches("Wilhelm/rough 3.mp3", null))
        assertFalse(query("wilhelm").matches("Bonaparte/Bonaparte.mp3", null))
    }

    @Test
    fun `every word has to match, in any order`() {
        val key = "Wilhelm/rough 3.mp3"
        assertTrue(query("rough wilhelm").matches(key, null))
        assertFalse(query("wilhelm master").matches(key, null))
    }

    @Test
    fun `the tags count as much as the path`() {
        val key = "2026/bounce 0728.flac"
        val tags = Tags(title = "Glass Harbour", artist = "The Lindens", album = "Night Ferry")
        assertTrue(query("harbour").matches(key, tags))
        assertTrue(query("lindens ferry").matches(key, tags))
        assertTrue(query("glass 2026").matches(key, tags))
        assertFalse(query("harbour").matches(key, Tags()))
    }

    @Test
    fun `without tags only the path is asked`() {
        val tags = Tags(title = "Glass Harbour")
        assertFalse(query("harbour").matches("2026/bounce 0728.flac", null))
        assertTrue(query("harbour").matches("2026/bounce 0728.flac", tags))
    }

    @Test
    fun `a word is not found across the join between two fields`() {
        val tags = Tags(title = "Demo", album = "Mixes")
        assertFalse(query("demomixes").matches("a.mp3", tags))
        assertTrue(query("demo mixes").matches("a.mp3", tags))
    }

    @Test
    fun `the track number and the comment are not searched`() {
        val tags = Tags(track = "7", comment = "the one with the long fade")
        assertFalse(query("7").matches("a.mp3", tags))
        assertFalse(query("fade").matches("a.mp3", tags))
    }

    @Test
    fun `case and accents are ignored on both sides`() {
        assertTrue(query("ubung").matches("Übung 3.wav", null))
        assertTrue(query("ÜBUNG").matches("ubung 3.wav", null))
        assertTrue(query("hafen").matches("a.mp3", Tags(title = "Häfen")))
        assertTrue(query("BJÖRK").matches("a.mp3", Tags(artist = "bjork")))
    }
}
