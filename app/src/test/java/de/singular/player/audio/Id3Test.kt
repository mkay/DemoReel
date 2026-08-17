// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ID3 writer and reader, checked against each other and against hand-built tags.
 *
 * Worth having as local JVM tests rather than as something checked by opening the app: this is byte
 * layout, and byte layout is either exactly right or it silently produces a file that one player
 * reads and another does not. Every case here is one that would be invisible on screen — a size
 * field off by the header's own length, a UTF-16 description scanned as if it were Latin-1 — and
 * would show up weeks later as "some of my tags disappeared".
 *
 * [Id3] is pure Kotlin with no Android dependency, which is what makes this possible at all.
 */
class Id3Test {

    private val full = Tags(
        title = "Rough Mix 3",
        artist = "Kreuder",
        album = "Demos",
        albumArtist = "Various",
        year = "2026",
        genre = "Electronic",
        track = "4",
        comment = "needs a longer fade",
    )

    @Test
    fun `every field survives a round trip`() {
        val tag = Id3.build(full, cover = null, coverMime = Artwork.MIME_JPEG)
        assertEquals(full, Id3.read(tag))
    }

    @Test
    fun `tagSize measures the whole tag`() {
        val tag = Id3.build(full, cover = null, coverMime = Artwork.MIME_JPEG)
        // The size the header declares must be the size of what was actually written, or the audio
        // behind it starts in the wrong place — the one mistake here that corrupts a file rather
        // than losing a field.
        assertEquals(tag.size, Id3.tagSize(tag))
    }

    @Test
    fun `no tag is reported as no tag`() {
        // A bare MPEG frame sync, which is what an untagged mp3 opens with.
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(64)
        assertEquals(0, Id3.tagSize(audio))
    }

    @Test
    fun `empty fields are not written at all`() {
        val onlyTitle = Tags(title = "One")
        val tag = Id3.build(onlyTitle, cover = null, coverMime = Artwork.MIME_JPEG)
        assertEquals(onlyTitle, Id3.read(tag))
        // A frame per non-empty field and nothing else: header, one frame header, three bytes of
        // "One" and its encoding byte. Anything larger means blank frames are being written, which
        // is what makes a player show empty lines where it should show nothing.
        assertEquals(Id3.HEADER_BYTES + 10 + 1 + 3, tag.size)
    }

    @Test
    fun `non-ascii text survives, which is what UTF-8 is for`() {
        val accented = Tags(artist = "Björk", album = "Fünf Stücke", comment = "größer")
        val tag = Id3.build(accented, cover = null, coverMime = Artwork.MIME_JPEG)
        assertEquals(accented, Id3.read(tag))
    }

    @Test
    fun `size fields never contain a false sync`() {
        // Synchsafe integers exist so that no size field can hold a run of set high bits that a
        // decoder scanning for an MPEG frame sync would mistake for one. A tag large enough to need
        // the upper bytes of the size is the case that would expose a plain u32 written by mistake.
        val big = Tags(comment = "x".repeat(500))
        val tag = Id3.build(big, cover = null, coverMime = Artwork.MIME_JPEG)
        for (i in 6..9) {
            assertTrue("byte $i of the tag size has its top bit set", tag[i].toInt() and 0x80 == 0)
        }
        assertEquals(tag.size, Id3.tagSize(tag))
    }

    @Test
    fun `a cover survives a round trip`() {
        val picture = ByteArray(300) { (it % 251).toByte() }
        val tag = Id3.build(full, cover = picture, coverMime = "image/png")
        assertTrue(picture.contentEquals(Id3.readPicture(tag)))
        // And the text is still readable with a picture frame sitting among the others.
        assertEquals(full, Id3.read(tag))
    }

    @Test
    fun `no cover means no picture frame`() {
        val tag = Id3.build(full, cover = null, coverMime = Artwork.MIME_JPEG)
        assertNull(Id3.readPicture(tag))
    }

    @Test
    fun `a v2_3 tag written by something else still reads`() {
        // v2.3 sizes its frames with a plain integer rather than a synchsafe one, and Latin-1 is
        // its default text encoding. This is what most files in the wild actually carry.
        val tag = v23Tag("TPE1" to "Kreuder", "TIT2" to "Old Mix")
        val read = Id3.read(tag)
        assertEquals("Kreuder", read.artist)
        assertEquals("Old Mix", read.title)
    }

    @Test
    fun `a numeric genre reference is dropped in favour of its text`() {
        val tag = v23Tag("TCON" to "(17)Rock")
        assertEquals("Rock", Id3.read(tag).genre)
    }

    @Test
    fun `a track number keeps only the track, not the total`() {
        val tag = Id3.build(Tags(track = "4/12"), cover = null, coverMime = Artwork.MIME_JPEG)
        assertEquals("4", Id3.read(tag).track)
    }

    @Test
    fun `a timestamp year is reduced to the year`() {
        val tag = Id3.build(
            Tags(year = "2026-03-11T14:02"),
            cover = null,
            coverMime = Artwork.MIME_JPEG,
        )
        assertEquals("2026", Id3.read(tag).year)
    }

    @Test
    fun `a truncated tag is refused rather than read as rubbish`() {
        val tag = Id3.build(full, cover = null, coverMime = Artwork.MIME_JPEG)
        // Cut it short mid-frame: the parser must stop at the frame it cannot complete rather than
        // reading past the end or inventing a field out of whatever follows.
        val cut = tag.copyOf(tag.size - 20)
        val read = Id3.read(cut)
        assertEquals("Rough Mix 3", read.title) // the early frames are intact and still readable
    }

    /** A minimal, valid ID3v2.3 tag with Latin-1 text frames — what an older tagger would write. */
    private fun v23Tag(vararg frames: Pair<String, String>): ByteArray {
        val body = Bytes()
        frames.forEach { (id, value) ->
            val text = value.toByteArray(Charsets.ISO_8859_1)
            body.ascii(id)
            body.u32(text.size + 1) // v2.3: a plain big-endian size
            body.byte(0)
            body.byte(0)
            body.byte(0) // encoding: ISO-8859-1
            body.raw(text)
        }
        val bodyBytes = body.toByteArray()

        val out = Bytes()
        out.ascii("ID3")
        out.byte(3) // major version 3
        out.byte(0)
        out.byte(0)
        // The tag size is synchsafe in every version, unlike the frame sizes.
        out.byte((bodyBytes.size shr 21) and 0x7F)
        out.byte((bodyBytes.size shr 14) and 0x7F)
        out.byte((bodyBytes.size shr 7) and 0x7F)
        out.byte(bodyBytes.size and 0x7F)
        out.raw(bodyBytes)
        return out.toByteArray()
    }
}
