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
 * v2.2 is read too. It looks obsolete — three-character frame ids, three-byte sizes, no flags — but
 * Logic Pro wrote it into every MP3 bounce until 10.5 in 2020, so it is what a folder of demo mixes
 * from those years carries. Its frames are mapped onto their v2.4 ids as they are read ([V22]), and
 * everything past that point sees one kind of tag. Saving such a file upgrades it to v2.4.
 *
 * ## What a rewrite keeps
 *
 * The eight fields of [Tags] and the cover are what the editor owns, and they are rebuilt from the
 * form. **Every other frame is carried across unchanged** — see [kept]. A file from Logic or a
 * desktop tagger carries tempo, key, composer and more that this app has no field for, and a save
 * that dropped them would cost the user data they never saw on screen. What is not carried: frames
 * a v2.4 tag may not hold, frames flagged to be discarded when the tag changes, and frames whose
 * bytes are compressed, encrypted or grouped, which cannot be moved without being understood.
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
     * Returns empty tags rather than null for a tag this parser will not read (one whose frames are
     * corrupt, or a v2.2 tag compressed as a whole): the file plainly has *something*, and an
     * editor opening onto empty fields that it is about to overwrite is the honest presentation of
     * "nothing here I can show you".
     */
    fun read(tag: ByteArray): Tags {
        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var year = ""
        var genre = ""
        var track = ""

        frames(tag).forEach { f ->
            when (f.id) {
                "TIT2" -> title = text(f.body)
                "TPE1" -> artist = text(f.body)
                "TALB" -> album = text(f.body)
                "TPE2" -> albumArtist = text(f.body)
                // v2.4 replaced TYER with TDRC, whose value is a whole timestamp; either way the
                // four digits are the part a demo mix has any use for. The rest of the timestamp is
                // not lost on a save — see [Kept.timestamp].
                "TDRC", "TYER" -> year = YEAR.find(text(f.body))?.value.orEmpty()
                "TCON" -> genre = plainGenre(text(f.body))
                "TRCK" -> track = text(f.body).substringBefore('/').trim()
            }
        }

        return Tags(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            genre = genre,
            track = track,
            comment = theComment(frames(tag))?.let { commentText(it.body) }.orEmpty(),
        )
    }

    /** The cover in [tag] — the front-cover `APIC` frame if there is one, else any picture. */
    fun readPicture(tag: ByteArray): ByteArray? {
        var fallback: ByteArray? = null
        frames(tag).filter { it.id == "APIC" }.forEach { f ->
            val picture = apic(f.body) ?: return@forEach
            // Type 3 is the front cover, and is the one to prefer when a file carries several (a
            // back cover, a band photo, a file icon). Anything else is kept as a fallback so a file
            // whose only picture is untyped still shows something.
            if (picture.first == PICTURE_FRONT_COVER) return picture.second
            if (fallback == null) fallback = picture.second
        }
        return fallback
    }

    /**
     * Everything in [tag] that a rewrite carries across unchanged — every frame but the ones the
     * editor rebuilds, as v2.4 frames ready to be written. See the class doc.
     */
    fun kept(tag: ByteArray): Kept {
        val all = frames(tag)
        val comment = theComment(all)
        val frames = all.mapNotNull { f ->
            when {
                f === comment || f.id in OWNED || f.id in V23_ONLY -> null
                !f.movable -> null
                else -> RENAMED_IN_V24[f.id]?.let { Frame(it, f.body, true) } ?: f
            }
        }
        return Kept(frames, timestamp(all))
    }

    /**
     * What a rewrite carries through: frames this app does not edit, already in v2.4 form, and the
     * file's full recording date.
     *
     * Opaque on purpose — nothing outside this file should need to know a frame's layout to keep it.
     */
    class Kept internal constructor(
        internal val frames: List<Frame>,
        /**
         * The recording date as precisely as the file stated it, `2019-03-22T20:59` or just `2019`.
         * [Tags.year] is only the year, and writing that back alone would drop the day and the time
         * Logic stamped on the bounce; [build] writes this instead while the year still agrees.
         */
        internal val timestamp: String,
    ) : TagIo.Kept {

        /**
         * Without the frames that describe the audio rather than the music — for a normalised or
         * converted copy, whose audio is new.
         *
         * Apple's gapless and loudness comments, a length, the encoder's name, replay gain and
         * volume adjustments were all measured on the old samples. Carried onto a re-encode they
         * would be confidently wrong: a player trusting `iTunSMPB` would trim the wrong number of
         * samples, and replay gain would undo the normalise it sits on.
         */
        override fun forNewAudio(): Kept = Kept(frames.filterNot { it.describesAudio() }, timestamp)

        override fun common(): TagIo.Common {
            fun value(id: String) = frames.firstOrNull { it.id == id }?.let { text(it.body) }.orEmpty()
            return TagIo.Common(
                bpm = value("TBPM"),
                key = value("TKEY"),
                composer = value("TCOM"),
                date = timestamp,
            )
        }

        companion object {
            val NONE = Kept(emptyList(), "")

            /** The frames for what another format carried — see [TagIo.Common]. */
            fun from(common: TagIo.Common): Kept {
                val frames = listOf("TBPM" to common.bpm, "TKEY" to common.key, "TCOM" to common.composer)
                    .filter { it.second.isNotEmpty() }
                    .map { (id, value) ->
                        Frame(id, byteArrayOf(ENC_UTF8.toByte()) + value.toByteArray(Charsets.UTF_8), true)
                    }
                return Kept(frames, common.date)
            }
        }
    }

    /**
     * One frame: its v2.4 id and its body. [movable] is false for a frame that must not be carried
     * — discarded on alteration by its own flags, or compressed, encrypted or grouped, so that its
     * body is not the plain bytes a v2.4 frame of that id would hold.
     */
    internal class Frame(val id: String, val body: ByteArray, val movable: Boolean) {

        fun describesAudio(): Boolean = when (id) {
            "TLEN", "TSSE", "RVA2", "RVAD", "MLLT", "SEEK", "ASPI", "EQU2" -> true
            "COMM" -> description(body, 4).let { d -> AUDIO_DESCRIPTIONS.any { d.startsWith(it, true) } }
            "TXXX" -> description(body, 1).let { d -> AUDIO_DESCRIPTIONS.any { d.startsWith(it, true) } }
            else -> false
        }
    }

    /**
     * Every frame in [tag], in order, with v2.2 ids mapped onto v2.4 ones and a v2.2 `PIC` turned
     * into an `APIC`. Stops at the padding, at the end, or at the first frame that does not fit —
     * a tag cut short still yields the frames before the cut.
     */
    private fun frames(tag: ByteArray): List<Frame> {
        if (tag.size < HEADER_BYTES) return emptyList()
        val major = tag[3].toInt() and 0xFF
        val flags = tag[5].toInt() and 0xFF
        val v22 = major < 3
        // A v2.2 tag with this bit set is compressed as a whole, with no scheme ever defined for
        // it. Nothing reads one; neither does this.
        if (v22 && flags and V22_FLAG_COMPRESSED != 0) return emptyList()
        // A tag unsynchronised as a whole has bytes inserted into its frames. Reading through them
        // is what this parser has always done for text, but they must not be carried into a tag
        // that does not declare them.
        val tagUnsynced = flags and FLAG_UNSYNCHRONISED != 0

        var p = HEADER_BYTES
        // The extended header is counted inside the tag size, so its length has to come off the
        // front before the frames start. v2.4 states that length synchsafe and includes itself in
        // it; v2.3 states it plain and excludes its own four bytes. v2.2 has none.
        if (!v22 && flags and FLAG_EXTENDED != 0 && p + 4 <= tag.size) {
            p += if (major >= 4) synchsafe(tag, p) else plainInt(tag, p) + 4
        }

        val idLength = if (v22) 3 else 4
        val headerLength = if (v22) V22_FRAME_HEADER_BYTES else FRAME_HEADER_BYTES
        val out = ArrayList<Frame>()
        while (p + headerLength <= tag.size) {
            // A run of zero bytes is the padding that follows the last frame.
            if (tag[p].toInt() == 0) break
            val rawId = String(tag, p, idLength, Charsets.US_ASCII)
            val size = when {
                v22 -> ((tag[p + 3].toInt() and 0xFF) shl 16) or
                    ((tag[p + 4].toInt() and 0xFF) shl 8) or
                    (tag[p + 5].toInt() and 0xFF)
                major >= 4 -> synchsafe(tag, p + 4)
                else -> plainInt(tag, p + 4)
            }
            val at = p + headerLength
            if (size <= 0 || at + size > tag.size) break
            val body = tag.copyOfRange(at, at + size)
            p = at + size

            if (v22) {
                // An id with no v2.4 counterpart is read past but not kept: there is nowhere to
                // put it in the tag that will be written.
                val id = V22[rawId] ?: continue
                val converted = if (id == "APIC") picFromV22(body) ?: continue else body
                out += Frame(id, converted, movable = !tagUnsynced)
            } else {
                val status = tag[at - 2].toInt() and 0xFF
                val format = tag[at - 1].toInt() and 0xFF
                val movable = !tagUnsynced && if (major >= 4) {
                    status and V24_DISCARD_ON_TAG_CHANGE == 0 && format and V24_FORMAT_BITS == 0
                } else {
                    status and V23_DISCARD_ON_TAG_CHANGE == 0 && format and V23_FORMAT_BITS == 0
                }
                out += Frame(rawId, body, movable)
            }
        }
        return out
    }

    /**
     * The one comment the editor shows and owns: the first with no description, which is what a
     * comment field in every tagger means. Failing that, the first that is not one of Apple's
     * machine-written ones — a file of those alone has no comment anyone typed.
     */
    private fun theComment(frames: List<Frame>): Frame? {
        val comments = frames.filter { it.id == "COMM" && commentText(it.body).isNotEmpty() }
        return comments.firstOrNull { description(it.body, 4).isEmpty() }
            ?: comments.firstOrNull { f ->
                description(f.body, 4).let { d -> AUDIO_DESCRIPTIONS.none { d.startsWith(it, true) } }
            }
    }

    /**
     * The recording date at the precision the file gave it: v2.4's `TDRC` whole, or v2.3's and
     * v2.2's year, `DDMM` date and `HHMM` time put back together into the same form.
     */
    private fun timestamp(frames: List<Frame>): String {
        frames.firstOrNull { it.id == "TDRC" }?.let { return text(it.body) }
        val year = frames.firstOrNull { it.id == "TYER" }?.let { text(it.body) }
            ?.takeIf { YEAR.matches(it) } ?: return ""
        val date = frames.firstOrNull { it.id == "TDAT" }?.let { text(it.body) }
            ?.takeIf { YEAR.matches(it) } ?: return year
        val stamp = "$year-${date.substring(2, 4)}-${date.substring(0, 2)}"
        val time = frames.firstOrNull { it.id == "TIME" }?.let { text(it.body) }
            ?.takeIf { YEAR.matches(it) } ?: return stamp
        return "${stamp}T${time.substring(0, 2)}:${time.substring(2, 4)}"
    }

    /**
     * A v2.2 `PIC` body as the `APIC` body it would be in v2.4. The only difference is the image
     * format: three letters, `JPG` or `PNG`, where `APIC` has a null-terminated MIME type.
     */
    private fun picFromV22(body: ByteArray): ByteArray? {
        if (body.size < 5) return null
        val format = String(body, 1, 3, Charsets.ISO_8859_1).uppercase()
        val mime = when (format) {
            "JPG" -> Artwork.MIME_JPEG
            "PNG" -> "image/png"
            else -> "image/${format.lowercase()}"
        }.toByteArray(Charsets.US_ASCII)
        val out = Bytes(body.size + mime.size)
        out.byte(body[0].toInt()) // encoding
        out.raw(mime)
        out.byte(0)
        out.raw(body.copyOfRange(4, body.size)) // picture type, description, data
        return out.toByteArray()
    }

    /** The picture type and bytes of one `APIC` frame body, or null if it is malformed. */
    private fun apic(body: ByteArray): Pair<Int, ByteArray>? {
        if (body.size < 4) return null
        val end = body.size
        val encoding = body[0].toInt() and 0xFF
        // The MIME type is always Latin-1 and null-terminated, whatever the text encoding says.
        var p = 1
        while (p < end && body[p].toInt() != 0) p++
        if (p >= end - 1) return null
        p++ // the terminator
        val type = body[p].toInt() and 0xFF
        p++
        // The description follows in the frame's own encoding, so a UTF-16 one ends on two zeroes.
        val wide = encoding == ENC_UTF16 || encoding == ENC_UTF16BE
        p = skipTerminated(body, p, end, wide) ?: return null
        if (p >= end) return null
        return type to body.copyOfRange(p, end)
    }

    /**
     * A whole ID3v2.4 tag for [tags], with [cover] as its front-cover picture, followed by the
     * frames [kept] carries over from the tag being replaced.
     *
     * Empty fields are left out rather than written empty: a frame present and blank is not the same
     * as a frame absent, and some players show the first as an empty line where they would show
     * nothing for the second.
     */
    fun build(
        tags: Tags,
        cover: ByteArray?,
        coverMime: String,
        kept: Kept = Kept.NONE,
    ): ByteArray {
        val frames = Bytes()
        frames.textFrame("TIT2", tags.title)
        frames.textFrame("TPE1", tags.artist)
        frames.textFrame("TALB", tags.album)
        frames.textFrame("TPE2", tags.albumArtist)
        // The file's own date and time while it still agrees with the year in the form; the year
        // alone once it has been changed, since the day and time were the old year's.
        val date = kept.timestamp.takeIf { tags.year.isNotEmpty() && it.startsWith(tags.year) }
        frames.textFrame("TDRC", date ?: tags.year)
        frames.textFrame("TCON", tags.genre)
        frames.textFrame("TRCK", tags.track)
        frames.commentFrame(tags.comment)
        if (cover != null && cover.isNotEmpty()) frames.pictureFrame(cover, coverMime)
        kept.frames.forEach { f ->
            frames.frameHeader(f.id, f.body.size)
            frames.raw(f.body)
        }

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
    private fun text(body: ByteArray): String {
        if (body.size <= 1) return ""
        // Trailing terminators are optional and writers disagree about them; trimming zeroes off
        // the end is simpler than deciding whether this writer meant one.
        return String(body, 1, body.size - 1, charsetOf(body[0])).trim('\u0000', ' ')
    }

    /** A `COMM` body: encoding, three-byte language, a description, then the comment itself. */
    private fun commentText(body: ByteArray): String {
        if (body.size <= 5) return ""
        val wide = isWide(body[0])
        val afterDescription = skipTerminated(body, 4, body.size, wide) ?: return ""
        if (afterDescription >= body.size) return ""
        return String(body, afterDescription, body.size - afterDescription, charsetOf(body[0]))
            .trim('\u0000', ' ')
    }

    /**
     * The description of a `COMM` or `TXXX` body, which starts at [from] — after the language for
     * the first, straight after the encoding byte for the second. Empty if there is none.
     */
    private fun description(body: ByteArray, from: Int): String {
        if (body.isEmpty() || from >= body.size) return ""
        val end = skipTerminated(body, from, body.size, isWide(body[0])) ?: return ""
        val terminator = if (isWide(body[0])) 2 else 1
        return String(body, from, end - terminator - from, charsetOf(body[0])).trim('\u0000', ' ')
    }

    private fun isWide(encoding: Byte): Boolean =
        (encoding.toInt() and 0xFF).let { it == ENC_UTF16 || it == ENC_UTF16BE }

    private fun charsetOf(encoding: Byte) = when (encoding.toInt() and 0xFF) {
        ENC_UTF16 -> Charsets.UTF_16 // the BOM decides the order, which UTF_16 honours
        ENC_UTF16BE -> Charsets.UTF_16BE
        ENC_UTF8 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
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
    private const val V22_FRAME_HEADER_BYTES = 6

    private const val FLAG_UNSYNCHRONISED = 0x80
    private const val FLAG_EXTENDED = 0x40
    private const val FLAG_FOOTER = 0x10
    private const val V22_FLAG_COMPRESSED = 0x40

    // A frame's own flags, which v2.3 and v2.4 lay out differently. The status byte's "discard on
    // tag alteration" asks exactly this: not to be carried into a changed tag. The format bits
    // are compression, encryption and grouping (and in v2.4 unsynchronisation and a length
    // prefix), each of which makes the body something other than the plain frame it would be.
    private const val V23_DISCARD_ON_TAG_CHANGE = 0x80
    private const val V23_FORMAT_BITS = 0xE0
    private const val V24_DISCARD_ON_TAG_CHANGE = 0x40
    private const val V24_FORMAT_BITS = 0x4F

    /** The frames the editor rebuilds from [Tags] and the cover — never carried. */
    private val OWNED = setOf("TIT2", "TPE1", "TALB", "TPE2", "TDRC", "TYER", "TCON", "TRCK", "APIC")

    /**
     * v2.3 frames v2.4 has no place for. The date and time parts are folded into `TDRC` instead
     * (see [Kept.timestamp]); the rest were replaced by frames of a different layout.
     */
    private val V23_ONLY = setOf("TDAT", "TIME", "TRDA", "TSIZ", "EQUA", "RVAD")

    /** v2.3 frames that v2.4 kept under a new name, with the body unchanged. */
    private val RENAMED_IN_V24 = mapOf("TORY" to "TDOR", "IPLS" to "TIPL")

    /** Descriptions of `COMM` and `TXXX` frames that measure the audio — see [Kept.forNewAudio]. */
    private val AUDIO_DESCRIPTIONS = listOf("iTunNORM", "iTunSMPB", "iTunPGAP", "REPLAYGAIN_")

    /**
     * v2.2's three-character ids and the v2.4 frames they became. The bodies are the same layout
     * for all of them but `PIC`, which [picFromV22] rewrites. Ids missing here have no v2.4 frame
     * and are skipped.
     */
    private val V22 = mapOf(
        "TT1" to "TIT1", "TT2" to "TIT2", "TT3" to "TIT3",
        "TP1" to "TPE1", "TP2" to "TPE2", "TP3" to "TPE3", "TP4" to "TPE4",
        "TAL" to "TALB", "TRK" to "TRCK", "TPA" to "TPOS", "TCO" to "TCON",
        "TYE" to "TYER", "TDA" to "TDAT", "TIM" to "TIME", "TOR" to "TORY",
        "TCM" to "TCOM", "TXT" to "TEXT", "TOA" to "TOPE", "TOT" to "TOAL", "TOL" to "TOLY",
        "TBP" to "TBPM", "TKE" to "TKEY", "TLA" to "TLAN", "TLE" to "TLEN", "TMT" to "TMED",
        "TCR" to "TCOP", "TPB" to "TPUB", "TEN" to "TENC", "TSS" to "TSSE", "TRC" to "TSRC",
        "TFT" to "TFLT", "TOF" to "TOFN", "TXX" to "TXXX",
        "COM" to "COMM", "ULT" to "USLT", "PIC" to "APIC", "UFI" to "UFID",
        "WAF" to "WOAF", "WAR" to "WOAR", "WAS" to "WOAS", "WCM" to "WCOM", "WCP" to "WCOP",
        "WPB" to "WPUB", "WXX" to "WXXX",
    )

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
