// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

/**
 * ID3v2, read and written.
 *
 * ## Why this exists at all, when the platform can already read tags
 *
 * [Tags.read] asks [android.media.MediaMetadataRetriever], which reads every format at once and is
 * exactly right for filling a listing. It cannot write, and it is also lossy in one direction that
 * matters here: it has no key for a *comment*, and its nearest neighbours (`WRITER`, `AUTHOR`) mean
 * other things. So an editor that read through it would show a field it could not have filled and
 * would blank whatever was there on save.
 *
 * Round-tripping is the whole job of an editor, so the editor reads with [read] and writes with
 * [build], and the two agree by construction.
 *
 * ## Which version is written
 *
 * **v2.4, always**, whatever came in. Reading handles v2.3 as well, because that is what most files
 * in the wild carry, and the two differ in ways a parser has to know about: v2.4 sizes frames with
 * synchsafe integers and v2.3 with plain ones, and v2.4 adds the UTF-8 text encoding that lets a
 * tag hold anything without the UTF-16 byte-order dance.
 *
 * v2.2, with its three-character frame ids, is *skipped* rather than parsed — the offset is found
 * correctly so the audio is never damaged, but its fields are not read. It predates 1998 and nothing
 * has written one this century; supporting it would be a second frame table for files that do not
 * arrive.
 *
 * Writing v2.4 rather than v2.3 is the one compatibility call here. Android's own parser handles
 * both, as does every desktop player still maintained; a few very old hardware players read only
 * v2.3. Correctness wins, because the alternative is writing UTF-16 for every non-ASCII character
 * to keep a player from 2004 happy.
 *
 * ## Unsynchronisation
 *
 * Not applied. It exists so that a decoder scanning for a frame sync can never trip over one inside
 * the tag, and it is unnecessary in practice because every decoder written since the size field
 * existed uses the size field. Applying it would inflate the tag and is what the reference
 * implementations stopped doing years ago.
 */
object Id3 {

    /**
     * How many bytes at the head of [head] belong to an ID3v2 tag, or 0 if there is no tag there.
     *
     * This is the number the audio starts after, and it is the one thing in this file that must be
     * right even for a tag version we do not understand — getting it wrong writes a new tag in front
     * of a stale one, or worse, in front of half of one.
     *
     * [head] only has to be the first ten bytes; the size is in the header.
     */
    fun tagSize(head: ByteArray): Int {
        if (head.size < HEADER_BYTES) return 0
        if (head[0] != 'I'.code.toByte() ||
            head[1] != 'D'.code.toByte() ||
            head[2] != '3'.code.toByte()
        ) {
            return 0
        }
        // A major version of 0xFF is reserved and marks a header that is not one.
        if ((head[3].toInt() and 0xFF) >= 0xFF) return 0
        val flags = head[5].toInt() and 0xFF
        val size = synchsafe(head, 6)
        if (size <= 0) return 0
        // The footer, when present, is ten more bytes that the size field does not count.
        val footer = if (flags and FLAG_FOOTER != 0) HEADER_BYTES else 0
        return HEADER_BYTES + size + footer
    }

    /**
     * The fields in [tag] — a whole ID3v2 tag, header and all, as [tagSize] measures it.
     *
     * Returns empty tags rather than null for a tag this parser will not read (v2.2, or one whose
     * frames are corrupt): the file plainly has *something*, and an editor opening onto empty fields
     * that it is about to overwrite is the honest presentation of "nothing here I can show you".
     */
    fun read(tag: ByteArray): Tags {
        if (tag.size < HEADER_BYTES) return Tags()
        val major = tag[3].toInt() and 0xFF
        if (major < 3) return Tags() // v2.2 and older — see the class doc
        val flags = tag[5].toInt() and 0xFF
        var p = HEADER_BYTES

        // The extended header is counted inside the tag size, so its length has to come off the
        // front before the frames start. v2.4 states that length synchsafe and includes itself in
        // it; v2.3 states it plain and excludes its own four bytes.
        if (flags and FLAG_EXTENDED != 0 && p + 4 <= tag.size) {
            p += if (major >= 4) synchsafe(tag, p) else plainInt(tag, p) + 4
        }

        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var year = ""
        var genre = ""
        var track = ""
        var comment = ""

        while (p + FRAME_HEADER_BYTES <= tag.size) {
            val id = String(tag, p, 4, Charsets.US_ASCII)
            // A run of zero bytes is the padding that follows the last frame.
            if (id[0] == '\u0000') break
            val size = if (major >= 4) synchsafe(tag, p + 4) else plainInt(tag, p + 4)
            val body = p + FRAME_HEADER_BYTES
            if (size <= 0 || body + size > tag.size) break

            when (id) {
                "TIT2" -> title = text(tag, body, size)
                "TPE1" -> artist = text(tag, body, size)
                "TALB" -> album = text(tag, body, size)
                "TPE2" -> albumArtist = text(tag, body, size)
                // v2.4 replaced TYER with TDRC, whose value is a whole timestamp; either way the
                // four digits are the part a demo mix has any use for.
                "TDRC", "TYER" -> year = YEAR.find(text(tag, body, size))?.value.orEmpty()
                "TCON" -> genre = plainGenre(text(tag, body, size))
                "TRCK" -> track = text(tag, body, size).substringBefore('/').trim()
                "COMM" -> comment = commentText(tag, body, size).ifEmpty { comment }
            }
            p = body + size
        }

        return Tags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            genre = genre,
            track = track,
            comment = comment,
        )
    }

    /** The cover in [tag] — the front-cover `APIC` frame if there is one, else any picture. */
    fun readPicture(tag: ByteArray): ByteArray? {
        if (tag.size < HEADER_BYTES) return null
        val major = tag[3].toInt() and 0xFF
        if (major < 3) return null
        var p = HEADER_BYTES
        val flags = tag[5].toInt() and 0xFF
        if (flags and FLAG_EXTENDED != 0 && p + 4 <= tag.size) {
            p += if (major >= 4) synchsafe(tag, p) else plainInt(tag, p) + 4
        }

        var fallback: ByteArray? = null
        while (p + FRAME_HEADER_BYTES <= tag.size) {
            val id = String(tag, p, 4, Charsets.US_ASCII)
            if (id[0] == '\u0000') break
            val size = if (major >= 4) synchsafe(tag, p + 4) else plainInt(tag, p + 4)
            val body = p + FRAME_HEADER_BYTES
            if (size <= 0 || body + size > tag.size) break
            if (id == "APIC") {
                val picture = apic(tag, body, size)
                // Type 3 is the front cover, and is the one to prefer when a file carries several
                // (a back cover, a band photo, a file icon). Anything else is kept as a fallback so
                // a file whose only picture is untyped still shows something.
                if (picture != null) {
                    if (picture.first == PICTURE_FRONT_COVER) return picture.second
                    if (fallback == null) fallback = picture.second
                }
            }
            p = body + size
        }
        return fallback
    }

    /** The picture type and bytes of one `APIC` frame body, or null if it is malformed. */
    private fun apic(tag: ByteArray, at: Int, size: Int): Pair<Int, ByteArray>? {
        if (size < 4) return null
        val end = at + size
        val encoding = tag[at].toInt() and 0xFF
        // The MIME type is always Latin-1 and null-terminated, whatever the text encoding says.
        var p = at + 1
        while (p < end && tag[p].toInt() != 0) p++
        if (p >= end - 1) return null
        p++ // the terminator
        val type = tag[p].toInt() and 0xFF
        p++
        // The description follows in the frame's own encoding, so a UTF-16 one ends on two zeroes.
        val wide = encoding == ENC_UTF16 || encoding == ENC_UTF16BE
        p = skipTerminated(tag, p, end, wide) ?: return null
        if (p >= end) return null
        return type to tag.copyOfRange(p, end)
    }

    /**
     * A whole ID3v2.4 tag for [tags], with [cover] as its front-cover picture.
     *
     * Empty fields are left out rather than written empty: a frame present and blank is not the same
     * as a frame absent, and some players show the first as an empty line where they would show
     * nothing for the second.
     */
    fun build(tags: Tags, cover: ByteArray?, coverMime: String): ByteArray {
        val frames = Bytes()
        frames.textFrame("TIT2", tags.title)
        frames.textFrame("TPE1", tags.artist)
        frames.textFrame("TALB", tags.album)
        frames.textFrame("TPE2", tags.albumArtist)
        frames.textFrame("TDRC", tags.year)
        frames.textFrame("TCON", tags.genre)
        frames.textFrame("TRCK", tags.track)
        frames.commentFrame(tags.comment)
        if (cover != null && cover.isNotEmpty()) frames.pictureFrame(cover, coverMime)

        val body = frames.toByteArray()
        val out = Bytes()
        out.ascii("ID3")
        out.byte(4) // major version
        out.byte(0) // revision
        out.byte(0) // no unsynchronisation, no extended header, no footer — see the class doc
        out.raw(synchsafeBytes(body.size))
        out.raw(body)
        return out.toByteArray()
    }

    // ---- frame writing ------------------------------------------------------------------------

    /** A text frame, in UTF-8, with no terminator. Nothing is written for an empty value. */
    private fun Bytes.textFrame(id: String, value: String) {
        if (value.isEmpty()) return
        val text = value.toByteArray(Charsets.UTF_8)
        frameHeader(id, text.size + 1)
        byte(ENC_UTF8)
        raw(text)
    }

    /**
     * The comment frame, which is not a text frame: it carries a three-letter language and a short
     * description before the text itself.
     *
     * `XXX` for the language, which the spec provides for exactly this — the comment is in whatever
     * language the person wrote it in and nothing here knows which. The description is left empty,
     * which is what makes this *the* comment rather than one of several keyed by name.
     */
    private fun Bytes.commentFrame(value: String) {
        if (value.isEmpty()) return
        val text = value.toByteArray(Charsets.UTF_8)
        // encoding + language + empty description and its terminator + the text
        frameHeader("COMM", 1 + 3 + 1 + text.size)
        byte(ENC_UTF8)
        ascii("XXX")
        byte(0)
        raw(text)
    }

    /** The front-cover picture, with an empty description. */
    private fun Bytes.pictureFrame(picture: ByteArray, mime: String) {
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        // encoding + mime and its terminator + picture type + empty description and its terminator
        frameHeader("APIC", 1 + mimeBytes.size + 1 + 1 + 1 + picture.size)
        byte(ENC_UTF8)
        raw(mimeBytes)
        byte(0)
        byte(PICTURE_FRONT_COVER)
        byte(0) // empty description
        raw(picture)
    }

    private fun Bytes.frameHeader(id: String, size: Int) {
        ascii(id)
        raw(synchsafeBytes(size))
        byte(0)
        byte(0)
    }

    // ---- reading helpers ----------------------------------------------------------------------

    /** A text frame body: an encoding byte, then the text in that encoding. */
    private fun text(tag: ByteArray, at: Int, size: Int): String {
        if (size <= 1) return ""
        val charset = when (tag[at].toInt() and 0xFF) {
            ENC_UTF16 -> Charsets.UTF_16 // the BOM decides the order, which UTF_16 honours
            ENC_UTF16BE -> Charsets.UTF_16BE
            ENC_UTF8 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        // Trailing terminators are optional and writers disagree about them; trimming zeroes off
        // the end is simpler than deciding whether this writer meant one.
        return String(tag, at + 1, size - 1, charset).trim('\u0000', ' ')
    }

    /** A `COMM` body: encoding, three-byte language, a description, then the comment itself. */
    private fun commentText(tag: ByteArray, at: Int, size: Int): String {
        if (size <= 5) return ""
        val end = at + size
        val encoding = tag[at].toInt() and 0xFF
        val wide = encoding == ENC_UTF16 || encoding == ENC_UTF16BE
        val afterDescription = skipTerminated(tag, at + 4, end, wide) ?: return ""
        if (afterDescription >= end) return ""
        val charset = when (encoding) {
            ENC_UTF16 -> Charsets.UTF_16
            ENC_UTF16BE -> Charsets.UTF_16BE
            ENC_UTF8 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(tag, afterDescription, end - afterDescription, charset).trim('\u0000', ' ')
    }

    /**
     * Step past a null-terminated string starting at [from], returning where the next field begins.
     *
     * [wide] strings terminate on two zero bytes aligned to the pair, not on any zero — half of
     * every ASCII character in UTF-16 *is* a zero byte, so scanning for a single one would stop
     * inside the first letter.
     */
    private fun skipTerminated(tag: ByteArray, from: Int, end: Int, wide: Boolean): Int? {
        var p = from
        if (wide) {
            while (p + 1 < end) {
                if (tag[p].toInt() == 0 && tag[p + 1].toInt() == 0) return p + 2
                p += 2
            }
        } else {
            while (p < end) {
                if (tag[p].toInt() == 0) return p + 1
                p++
            }
        }
        return null
    }

    /**
     * `(17)Rock` written by an old tagger becomes `Rock`.
     *
     * ID3v1 numbered its genres, and v2 kept the numbers usable by wrapping them in brackets in
     * front of the text. The number is only useful as a lookup into a table this app does not carry,
     * so where there is text beside it the text wins and the number goes. A value that is *only* a
     * number is left exactly as it is — showing `17` is unhelpful, but inventing a genre from a
     * table would be worse, and this app never writes one.
     */
    private fun plainGenre(raw: String): String {
        val stripped = raw.replace(GENRE_NUMBER, "").trim()
        return stripped.ifEmpty { raw.trim() }
    }

    private fun synchsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or
            ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or
            (b[at + 3].toInt() and 0x7F)

    private fun plainInt(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)

    /**
     * A size as four synchsafe bytes: seven bits each, the top bit of every byte left clear.
     *
     * The clear bit is the whole point — it makes a run of eleven set bits impossible inside a size
     * field, so a decoder scanning for an MPEG frame sync can never find one there.
     */
    private fun synchsafeBytes(v: Int): ByteArray = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(),
        ((v shr 14) and 0x7F).toByte(),
        ((v shr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte(),
    )

    const val HEADER_BYTES = 10
    private const val FRAME_HEADER_BYTES = 10

    private const val FLAG_EXTENDED = 0x40
    private const val FLAG_FOOTER = 0x10

    private const val ENC_UTF16 = 1
    private const val ENC_UTF16BE = 2
    private const val ENC_UTF8 = 3

    /** `APIC` picture type 3 — the front cover, which is the only kind this app writes. */
    private const val PICTURE_FRONT_COVER = 3

    private val YEAR = Regex("""\d{4}""")
    private val GENRE_NUMBER = Regex("""\(\d+\)""")
}

/** A growable byte buffer, so the writers above read as a list of what goes into the file. */
internal class Bytes(initial: Int = 256) {

    private var buf = ByteArray(initial)
    private var size = 0

    fun byte(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun raw(b: ByteArray) {
        ensure(b.size)
        b.copyInto(buf, size)
        size += b.size
    }

    fun ascii(s: String) = raw(s.toByteArray(Charsets.US_ASCII))

    /** Big-endian, which is what ID3 and FLAC's block headers and picture blocks both use. */
    fun u32(v: Int) {
        ensure(4)
        buf[size++] = ((v ushr 24) and 0xFF).toByte()
        buf[size++] = ((v ushr 16) and 0xFF).toByte()
        buf[size++] = ((v ushr 8) and 0xFF).toByte()
        buf[size++] = (v and 0xFF).toByte()
    }

    /** Little-endian, which is what a Vorbis comment uses and nothing else here does. */
    fun u32le(v: Int) {
        ensure(4)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v ushr 8) and 0xFF).toByte()
        buf[size++] = ((v ushr 16) and 0xFF).toByte()
        buf[size++] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun ensure(more: Int) {
        if (size + more <= buf.size) return
        var next = buf.size * 2
        while (next < size + more) next *= 2
        buf = buf.copyOf(next)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}
