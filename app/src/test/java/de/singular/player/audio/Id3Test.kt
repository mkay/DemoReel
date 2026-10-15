// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import org.junit.Assert.assertArrayEquals
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
        composer = "Kreuder",
        bpm = "112",
        key = "F#m",
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

    // ---- v2.2, as Logic Pro wrote it until 10.5 -------------------------------------------------

    @Test
    fun `a Logic Pro v2_2 tag reads`() {
        val read = Id3.read(logicTag())
        assertEquals("Kasper", read.title)
        assertEquals("Kreuder", read.artist)
        assertEquals("2019", read.year)
        // The comment typed in the bounce dialog, not the iTunNORM one Logic writes ahead of it.
        assertEquals("22.03.2019", read.comment)
        assertEquals("196", read.bpm)
        assertEquals("C", read.key)
    }

    @Test
    fun `a v2_2 picture reads as the cover`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        val pic = Bytes()
        pic.byte(0) // encoding
        pic.ascii("JPG")
        pic.byte(3) // front cover
        pic.byte(0) // empty description
        pic.raw(jpeg)
        val tag = v22Tag(listOf("TT2" to text("Kasper"), "PIC" to pic.toByteArray()))
        assertArrayEquals(jpeg, Id3.readPicture(tag))
    }

    @Test
    fun `saving a v2_2 tag keeps what the editor does not show`() {
        val old = logicTag()
        val rebuilt = Id3.build(Id3.read(old), null, Artwork.MIME_JPEG, Id3.kept(old))

        assertEquals(4, rebuilt[3].toInt()) // upgraded to v2.4
        assertEquals(Id3.read(old), Id3.read(rebuilt))
        val ids = Id3.kept(rebuilt).frames.map { it.id }
        assertTrue("TSSE" in ids) // no field for it, so carried
        assertTrue("COMM" in ids) // iTunNORM, likewise
        // Logic's date and time, folded into the one v2.4 frame rather than dropped with the
        // v2.3-only frames they came in.
        assertEquals("2019-03-22T20:59", Id3.kept(rebuilt).date)
        assertTrue("TDAT" !in ids && "TIME" !in ids && "TYER" !in ids)
    }

    @Test
    fun `a changed year replaces the old date rather than keeping its day`() {
        val old = logicTag()
        val rebuilt = Id3.build(Id3.read(old).copy(year = "2020"), null, Artwork.MIME_JPEG, Id3.kept(old))
        assertEquals("2020", Id3.kept(rebuilt).date)
    }

    @Test
    fun `a new copy of the audio drops what was measured on the old`() {
        val kept = Id3.kept(logicTag()).forNewAudio()
        val ids = kept.frames.map { it.id }
        assertTrue("TSSE" !in ids) // the encoder that made the old audio
        assertTrue("COMM" !in ids) // iTunNORM, the old audio's loudness
        assertEquals("2019-03-22T20:59", kept.date) // the date is the music's, and stays
    }

    // ---- what a rewrite carries -------------------------------------------------------------

    @Test
    fun `frames the editor does not own survive a rewrite`() {
        val old = v23Tag("TIT2" to "Old Mix", "TPUB" to "Singular", "TEXT" to "Kreuder", "TORY" to "2019")
        val rebuilt = Id3.build(Tags(title = "New Mix"), null, Artwork.MIME_JPEG, Id3.kept(old))
        val kept = Id3.kept(rebuilt).frames.associate { it.id to it.body.drop(1).toByteArray() }
        assertEquals("New Mix", Id3.read(rebuilt).title)
        assertEquals("Singular", String(kept.getValue("TPUB"), Charsets.ISO_8859_1))
        assertEquals("Kreuder", String(kept.getValue("TEXT"), Charsets.ISO_8859_1))
        // A v2.3 frame v2.4 renamed goes across under its new name.
        assertEquals("2019", String(kept.getValue("TDOR"), Charsets.ISO_8859_1))
    }

    @Test
    fun `a frame flagged to be discarded on change is not carried`() {
        val old = v23Tag("TPUB" to "Singular")
        old[HEADER + 8] = 0x80.toByte() // v2.3 status: discard when the tag is altered
        assertTrue(Id3.kept(old).frames.isEmpty())
    }

    @Test
    fun `a file with only machine comments shows no comment`() {
        val tag = v22Tag(listOf("COM" to comment("iTunNORM", " 0000030C 00000345")))
        assertEquals("", Id3.read(tag).comment)
    }

    /**
     * The tag on a Logic Pro X 10.4 bounce, frame for frame: Latin-1 text, the date and time split
     * over `TYE`, `TDA` (DDMM) and `TIM` (HHMM), and Apple's loudness comment ahead of the real one.
     */
    private fun logicTag(): ByteArray = v22Tag(
        listOf(
            "TSS" to text("Logic Pro X 10.4.4"),
            "COM" to comment("iTunNORM", " 0000030C 00000345 000015E9"),
            "TT2" to text("Kasper"),
            "TP1" to text("Kreuder"),
            "TKE" to text("C"),
            "TBP" to text("196"),
            "COM" to comment("", "22.03.2019"),
            "TYE" to text("2019"),
            "TDA" to text("2203"),
            "TIM" to text("2059"),
        ),
    )

    /** A text frame body in Latin-1. */
    private fun text(value: String): ByteArray = byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)

    /** A comment body in Latin-1, in English, with [description]. */
    private fun comment(description: String, value: String): ByteArray =
        byteArrayOf(0) + "eng".toByteArray(Charsets.US_ASCII) +
            description.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) +
            value.toByteArray(Charsets.ISO_8859_1)

    /** An ID3v2.2 tag: three-character ids, three-byte plain sizes, no frame flags. */
    private fun v22Tag(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = Bytes()
        frames.forEach { (id, bytes) ->
            body.ascii(id)
            body.byte((bytes.size shr 16) and 0xFF)
            body.byte((bytes.size shr 8) and 0xFF)
            body.byte(bytes.size and 0xFF)
            body.raw(bytes)
        }
        return wrap(2, body.toByteArray())
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
        return wrap(3, body.toByteArray())
    }

    /** A tag header of [major] version around [body]. */
    private fun wrap(major: Int, body: ByteArray): ByteArray {
        val out = Bytes()
        out.ascii("ID3")
        out.byte(major)
        out.byte(0)
        out.byte(0)
        // The tag size is synchsafe in every version, unlike the frame sizes.
        out.byte((body.size shr 21) and 0x7F)
        out.byte((body.size shr 14) and 0x7F)
        out.byte((body.size shr 7) and 0x7F)
        out.byte(body.size and 0x7F)
        out.raw(body)
        return out.toByteArray()
    }

    private companion object {
        /** Where the first frame starts: after the ten-byte tag header. */
        const val HEADER = 10
    }
}
