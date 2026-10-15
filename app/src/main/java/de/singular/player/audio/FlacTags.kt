// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import java.io.InputStream

/**
 * FLAC's metadata blocks, read and rewritten.
 *
 * A FLAC file is `fLaC`, then a run of metadata blocks, then the audio frames. Each block has a
 * four-byte header — one byte carrying the type in its low seven bits and a *last block* marker in
 * its top bit, then a three-byte big-endian length — and the audio begins directly after the block
 * whose marker is set.
 *
 * That layout is why re-tagging a FLAC is a whole-file rewrite and not a patch: the blocks sit in
 * front of the audio, so changing their total length moves every byte after them. FLAC provides
 * `PADDING` precisely so a tagger can grow in place, and this app does not use it — the SAF write
 * path builds the file in the cache and replaces it wholesale anyway (see
 * [de.singular.player.storage.MixStore]), so padding would buy nothing and would leave dead space
 * in every file the app touched.
 *
 * ## What is kept and what is replaced
 *
 * `STREAMINFO`, `SEEKTABLE`, `CUESHEET` and `APPLICATION` are **copied through untouched**. They
 * describe the audio, not the tagging, and a tag editor that dropped a seek table would make long
 * files slow to scrub for no reason. `VORBIS_COMMENT` and `PICTURE` are what this app owns, so they
 * are rebuilt from what the editor holds — but only the comment's fields the editor has a place
 * for: every other `KEY=value` entry is carried across as it was ([Kept]), so replay gain, lyrics
 * and whatever else a tagger put there survive a save. `PADDING` is dropped.
 *
 * `STREAMINFO` must be the first block, and it stays first because the kept blocks keep their
 * order; the new comment and picture go on the end.
 *
 * ## The endianness trap
 *
 * Block headers and `PICTURE` bodies are **big-endian**. A `VORBIS_COMMENT` body is
 * **little-endian**, because it is a Vorbis structure that FLAC borrowed wholesale rather than a
 * FLAC structure. Both appear in this file, a few lines apart, and getting one wrong produces a
 * file that looks fine until something tries to read it.
 */
object FlacTags {

    /**
     * What a file's metadata section contains and where the audio starts after it.
     *
     * [keptBlocks] is raw bytes — headers and bodies, in order, exactly as they were read — because
     * nothing here needs to understand a seek table in order to preserve one. Their last-block
     * markers are cleared on the way out in [build], since none of them is last any more.
     */
    class Layout(
        val audioStart: Long,
        val keptBlocks: ByteArray,
        val tags: Tags,
        val picture: ByteArray?,
        val kept: Kept,
    )

    /**
     * The comment entries the editor does not own, exactly as they were, and the full `DATE` —
     * see [Id3.Kept], which this mirrors for the other format.
     */
    class Kept internal constructor(
        internal val entries: List<String>,
        override val date: String,
    ) : TagIo.Kept {

        /** Without replay gain, the encoder and Apple's audio comments — see [Id3.Kept.forNewAudio]. */
        override fun forNewAudio(): Kept = Kept(
            entries.filterNot { entry ->
                val key = entry.substringBefore('=').uppercase()
                AUDIO_KEYS.any { key.startsWith(it) }
            },
            date,
        )

        /** These entries with [date] in place of their own — for a convert from another format. */
        fun withDate(date: String): Kept = Kept(entries, date)

        companion object {
            val NONE = Kept(emptyList(), "")
        }
    }

    /**
     * Walk the metadata blocks of [input], which must be positioned at the very start of the file.
     *
     * Streamed rather than read into one buffer: a `PICTURE` block can be a megabyte, and a file
     * that carries several would otherwise all be resident at once for the sake of one that is
     * about to be thrown away. The stream is left positioned at the first audio byte, which is what
     * the rewrite wants next.
     *
     * Null if this is not a FLAC file, or if a block claims a length that runs past the end of it.
     */
    fun readLayout(input: InputStream): Layout? {
        val magic = ByteArray(4)
        if (!input.readFully(magic)) return null
        if (String(magic, Charsets.US_ASCII) != "fLaC") return null

        val kept = Bytes(1024)
        var tags = Tags()
        var keptEntries = Kept.NONE
        var picture: ByteArray? = null
        var at = 4L

        while (true) {
            val header = ByteArray(4)
            if (!input.readFully(header)) return null
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            if (length < 0 || length > MAX_BLOCK) return null

            val body = ByteArray(length)
            if (length > 0 && !input.readFully(body)) return null
            at += 4 + length

            when (type) {
                TYPE_VORBIS_COMMENT -> readComment(body)?.let { (read, entries) ->
                    tags = read
                    keptEntries = entries
                }
                TYPE_PICTURE -> if (picture == null) picture = readPicture(body)
                TYPE_PADDING -> Unit // dropped; see the class doc
                else -> {
                    kept.raw(header)
                    kept.raw(body)
                }
            }

            if (last) break
        }

        return Layout(
            audioStart = at,
            keptBlocks = kept.toByteArray(),
            tags = tags,
            picture = picture,
            kept = keptEntries,
        )
    }

    /**
     * The whole metadata section for a rewritten file: `fLaC`, the kept blocks, then a fresh
     * comment and picture.
     *
     * The caller writes this and then copies the original's audio from [Layout.audioStart] onward.
     */
    fun build(kept: ByteArray, tags: Tags, cover: Artwork?, entries: Kept = Kept.NONE): ByteArray {
        val comment = commentBlock(tags, entries)
        val picture = cover?.let(::pictureBlock)

        val out = Bytes(kept.size + comment.size + (picture?.size ?: 0) + 8)
        out.ascii("fLaC")
        // Every kept block loses its last-block marker: the comment or the picture is last now.
        // A stale marker here would make a decoder stop reading before the tags it came for.
        out.raw(clearLastMarkers(kept))
        out.blockHeader(TYPE_VORBIS_COMMENT, comment.size, last = picture == null)
        out.raw(comment)
        if (picture != null) {
            out.blockHeader(TYPE_PICTURE, picture.size, last = true)
            out.raw(picture)
        }
        return out.toByteArray()
    }

    /** [blocks] with the top bit of every block header byte cleared — see [build]. */
    private fun clearLastMarkers(blocks: ByteArray): ByteArray {
        val copy = blocks.copyOf()
        var p = 0
        while (p + 4 <= copy.size) {
            copy[p] = (copy[p].toInt() and 0x7F).toByte()
            val length = ((copy[p + 1].toInt() and 0xFF) shl 16) or
                ((copy[p + 2].toInt() and 0xFF) shl 8) or
                (copy[p + 3].toInt() and 0xFF)
            p += 4 + length
        }
        return copy
    }

    private fun Bytes.blockHeader(type: Int, length: Int, last: Boolean) {
        byte(if (last) type or 0x80 else type)
        byte((length ushr 16) and 0xFF)
        byte((length ushr 8) and 0xFF)
        byte(length and 0xFF)
    }

    // ---- vorbis comment -----------------------------------------------------------------------

    /**
     * A `VORBIS_COMMENT` body: a vendor string, then a count, then `KEY=value` entries — every
     * length little-endian.
     *
     * Field names are conventionally uppercase and are compared case-insensitively by everything
     * that reads them, so the lookup here folds case rather than trusting the writer.
     */
    private fun readComment(body: ByteArray): Pair<Tags, Kept>? {
        var p = 0
        val vendorLength = u32le(body, p) ?: return null
        p += 4 + vendorLength
        val count = u32le(body, p) ?: return null
        p += 4

        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var year = ""
        var genre = ""
        var track = ""
        var comment = ""
        var date = ""
        var composer = ""
        var bpm = ""
        var musicalKey = ""
        val others = ArrayList<String>()

        repeat(count) {
            val length = u32le(body, p) ?: return@repeat
            p += 4
            if (p + length > body.size) return@repeat
            val entry = String(body, p, length, Charsets.UTF_8)
            p += length
            val key = entry.substringBefore('=', "").uppercase()
            val value = entry.substringAfter('=', "").trim()
            if (key !in OWNED) {
                others += entry
                return@repeat
            }
            if (value.isEmpty()) return@repeat
            when (key) {
                "TITLE" -> title = value
                "ARTIST" -> artist = value
                "ALBUM" -> album = value
                "ALBUMARTIST", "ALBUM ARTIST" -> albumArtist = value
                "DATE", "YEAR" -> {
                    year = YEAR.find(value)?.value.orEmpty()
                    date = value
                }
                "GENRE" -> genre = value
                "TRACKNUMBER" -> track = value.substringBefore('/').trim()
                "COMMENT", "DESCRIPTION" -> comment = value
                "COMPOSER" -> composer = value
                "BPM" -> bpm = value
                "INITIALKEY", "KEY" -> musicalKey = value
            }
        }

        val tags = Tags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            genre = genre,
            track = track,
            comment = comment,
            composer = composer,
            bpm = bpm,
            key = musicalKey,
        )
        return tags to Kept(others, date)
    }

    private fun commentBlock(tags: Tags, kept: Kept): ByteArray {
        val fields = buildList {
            if (tags.title.isNotEmpty()) add("TITLE=${tags.title}")
            if (tags.artist.isNotEmpty()) add("ARTIST=${tags.artist}")
            if (tags.album.isNotEmpty()) add("ALBUM=${tags.album}")
            if (tags.albumArtist.isNotEmpty()) add("ALBUMARTIST=${tags.albumArtist}")
            // The full date while it still agrees with the form's year — see [TagIo.Kept.date].
            val date = kept.date.takeIf { tags.year.isNotEmpty() && it.startsWith(tags.year) }
            if (tags.year.isNotEmpty()) add("DATE=${date ?: tags.year}")
            if (tags.genre.isNotEmpty()) add("GENRE=${tags.genre}")
            if (tags.track.isNotEmpty()) add("TRACKNUMBER=${tags.track}")
            if (tags.comment.isNotEmpty()) add("COMMENT=${tags.comment}")
            if (tags.composer.isNotEmpty()) add("COMPOSER=${tags.composer}")
            if (tags.bpm.isNotEmpty()) add("BPM=${tags.bpm}")
            if (tags.key.isNotEmpty()) add("INITIALKEY=${tags.key}")
            addAll(kept.entries)
        }
        val vendor = VENDOR.toByteArray(Charsets.UTF_8)
        val encoded = fields.map { it.toByteArray(Charsets.UTF_8) }

        val out = Bytes(vendor.size + encoded.sumOf { it.size + 4 } + 8)
        out.u32le(vendor.size)
        out.raw(vendor)
        out.u32le(encoded.size)
        encoded.forEach {
            out.u32le(it.size)
            out.raw(it)
        }
        return out.toByteArray()
    }

    // ---- picture ------------------------------------------------------------------------------

    /** The image bytes out of a `PICTURE` body, skipping the descriptive fields in front of them. */
    private fun readPicture(body: ByteArray): ByteArray? {
        var p = 4 // picture type
        val mimeLength = u32be(body, p) ?: return null
        p += 4 + mimeLength
        val descriptionLength = u32be(body, p) ?: return null
        p += 4 + descriptionLength
        // width, height, depth, colours used
        p += 16
        val dataLength = u32be(body, p) ?: return null
        p += 4
        if (dataLength <= 0 || p + dataLength > body.size) return null
        return body.copyOfRange(p, p + dataLength)
    }

    /**
     * A `PICTURE` body, as a front cover.
     *
     * "Colours used" is 0, which the format defines as *not applicable* — it is for indexed images,
     * and this app only ever writes JPEG.
     */
    private fun pictureBlock(cover: Artwork): ByteArray {
        val mime = cover.mime.toByteArray(Charsets.US_ASCII)
        val out = Bytes(cover.bytes.size + mime.size + 40)
        out.u32(PICTURE_FRONT_COVER)
        out.u32(mime.size)
        out.raw(mime)
        out.u32(0) // empty description
        out.u32(cover.width)
        out.u32(cover.height)
        out.u32(COLOUR_DEPTH)
        out.u32(0) // colours used — not applicable
        out.u32(cover.bytes.size)
        out.raw(cover.bytes)
        return out.toByteArray()
    }

    // ---- primitives ---------------------------------------------------------------------------

    /** Big-endian, and null rather than an exception when the field runs off the end. */
    private fun u32be(b: ByteArray, at: Int): Int? {
        if (at < 0 || at + 4 > b.size) return null
        val v = ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
        return if (v < 0) null else v
    }

    /** Little-endian — the Vorbis half of the file. See the class doc. */
    private fun u32le(b: ByteArray, at: Int): Int? {
        if (at < 0 || at + 4 > b.size) return null
        val v = (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)
        return if (v < 0) null else v
    }

    /** [InputStream.read] may return short reads; a block parser cannot live with that. */
    private fun InputStream.readFully(into: ByteArray): Boolean {
        var got = 0
        while (got < into.size) {
            val n = read(into, got, into.size - got)
            if (n <= 0) return false
            got += n
        }
        return true
    }

    private const val TYPE_PADDING = 1
    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6

    /** The format's own ceiling: a block length is three bytes. */
    private const val MAX_BLOCK = 0xFFFFFF

    /** `PICTURE` type 3 — the front cover, which is the only kind this app writes. */
    private const val PICTURE_FRONT_COVER = 3

    /** Bits per pixel of the JPEG this app writes: eight each of red, green and blue. */
    private const val COLOUR_DEPTH = 24

    /**
     * The vendor string, which by convention names what wrote the tags. Deliberately not
     * translatable and deliberately not the localised app name — it is a machine-readable
     * provenance marker that shows up in `metaflac` output, not a label anyone reads in the app.
     */
    private const val VENDOR = "Demo Reel"

    private val YEAR = Regex("""\d{4}""")

    /** The comment keys the editor owns; every other entry is carried — see [Kept]. */
    private val OWNED = setOf(
        "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "ALBUM ARTIST", "DATE", "YEAR", "GENRE",
        "TRACKNUMBER", "COMMENT", "DESCRIPTION", "COMPOSER", "BPM", "INITIALKEY", "KEY",
    )

    /** Key prefixes measured on the audio, dropped from a new copy's tags. */
    private val AUDIO_KEYS = listOf("REPLAYGAIN_", "R128_", "ENCODER", "ITUNNORM", "ITUNSMPB", "ITUNPGAP")
}
