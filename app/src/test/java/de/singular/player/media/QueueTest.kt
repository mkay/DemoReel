// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The ordering behind the car's three ways to start a run of mixes.
 *
 * Strings stand in for mixes, which is the reason [Queue] is generic: a `Mix` carries an
 * `android.net.Uri` and would drag the whole test onto a device for the sake of ordering a list.
 */
class QueueTest {

    private val folder = listOf("a.mp3", "b.flac", "c.wav", "d.mp3", "e.flac")

    @Test
    fun `in order keeps the listing's order`() {
        assertEquals(folder, Queue.inOrder(folder))
    }

    @Test
    fun `in order copies, so the queue cannot change underneath the player`() {
        val live = ArrayList(folder)
        val queued = Queue.inOrder(live)
        live.clear()
        assertEquals(5, queued.size)
    }

    @Test
    fun `a shuffle is a permutation - every mix exactly once`() {
        val shuffled = Queue.shuffled(folder, Random(7))
        assertEquals(folder.size, shuffled.size)
        assertEquals(folder.toSet(), shuffled.toSet())
        assertEquals(folder.sorted(), shuffled.sorted())
    }

    @Test
    fun `a seeded shuffle is repeatable`() {
        assertEquals(Queue.shuffled(folder, Random(42)), Queue.shuffled(folder, Random(42)))
    }

    @Test
    fun `a shuffle actually reorders`() {
        // Long enough that an identity permutation would be a real signal rather than luck: with
        // 40 items the chance of shuffling back into the same order is 1 in 40!.
        val many = (1..40).map { "mix $it" }
        assertNotEquals(many, Queue.shuffled(many, Random(1)))
    }

    @Test
    fun `shuffling starting with a mix puts it first and keeps the rest`() {
        val queued = Queue.shuffledStartingWith(folder, "c.wav", Random(3))
        assertEquals("c.wav", queued.first())
        assertEquals(folder.sorted(), queued.sorted())
    }

    @Test
    fun `shuffling starting with a mix that is not there is an ordinary shuffle`() {
        val queued = Queue.shuffledStartingWith(folder, "gone.mp3", Random(3))
        assertEquals(folder.sorted(), queued.sorted())
        assertTrue("gone.mp3" !in queued)
    }

    @Test
    fun `empty and single-item folders are not special cases`() {
        assertEquals(emptyList<String>(), Queue.shuffled(emptyList<String>(), Random(1)))
        assertEquals(listOf("only.mp3"), Queue.shuffled(listOf("only.mp3"), Random(1)))
        assertEquals(listOf("only.mp3"), Queue.shuffledStartingWith(listOf("only.mp3"), "only.mp3"))
    }
}
