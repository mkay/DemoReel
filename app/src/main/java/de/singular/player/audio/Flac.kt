// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.File
import java.io.OutputStream

/**
 * Writing the native FLAC container.
 *
 * Reading a FLAC's *tags* is [FlacTags]' job; this is the other half — the one an edit needs, which
 * is putting audio into a `.flac` in the first place.
 *
 * **Why a container by hand.** [MediaCodec] encodes the frames but will not wrap them:
 * [android.media.MediaMuxer] has no native `.flac` output, only FLAC inside MP4, which is not what
 * a folder of mixes wants. So the stream is laid out here — magic, STREAMINFO, a Vorbis comment,
 * frames — exactly as [Wav] lays out RIFF.
 *
 * **Why the frames are buffered.** STREAMINFO carries the total sample count, and that is not known
 * until the last frame is encoded, while a SAF output stream cannot be seeked back to patch it. So
 * frames go to a cache file, and the header is written in front of them at the end.
 *
 * The comment block written here says only who encoded the file. Everything a person tagged is put
 * back afterwards by [TagIo], which rewrites this block properly — cover art and all.
 */
object Flac {

    const val MIME = "audio/flac"

    /** FLAC's own name for itself, at the head of every stream. */
    private const val MAGIC = "fLaC"

    /** STREAMINFO is fixed at 34 bytes, and always the first metadata block. */
    private const val STREAMINFO_BYTES = 34

    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_VORBIS_COMMENT = 4

    /** Middling: the encoder's own default, and the last levels buy little for the time they cost. */
    private const val COMPRESSION_LEVEL = 5

    /** 36 bits of sample count, the odd size FLAC actually specifies. */
    private const val TOTAL_SAMPLES_MASK = 0xF_FFFF_FFFFL

    private const val BYTES_PER_SAMPLE = 2

    /** As [AudioDecoder]'s: long enough not to spin, short enough to keep the encoder fed. */
    private const val TIMEOUT_US = 10_000L

    private const val VENDOR = "Demo Reel"

    /**
     * A sink that takes interleaved little-endian 16-bit PCM and leaves a complete `.flac` on [out].
     *
     * An [OutputStream] on purpose: the caller already writes PCM to one — scaled by a gain, or
     * passed straight through — and this way choosing FLAC over WAV swaps the stream and changes
     * nothing else.
     *
     * Nothing reaches [out] until [close], which is when the header can finally be written. Closing
     * does not close [out]: the caller opened it and `use` will close it.
     */
    class Writer(
        private val out: OutputStream,
        private val sampleRate: Int,
        private val channels: Int,
        cacheDir: File,
    ) : OutputStream() {

        private val frames = File(cacheDir, "flac-frames.bin")
        private val framesOut = frames.outputStream().buffered()
        private val codec = MediaCodec.createEncoderByType(MIME)
        private val info = MediaCodec.BufferInfo()

        /** STREAMINFO as the encoder reports it, before the sample count is patched in. */
        private var streamInfo: ByteArray? = null
        private var totalFrames = 0L
        private var closed = false

        /** A partial sample across a write boundary — PCM only means anything in whole samples. */
        private var pending = ByteArray(BYTES_PER_SAMPLE)
        private var pendingSize = 0

        init {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, COMPRESSION_LEVEL)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "write after close" }
            var at = off
            var left = len
            // The encoder is fed whole samples; an odd byte waits here for the rest of its sample.
            if (pendingSize > 0) {
                val want = minOf(BYTES_PER_SAMPLE - pendingSize, left)
                System.arraycopy(b, at, pending, pendingSize, want)
                pendingSize += want
                at += want
                left -= want
                if (pendingSize == BYTES_PER_SAMPLE) {
                    feed(pending, 0, BYTES_PER_SAMPLE)
                    pendingSize = 0
                }
            }
            val whole = left - left % BYTES_PER_SAMPLE
            if (whole > 0) feed(b, at, whole)
            val rest = left - whole
            if (rest > 0) {
                System.arraycopy(b, at + whole, pending, 0, rest)
                pendingSize = rest
            }
        }

        /**
         * Finish the stream: flush the encoder, then write magic, header and frames to [out].
         *
         * Failure still tears the codec down and deletes the cache file — a half-encoded mix is of
         * no use to anyone, and the caller deletes the document it was going into.
         */
        override fun close() {
            if (closed) return
            closed = true
            try {
                signalEndOfStream()
                drain(untilEndOfStream = true)
                framesOut.flush()
                framesOut.close()
                val header = streamInfo
                    ?: throw IllegalStateException("The FLAC encoder never described its stream.")
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.write(metadataBlock(BLOCK_STREAMINFO, withTotalSamples(header), last = false))
                out.write(metadataBlock(BLOCK_VORBIS_COMMENT, vorbisComment(), last = true))
                frames.inputStream().use { it.copyTo(out) }
            } finally {
                runCatching { framesOut.close() }
                runCatching { codec.stop() }
                runCatching { codec.release() }
                frames.delete()
            }
        }

        /** Hand [len] bytes to the encoder, draining whatever it has ready as we go. */
        private fun feed(b: ByteArray, off: Int, len: Int) {
            var at = off
            var left = len
            while (left > 0) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) {
                    drain(untilEndOfStream = false)
                    continue
                }
                val buffer = codec.getInputBuffer(index) ?: continue
                buffer.clear()
                val n = minOf(buffer.remaining(), left)
                buffer.put(b, at, n)
                codec.queueInputBuffer(index, 0, n, presentationTimeUs(), 0)
                totalFrames += n / (BYTES_PER_SAMPLE * channels)
                at += n
                left -= n
                drain(untilEndOfStream = false)
            }
        }

        private fun signalEndOfStream() {
            while (true) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(
                        index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                    )
                    return
                }
                drain(untilEndOfStream = false)
            }
        }

        /** Move whatever the encoder has produced into the frames file. */
        private fun drain(untilEndOfStream: Boolean) {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        streamInfo = streamInfoFrom(codec.outputFormat) ?: streamInfo

                    MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEndOfStream) return

                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            // The codec-config buffer is the header, not audio: it describes the
                            // stream and is written in front of the frames, never among them.
                            if (config) streamInfo = streamInfoOf(bytes) else framesOut.write(bytes)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        /**
         * Presentation timestamps the encoder will accept.
         *
         * FLAC does not carry them and nothing downstream reads them, but a codec fed a stream of
         * zeroes is entitled to complain, so they follow the samples actually queued.
         */
        private fun presentationTimeUs(): Long =
            if (sampleRate <= 0) 0 else totalFrames * 1_000_000L / sampleRate

        /** STREAMINFO with the sample count filled in — see the note on buffering, above. */
        private fun withTotalSamples(header: ByteArray): ByteArray {
            val patched = header.copyOf()
            // Bytes 10..17 are one 64-bit field: rate (20), channels (3), depth (5), samples (36).
            var packed = 0L
            for (i in 0 until 8) packed = (packed shl 8) or (patched[10 + i].toLong() and 0xFF)
            packed = (packed and TOTAL_SAMPLES_MASK.inv()) or (totalFrames and TOTAL_SAMPLES_MASK)
            for (i in 0 until 8) patched[10 + i] = ((packed ushr ((7 - i) * 8)) and 0xFF).toByte()
            return patched
        }

        /** A Vorbis comment is little-endian, alone among FLAC's structures. */
        private fun vorbisComment(): ByteArray {
            val vendor = VENDOR.toByteArray(Charsets.UTF_8)
            val field = "ENCODER=$VENDOR".toByteArray(Charsets.UTF_8)
            val out = ByteArray(4 + vendor.size + 4 + 4 + field.size)
            var at = 0
            fun u32le(v: Int) {
                out[at++] = (v and 0xFF).toByte()
                out[at++] = ((v ushr 8) and 0xFF).toByte()
                out[at++] = ((v ushr 16) and 0xFF).toByte()
                out[at++] = ((v ushr 24) and 0xFF).toByte()
            }
            u32le(vendor.size)
            System.arraycopy(vendor, 0, out, at, vendor.size)
            at += vendor.size
            u32le(1)
            u32le(field.size)
            System.arraycopy(field, 0, out, at, field.size)
            return out
        }
    }

    /**
     * The 34 STREAMINFO bytes out of whatever the encoder called codec config.
     *
     * Encoders differ on how much of the stream's head they hand back: some the bare block, some
     * the whole thing from the magic onwards. Both are recognised rather than one being assumed.
     */
    private fun streamInfoOf(csd: ByteArray): ByteArray? = when {
        csd.size >= 4 + 4 + STREAMINFO_BYTES &&
            String(csd, 0, 4, Charsets.US_ASCII) == MAGIC ->
            csd.copyOfRange(8, 8 + STREAMINFO_BYTES)

        csd.size >= STREAMINFO_BYTES -> csd.copyOfRange(0, STREAMINFO_BYTES)

        else -> null
    }

    private fun streamInfoFrom(format: MediaFormat): ByteArray? {
        val csd = runCatching { format.getByteBuffer("csd-0") }.getOrNull() ?: return null
        val bytes = ByteArray(csd.remaining())
        csd.duplicate().get(bytes)
        return streamInfoOf(bytes)
    }

    /** A metadata block header — last-block flag, type, 24-bit length — and its body. */
    private fun metadataBlock(type: Int, body: ByteArray, last: Boolean): ByteArray {
        val out = ByteArray(4 + body.size)
        out[0] = (type or if (last) 0x80 else 0).toByte()
        out[1] = ((body.size ushr 16) and 0xFF).toByte()
        out[2] = ((body.size ushr 8) and 0xFF).toByte()
        out[3] = (body.size and 0xFF).toByte()
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }
}
