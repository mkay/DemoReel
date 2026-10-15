// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * FLAC's metadata blocks, checked at the byte level.
 *
 * The two things worth guarding here are the ones a listen would not catch. A metadata block whose
 * *last* marker is left set in the middle of the run makes a decoder stop reading before the tags —
 * the file plays perfectly and shows nothing. And the format mixes big-endian block headers with
 * little-endian comment fields, so a single misplaced byte order produces a comment block whose
 * declared length is nonsense.
 */
class FlacTagsTest {

    private val tags = Tags(
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

    /** 34 bytes of STREAMINFO — the contents do not matter here, only that it is preserved. */
    private val streamInfo = ByteArray(34) { (it + 1).toByte() }

    @Test
    fun `every field survives a round trip`() {
        val file = flac(tags, cover = null)
        val layout = FlacTags.readLayout(ByteArrayInputStream(file))
        assertNotNull(layout)
        assertEquals(tags, layout!!.tags)
    }

    @Test
    fun `stream info is carried through untouched`() {
        val file = flac(tags, cover = null)
        val layout = FlacTags.readLayout(ByteArrayInputStream(file))!!
        // Header (4 bytes) plus the block itself, and the block's own bytes unchanged: a tag editor
        // that damaged STREAMINFO would produce a file nothing could decode.
        assertEquals(4 + streamInfo.size, layout.keptBlocks.size)
        assertArrayEquals(streamInfo, layout.keptBlocks.copyOfRange(4, layout.keptBlocks.size))
    }

    @Test
    fun `exactly one block is marked last, and it is the last one`() {
        val rebuilt = FlacTags.build(
            kept = header(type = 0, length = streamInfo.size, last = true) + streamInfo,
            tags = tags,
            cover = null,
        )
        // Note the input above deliberately has its marker set: the kept blocks come from a file
        // where one of them *was* last, and leaving that marker in place is the bug this guards.
        val marks = lastMarkers(rebuilt)
        assertEquals(listOf(false, true), marks)
    }

    @Test
    fun `a picture adds a block and takes the last marker`() {
        val picture = Artwork(ByteArray(120) { it.toByte() }, "image/jpeg", 300, 300)
        val rebuilt = FlacTags.build(
            kept = header(0, streamInfo.size, last = true) + streamInfo,
            tags = tags,
            cover = picture,
        )
        assertEquals(listOf(false, false, true), lastMarkers(rebuilt))

        val layout = FlacTags.readLayout(ByteArrayInputStream(rebuilt + AUDIO))!!
        assertArrayEquals(picture.bytes, layout.picture)
        assertEquals(tags, layout.tags)
    }

    @Test
    fun `audio starts where the metadata ends`() {
        val file = flac(tags, cover = null)
        val layout = FlacTags.readLayout(ByteArrayInputStream(file))!!
        assertEquals((file.size - AUDIO.size).toLong(), layout.audioStart)
    }

    @Test
    fun `padding is dropped rather than carried`() {
        val withPadding = Bytes().apply {
            ascii("fLaC")
            raw(header(0, streamInfo.size, last = false))
            raw(streamInfo)
            raw(header(TYPE_PADDING, 512, last = true))
            raw(ByteArray(512))
        }.toByteArray() + AUDIO

        val layout = FlacTags.readLayout(ByteArrayInputStream(withPadding))!!
        // Only STREAMINFO is kept: padding exists so a tagger can grow in place, and this app
        // rewrites the whole file instead — carrying it would leave dead space in every file.
        assertEquals(4 + streamInfo.size, layout.keptBlocks.size)
    }

    @Test
    fun `an unknown block type is preserved`() {
        // A SEEKTABLE, which this app has no opinion about and must not lose: dropping it makes a
        // long file slow to scrub for no reason at all.
        val seekTable = ByteArray(18) { 0x7F }
        val file = Bytes().apply {
            ascii("fLaC")
            raw(header(0, streamInfo.size, last = false))
            raw(streamInfo)
            raw(header(TYPE_SEEKTABLE, seekTable.size, last = true))
            raw(seekTable)
        }.toByteArray() + AUDIO

        val layout = FlacTags.readLayout(ByteArrayInputStream(file))!!
        assertEquals(4 + streamInfo.size + 4 + seekTable.size, layout.keptBlocks.size)
        assertTrue(
            layout.keptBlocks.toList().windowed(seekTable.size)
                .any { it.toByteArray().contentEquals(seekTable) },
        )
    }

    @Test
    fun `a file that is not FLAC is refused`() {
        assertNull(FlacTags.readLayout(ByteArrayInputStream(ByteArray(64))))
        // An mp3, handed to the FLAC path by a mislabelled extension.
        val mp3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte()) + ByteArray(60)
        assertNull(FlacTags.readLayout(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `empty tags produce a comment block with no fields`() {
        val rebuilt = FlacTags.build(
            kept = header(0, streamInfo.size, last = true) + streamInfo,
            tags = Tags(),
            cover = null,
        )
        val layout = FlacTags.readLayout(ByteArrayInputStream(rebuilt + AUDIO))!!
        assertEquals(Tags(), layout.tags)
    }

    @Test
    fun `non-ascii text survives`() {
        val accented = Tags(artist = "Björk", comment = "größer")
        val rebuilt = FlacTags.build(
            kept = header(0, streamInfo.size, last = true) + streamInfo,
            tags = accented,
            cover = null,
        )
        val layout = FlacTags.readLayout(ByteArrayInputStream(rebuilt + AUDIO))!!
        assertEquals(accented, layout.tags)
    }

    @Test
    fun `comment entries the editor does not own survive a rewrite`() {
        val file = FlacTags.build(
            kept = header(0, streamInfo.size, last = false) + streamInfo,
            tags = tags.copy(year = "2026"),
            cover = null,
            entries = FlacTags.Kept(listOf("MOOD=calm", "REPLAYGAIN_TRACK_GAIN=-3.1 dB"), "2026-03-11"),
        ) + AUDIO
        val layout = FlacTags.readLayout(ByteArrayInputStream(file))!!
        assertEquals(tags, layout.tags)
        assertEquals(listOf("MOOD=calm", "REPLAYGAIN_TRACK_GAIN=-3.1 dB"), layout.kept.entries)
        // The full date is written while the year agrees with it.
        assertEquals("2026-03-11", layout.kept.date)
        // A copy with new audio keeps the mood and drops the gain measured on the old.
        assertEquals(listOf("MOOD=calm"), layout.kept.forNewAudio().entries)
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** A whole small FLAC file: magic, STREAMINFO, a comment block, then some "audio". */
    private fun flac(tags: Tags, cover: Artwork?): ByteArray =
        FlacTags.build(
            kept = header(0, streamInfo.size, last = false) + streamInfo,
            tags = tags,
            cover = cover,
        ) + AUDIO

    private fun header(type: Int, length: Int, last: Boolean) = byteArrayOf(
        (if (last) type or 0x80 else type).toByte(),
        ((length ushr 16) and 0xFF).toByte(),
        ((length ushr 8) and 0xFF).toByte(),
        (length and 0xFF).toByte(),
    )

    /** The last-block marker of every metadata block in [file], in order. */
    private fun lastMarkers(file: ByteArray): List<Boolean> {
        val marks = mutableListOf<Boolean>()
        var p = 4 // past "fLaC"
        while (p + 4 <= file.size) {
            marks += file[p].toInt() and 0x80 != 0
            val length = ((file[p + 1].toInt() and 0xFF) shl 16) or
                ((file[p + 2].toInt() and 0xFF) shl 8) or
                (file[p + 3].toInt() and 0xFF)
            p += 4 + length
            if (marks.last()) break
        }
        return marks
    }

    private fun List<Byte>.toByteArray() = ByteArray(size) { this[it] }

    private companion object {
        /** Stands in for the audio frames; the point is only that they are copied unchanged. */
        val AUDIO = ByteArray(256) { (it * 7).toByte() }

        const val TYPE_PADDING = 1
        const val TYPE_SEEKTABLE = 3
    }
}
