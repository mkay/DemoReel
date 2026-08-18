// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import java.io.IOException
import java.io.OutputStream

/**
 * Writing MP3, which is the one thing on this list the platform will not do.
 *
 * Android decodes MP3 on every device and encodes it on none: [android.media.MediaCodec] offers AAC
 * and AMR and stops there. So an encoder is carried — LAME, vendored under `src/main/cpp/lame` and
 * reached through the JNI glue in `src/main/cpp/mp3.c`.
 *
 * **Why carry one at all**, when FLAC and WAV are written here without any of that: because MP3 is
 * what a demo mix is sent as. A folder of masters is FLAC, and the copy that goes to a promoter, a
 * label or a phone is an MP3 — an app that can convert a mix to everything except the format it
 * will actually be shared in has not done the job.
 */
object Mp3 {

    const val MIME = "audio/mpeg"

    /**
     * What a converted mix is written at.
     *
     * 320 kbit/s CBR: this is a *master* being turned into a shareable copy, once, and the encode is
     * the only lossy step it will ever go through here. The choice is between a file a third smaller
     * and one nobody can fault the sound of, and for the file a mix gets judged from that is not a
     * close call. CBR rather than VBR because [Writer] cannot write the Xing header a VBR file wants
     * — see the note in `mp3.c`.
     */
    const val BITRATE_KBPS = 320

    private const val LIBRARY = "demoreel_mp3"

    /** Whether the encoder loaded at all. False leaves MP3 off the list rather than failing later. */
    val available: Boolean = runCatching { System.loadLibrary(LIBRARY) }.isSuccess

    /**
     * LAME's own worst case for the frames [pcmBytes] of PCM can produce: 1.25 samples plus 7200.
     *
     * Sized per call rather than guessed at, because an undersized output buffer is not a truncated
     * write — LAME refuses the call and the frames are simply lost.
     */
    fun bufferBytes(pcmBytes: Int): Int = (pcmBytes / 2 * 5 / 4) + 7200

    /**
     * A sink that takes interleaved little-endian 16-bit PCM and leaves a complete `.mp3` on [out].
     *
     * The same shape as [Flac.Writer] — an [OutputStream], so the caller's choice of format is a
     * choice of stream and nothing else — but unlike FLAC it needs no cache file: MP3 frames are
     * self-contained and there is no header at the front waiting on a total sample count.
     *
     * Closing flushes the encoder and releases it, but does not close [out]: the caller opened it.
     */
    class Writer(
        private val out: OutputStream,
        sampleRate: Int,
        channels: Int,
        bitrateKbps: Int = BITRATE_KBPS,
    ) : OutputStream() {

        private val handle = nativeInit(sampleRate, channels, bitrateKbps)
        private val bytesPerFrame = 2 * channels

        /** Grown as needed; an encode of a few dozen KB reuses the same buffer for the whole file. */
        private var frames = ByteArray(0)

        /** A partial frame across a write boundary — LAME is fed whole frames or nothing. */
        private val pending = ByteArray(bytesPerFrame)
        private var pendingSize = 0

        private var closed = false

        init {
            if (handle == 0L) throw IOException("The MP3 encoder would not start.")
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "write after close" }
            var at = off
            var left = len
            if (pendingSize > 0) {
                val want = minOf(bytesPerFrame - pendingSize, left)
                System.arraycopy(b, at, pending, pendingSize, want)
                pendingSize += want
                at += want
                left -= want
                if (pendingSize == bytesPerFrame) {
                    encode(pending, 0, bytesPerFrame)
                    pendingSize = 0
                }
            }
            val whole = left - left % bytesPerFrame
            if (whole > 0) encode(b, at, whole)
            val rest = left - whole
            if (rest > 0) {
                System.arraycopy(b, at + whole, pending, 0, rest)
                pendingSize = rest
            }
        }

        /**
         * Finish the file: flush LAME's last partial frame and release the encoder.
         *
         * A trailing partial *sample frame* is dropped rather than padded — it is a fraction of a
         * millisecond of audio that arrived without all its channels, and inventing the rest of it
         * would be worse than losing it.
         */
        override fun close() {
            if (closed) return
            closed = true
            try {
                val buffer = grow(0)
                val written = nativeFlush(handle, buffer)
                if (written < 0) throw IOException("The MP3 encoder failed on the last frame.")
                if (written > 0) out.write(buffer, 0, written)
            } finally {
                nativeClose(handle)
            }
        }

        private fun encode(b: ByteArray, off: Int, len: Int) {
            val buffer = grow(len)
            val written = nativeEncode(handle, b, off, len, buffer)
            if (written < 0) throw IOException("The MP3 encoder failed: $written")
            if (written > 0) out.write(buffer, 0, written)
        }

        private fun grow(pcmBytes: Int): ByteArray {
            val want = bufferBytes(pcmBytes)
            if (frames.size < want) frames = ByteArray(want)
            return frames
        }
    }

    @JvmStatic
    private external fun nativeInit(sampleRate: Int, channels: Int, bitrateKbps: Int): Long

    @JvmStatic
    private external fun nativeEncode(
        handle: Long,
        pcm: ByteArray,
        offset: Int,
        count: Int,
        mp3: ByteArray,
    ): Int

    @JvmStatic
    private external fun nativeFlush(handle: Long, mp3: ByteArray): Int

    @JvmStatic
    private external fun nativeClose(handle: Long)
}
