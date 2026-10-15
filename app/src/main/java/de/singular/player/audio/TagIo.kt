// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import java.io.InputStream
import java.io.OutputStream

/**
 * Reading and writing tags, per format, over plain streams.
 *
 * Streams rather than files or uris on purpose: the storage layer works in Storage Access Framework
 * documents, which are not files and cannot be seeked reliably, and everything here is a single
 * forward pass anyway. It also means the format code can be reasoned about — and tested — without an
 * Android provider anywhere near it.
 *
 * ## Reading here, and reading in [Tags.read]
 *
 * There are two readers in this app and they are for different jobs.
 *
 * [Tags.read] asks [android.media.MediaMetadataRetriever]. It handles every format the device knows,
 * it yields the duration in the same pass, and it is what fills a listing — thirty rows of title,
 * artist and length.
 *
 * This one parses the container itself, and is what the **editor** uses. The difference that forces
 * it: an editor must round-trip. Whatever it shows, it must be able to write back unchanged, and
 * the platform's reader is lossy in exactly the way that breaks — it has no notion of a comment, so
 * a comment already in a file would be invisible in the editor and gone after a save. A reader and
 * a writer that do not agree field for field is a data-loss bug waiting for someone to open a file
 * and press Save.
 *
 * So: the listing reads fast and approximately, the editor reads exactly.
 */
object TagIo {

    /**
     * What a file carries: its fields, its cover as the bytes the file itself holds, and whatever
     * else it carries that this app does not edit — null when nothing parsed the file closely
     * enough to say.
     */
    class Content(val tags: Tags, val cover: ByteArray?, val kept: Kept? = null)

    /**
     * The tags in a file that the editor does not own, held so a rewrite can carry them through —
     * tempo, key, composer and the rest. Each format has its own; see [Id3.Kept] and
     * [FlacTags.Kept].
     */
    sealed interface Kept {
        /** Without what was measured on the audio, for a copy whose audio is new. */
        fun forNewAudio(): Kept

        /** The part every format has a name for — see [Common]. */
        fun common(): Common
    }

    /**
     * What survives a change of format: the few fields beyond [Tags] that ID3 and Vorbis comments
     * both have an agreed name for, and the full recording date. Everything else a format carries
     * has no counterpart in the other, and a convert leaves it behind.
     *
     * | here       | ID3    | Vorbis comment |
     * |------------|--------|----------------|
     * | [bpm]      | `TBPM` | `BPM`          |
     * | [key]      | `TKEY` | `INITIALKEY`   |
     * | [composer] | `TCOM` | `COMPOSER`     |
     * | [date]     | `TDRC` | `DATE`         |
     */
    data class Common(
        val bpm: String = "",
        val key: String = "",
        val composer: String = "",
        val date: String = "",
    )

    /**
     * Everything [format] can tell us about the file behind [input], which must be positioned at
     * its very start.
     *
     * Null if the file is not the format it claims to be — a `.flac` that does not begin `fLaC`, or
     * a stream that ends mid-header. An mp3 with no tag at all is *not* a failure: it reads as empty
     * content, which is the ordinary state of a fresh bounce and exactly what the editor wants to
     * open onto.
     */
    fun read(format: TagFormat, input: InputStream): Content? = when (format) {
        TagFormat.MP3 -> readMp3(input)
        TagFormat.FLAC -> FlacTags.readLayout(input)?.let { Content(it.tags, it.picture, it.kept) }
    }

    /**
     * Copy the file behind [input] to [out], replacing its tags with [tags] and its cover with
     * [cover] — null meaning the written file carries no artwork at all.
     *
     * The audio is copied byte for byte and never decoded. Re-tagging a lossy file must not cost a
     * generation of quality, and re-tagging a lossless one must not cost an encode; only the
     * metadata in front of the audio changes.
     *
     * Everything else the file carries is kept — see [Kept]. [carry] null means the file's own,
     * which is what an edit wants. A render passes the original's instead, because the file it is
     * tagging is new and carries nothing of its own yet; one from another format brings what the
     * two formats share ([Common]) and nothing else.
     *
     * Returns false if the input is not the format it claims to be, in which case [out] holds
     * whatever was written before that was discovered and the caller must throw it away rather than
     * put it over the original.
     */
    fun write(
        format: TagFormat,
        input: InputStream,
        out: OutputStream,
        tags: Tags,
        cover: Artwork?,
        carry: Kept? = null,
    ): Boolean = when (format) {
        TagFormat.MP3 -> writeMp3(input, out, tags, cover, carry)
        TagFormat.FLAC -> writeFlac(input, out, tags, cover, carry)
    }

    // ---- mp3 ----------------------------------------------------------------------------------

    private fun readMp3(input: InputStream): Content {
        val header = ByteArray(Id3.HEADER_BYTES)
        if (!input.readFully(header)) return Content(Tags(), null)
        val total = Id3.tagSize(header)
        if (total <= Id3.HEADER_BYTES) return Content(Tags(), null)

        val tag = ByteArray(total)
        header.copyInto(tag)
        if (!input.readFully(tag, Id3.HEADER_BYTES, total - Id3.HEADER_BYTES)) {
            // A tag whose declared size runs past the end of the file. There is nothing to be
            // recovered from it, but the file is still a file, so this reads as untagged rather
            // than as a failure.
            return Content(Tags(), null)
        }
        return Content(Id3.read(tag), Id3.readPicture(tag), Id3.kept(tag))
    }

    private fun writeMp3(
        input: InputStream,
        out: OutputStream,
        tags: Tags,
        cover: Artwork?,
        carry: Kept?,
    ): Boolean {
        // Read whatever tag is there now: what it carries beyond the editor's fields goes into the
        // new one. The header has to be read either way, and if there is no tag those ten bytes are
        // audio and go straight back out in front of the rest.
        val header = ByteArray(Id3.HEADER_BYTES)
        // A file with fewer than ten bytes in it has no audio and no tag; refusing is better than
        // writing out a tag with nothing behind it, which is what a partial read here would produce.
        if (!input.readFully(header)) return false
        val total = Id3.tagSize(header)
        var own = Id3.Kept.NONE
        if (total > Id3.HEADER_BYTES) {
            val tag = ByteArray(total)
            header.copyInto(tag)
            if (!input.readFully(tag, Id3.HEADER_BYTES, total - Id3.HEADER_BYTES)) return false
            own = Id3.kept(tag)
        }
        val kept = when (carry) {
            null -> own
            is Id3.Kept -> carry
            else -> Id3.Kept.from(carry.common())
        }

        out.write(Id3.build(tags, cover?.bytes, cover?.mime ?: Artwork.MIME_JPEG, kept))
        // With no tag to replace, those ten bytes were the start of the audio and go back in front
        // of the rest of it.
        if (total == 0) out.write(header)
        copyDroppingId3v1(input, out)
        return true
    }

    /**
     * Copy the rest of [input] to [out], dropping a trailing ID3v1 tag if there is one.
     *
     * ID3v1 is the original tag format: exactly 128 bytes at the very end of the file, beginning
     * `TAG`. Files that have been through several taggers over the years commonly carry both it and
     * an ID3v2 tag, and they can disagree — which is fine while nothing is being edited, because
     * every reader written this century prefers v2. It stops being fine the moment this app writes
     * a new v2 tag: the file would then assert a new artist at the front and the old one at the
     * back, and something, somewhere, reads the back.
     *
     * So it goes. This is the one thing here that removes rather than replaces, and it is the only
     * honest option — the alternative is writing v1 as well, which means truncating every field to
     * thirty characters and mapping the genre onto a fixed table from 1996.
     *
     * The stream cannot be seeked, so the last 128 bytes are simply held back: at any moment this
     * has written everything except the most recent 128 bytes it has seen, and at the end it either
     * writes them or drops them.
     */
    private fun copyDroppingId3v1(input: InputStream, out: OutputStream) {
        val pending = ByteArray(ID3V1_BYTES)
        var pendingSize = 0
        val buf = ByteArray(BLOCK)

        while (true) {
            val n = input.read(buf)
            if (n <= 0) break

            val total = pendingSize + n
            // Whatever is beyond the last 128 bytes of what we now hold is safe to write.
            val writable = (total - ID3V1_BYTES).coerceAtLeast(0)

            // The writable part comes out of the held bytes first, then out of the fresh block.
            val fromPending = minOf(writable, pendingSize)
            if (fromPending > 0) out.write(pending, 0, fromPending)
            val fromBuf = writable - fromPending
            if (fromBuf > 0) out.write(buf, 0, fromBuf)

            // Refill the held bytes with the tail of the combined stream, in the same order.
            val keptFromPending = pendingSize - fromPending
            if (keptFromPending > 0) {
                pending.copyInto(pending, 0, fromPending, pendingSize)
            }
            val keptFromBuf = n - fromBuf
            if (keptFromBuf > 0) {
                buf.copyInto(pending, keptFromPending, fromBuf, n)
            }
            pendingSize = keptFromPending + keptFromBuf
        }

        val isId3v1 = pendingSize == ID3V1_BYTES &&
            pending[0] == 'T'.code.toByte() &&
            pending[1] == 'A'.code.toByte() &&
            pending[2] == 'G'.code.toByte()
        if (!isId3v1 && pendingSize > 0) out.write(pending, 0, pendingSize)
    }

    // ---- flac ---------------------------------------------------------------------------------

    private fun writeFlac(
        input: InputStream,
        out: OutputStream,
        tags: Tags,
        cover: Artwork?,
        carry: Kept?,
    ): Boolean {
        // Reading the layout also walks the stream to the first audio byte, which is where the
        // copy below has to start from — so the parse and the seek are the same pass.
        val layout = FlacTags.readLayout(input) ?: return false
        val kept = when (carry) {
            null -> layout.kept
            is FlacTags.Kept -> carry
            else -> FlacTags.Kept.from(carry.common())
        }
        out.write(FlacTags.build(layout.keptBlocks, tags, cover, kept))
        input.copyTo(out, BLOCK)
        return true
    }

    // ---- primitives ---------------------------------------------------------------------------

    private fun InputStream.readFully(into: ByteArray): Boolean =
        readFully(into, 0, into.size)

    private fun InputStream.readFully(into: ByteArray, at: Int, count: Int): Boolean {
        var got = 0
        while (got < count) {
            val n = read(into, at + got, count - got)
            if (n <= 0) return false
            got += n
        }
        return true
    }

    private const val BLOCK = 64 * 1024

    /** An ID3v1 tag is exactly this long, always, at the very end of the file. */
    private const val ID3V1_BYTES = 128
}
