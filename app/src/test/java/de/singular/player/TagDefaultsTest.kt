// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player

import de.singular.player.audio.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagDefaultsTest {

    @Test
    fun `defaults are trimmed where they are applied`() {
        val defaults = TagDefaults(artist = " The Beige Committee ", album = "I Am Not My Elbow ")
        val tags = defaults.applyTo(Tags(), ApplyMode.FILL_GAPS, fallbackTitle = "x")
        assertEquals("The Beige Committee", tags.artist)
        assertEquals("I Am Not My Elbow", tags.album)
    }

    @Test
    fun `a default of only spaces is no opinion`() {
        val defaults = TagDefaults(artist = "   ", titleFromFilename = false)
        val tags = defaults.applyTo(Tags(artist = "Kreuder"), ApplyMode.REPLACE, fallbackTitle = "x")
        assertEquals("Kreuder", tags.artist)
        assertTrue(defaults.isEmpty)
    }

    @Test
    fun `fill gaps keeps what is there and replace overwrites it`() {
        val defaults = TagDefaults(artist = "The Beige Committee")
        val tagged = Tags(artist = "Kreuder")
        assertEquals("Kreuder", defaults.applyTo(tagged, ApplyMode.FILL_GAPS, "x").artist)
        assertEquals("The Beige Committee", defaults.applyTo(tagged, ApplyMode.REPLACE, "x").artist)
        assertFalse(defaults.isEmpty)
    }
}
