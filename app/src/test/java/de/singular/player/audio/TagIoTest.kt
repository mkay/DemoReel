// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The whole rewrite, end to end: a file goes in, a file comes out, and the audio in the middle is
 * the same audio.
 *
 * That last point is the one this file exists for. Everything else about a re-tag is recoverable —
 * a wrong artist can be typed again — but a rewrite that drops, duplicates or shifts a single byte
 * of audio has damaged the thing the app is for, and it does it silently: the file still plays, and
 * it plays wrong somewhere in the middle where nobody is listening at the time.
 */
class TagIoTest {

    private val tags = Tags(
        title = "Rough Mix 3",
        artist = "Kreuder",
        album = "Demos",
        year = "2026",
        comment = "needs a longer fade",
    )

    /** Stands in for MPEG frames. Distinct bytes, so a shift of even one is visible. */
    private val audio = ByteArray(5_000) { (it * 31 % 251).toByte() }

    @Test
    fun `mp3 audio is copied byte for byte`() {
        val original = taggedMp3(Tags(artist = "Somebody Else"), audio)
        val out = ByteArrayOutputStream()
        assertTrue(TagIo.write(TagFormat.MP3, ByteArrayInputStream(original), out, tags, null))

        val written = out.toByteArray()
        val tagBytes = Id3.tagSize(written)
        assertArrayEquals(audio, written.copyOfRange(tagBytes, written.size))
    }

    @Test
    fun `an untagged mp3 gains a tag without losing its first bytes`() {
        // The writer reads ten bytes before it knows whether there is a tag; with no tag those ten
        // bytes are audio and have to go back in front of the rest. Off-by-ten here would lop the
        // first MPEG frame header off every untagged file the app touched.
        val out = ByteArrayOutputStream()
        assertTrue(TagIo.write(TagFormat.MP3, ByteArrayInputStream(audio), out, tags, null))

        val written = out.toByteArray()
        val tagBytes = Id3.tagSize(written)
        assertArrayEquals(audio, written.copyOfRange(tagBytes, written.size))
    }

    @Test
    fun `mp3 tags round trip through a write and a read`() {
        val out = ByteArrayOutputStream()
        TagIo.write(TagFormat.MP3, ByteArrayInputStream(audio), out, tags, null)
        val content = TagIo.read(TagFormat.MP3, ByteArrayInputStream(out.toByteArray()))
        assertNotNull(content)
        assertEquals(tags, content!!.tags)
    }

    @Test
    fun `a trailing ID3v1 tag is removed`() {
        // A file that has been through several taggers carries both. Once a new v2 tag is written
        // the old v1 one contradicts it, and something somewhere reads the back of the file.
        val original = taggedMp3(Tags(artist = "Old"), audio) + id3v1("Old Title", "Old Artist")
        val out = ByteArrayOutputStream()
        TagIo.write(TagFormat.MP3, ByteArrayInputStream(original), out, tags, null)

        val written = out.toByteArray()
        val tagBytes = Id3.tagSize(written)
        assertArrayEquals(audio, written.copyOfRange(tagBytes, written.size))
        assertFalse(endsWithId3v1(written))
    }

    @Test
    fun `audio that merely ends in TAG is not mistaken for a v1 tag`() {
        // The tail check must be about a 128-byte block at the end, not about the letters appearing
        // anywhere near it. Audio is arbitrary bytes and will contain "TAG" eventually.
        val tail = "TAG".toByteArray(Charsets.US_ASCII) + ByteArray(200) { 0x5A }
        val body = audio + tail
        val out = ByteArrayOutputStream()
        TagIo.write(TagFormat.MP3, ByteArrayInputStream(body), out, tags, null)

        val written = out.toByteArray()
        val tagBytes = Id3.tagSize(written)
        assertArrayEquals(body, written.copyOfRange(tagBytes, written.size))
    }

    @Test
    fun `a file shorter than the held-back tail still copies whole`() {
        // The tail is held back 128 bytes at a time; a file with less than that in it exercises the
        // path where nothing is ever written until the very end.
        val tiny = ByteArray(40) { it.toByte() }
        val out = ByteArrayOutputStream()
        TagIo.write(TagFormat.MP3, ByteArrayInputStream(tiny), out, tags, null)

        val written = out.toByteArray()
        val tagBytes = Id3.tagSize(written)
        assertArrayEquals(tiny, written.copyOfRange(tagBytes, written.size))
    }

    @Test
    fun `a cover survives the whole rewrite`() {
        val picture = Artwork(ByteArray(400) { (it % 97).toByte() }, "image/jpeg", 500, 500)
        val out = ByteArrayOutputStream()
        TagIo.write(TagFormat.MP3, ByteArrayInputStream(audio), out, tags, picture)

        val content = TagIo.read(TagFormat.MP3, ByteArrayInputStream(out.toByteArray()))!!
        assertArrayEquals(picture.bytes, content.cover)
        assertEquals(tags, content.tags)
    }

    @Test
    fun `dropping a cover shortens the file rather than blanking the frame`() {
        val picture = Artwork(ByteArray(400) { it.toByte() }, "image/jpeg", 500, 500)
        val withCover = ByteArrayOutputStream()
        TagIo.write(TagFormat.MP3, ByteArrayInputStream(audio), withCover, tags, picture)

        val without = ByteArrayOutputStream()
        TagIo.write(
            TagFormat.MP3,
            ByteArrayInputStream(withCover.toByteArray()),
            without,
            tags,
            null,
        )
        val written = without.toByteArray()
        assertTrue(written.size < withCover.size())
        assertArrayEquals(
            audio,
            written.copyOfRange(Id3.tagSize(written), written.size),
        )
    }

    @Test
    fun `flac audio is copied byte for byte`() {
        val streamInfo = ByteArray(34) { (it + 1).toByte() }
        val original = FlacTags.build(
            kept = byteArrayOf(0x00, 0x00, 0x00, 34) + streamInfo,
            tags = Tags(artist = "Somebody Else"),
            cover = null,
        ) + audio

        val out = ByteArrayOutputStream()
        assertTrue(TagIo.write(TagFormat.FLAC, ByteArrayInputStream(original), out, tags, null))

        val written = out.toByteArray()
        val layout = FlacTags.readLayout(ByteArrayInputStream(written))!!
        assertEquals(tags, layout.tags)
        assertArrayEquals(audio, written.copyOfRange(layout.audioStart.toInt(), written.size))
    }

    @Test
    fun `a mislabelled file is refused rather than rewritten`() {
        // An mp3 whose extension says flac. Writing it as FLAC would prepend a metadata section to
        // an MPEG stream and produce a file nothing can read — so the writer says no and the store
        // throws the half-built result away without touching the original.
        val mp3 = taggedMp3(tags, audio)
        val out = ByteArrayOutputStream()
        assertFalse(TagIo.write(TagFormat.FLAC, ByteArrayInputStream(mp3), out, tags, null))
    }

    @Test
    fun `an untagged mp3 reads as empty rather than failing`() {
        val content = TagIo.read(TagFormat.MP3, ByteArrayInputStream(audio))
        assertNotNull(content)
        assertEquals(Tags(), content!!.tags)
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun taggedMp3(tags: Tags, audio: ByteArray): ByteArray =
        Id3.build(tags, cover = null, coverMime = Artwork.MIME_JPEG) + audio

    /** A real ID3v1 tag: exactly 128 bytes, `TAG`, then fixed-width fields. */
    private fun id3v1(title: String, artist: String): ByteArray {
        val out = ByteArray(128)
        "TAG".toByteArray(Charsets.US_ASCII).copyInto(out)
        title.toByteArray(Charsets.ISO_8859_1).copyInto(out, 3, 0, minOf(title.length, 30))
        artist.toByteArray(Charsets.ISO_8859_1).copyInto(out, 33, 0, minOf(artist.length, 30))
        return out
    }

    private fun endsWithId3v1(file: ByteArray): Boolean {
        if (file.size < 128) return false
        val at = file.size - 128
        return file[at] == 'T'.code.toByte() &&
            file[at + 1] == 'A'.code.toByte() &&
            file[at + 2] == 'G'.code.toByte()
    }
}
