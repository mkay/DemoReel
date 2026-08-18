// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

/**
 * The RIFF/WAVE header a rendered mix is wrapped in.
 *
 * Writing only, and only the 44 bytes in front of the audio: this app never *records*, so the one
 * time it produces a WAV is when a normalise or a convert has a file of PCM in the cache and needs
 * a container to put it in. By then the payload length is known, which is what makes the header a
 * single write with nothing to seek back and patch — the thing a SAF output stream cannot do.
 *
 * A WAV carries no tags this app writes (see [TagFormat]), so nothing goes between `fmt ` and
 * `data`: a converted WAV is the audio and its shape, and the title stays in the filename.
 */
object Wav {

    const val MIME = "audio/x-wav"

    const val BITS_PER_SAMPLE = 16

    /** The 44-byte header preceding [dataBytes] bytes of interleaved little-endian 16-bit PCM. */
    fun header(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
        val out = Builder(44)
        val blockAlign = channels * BITS_PER_SAMPLE / 8
        val byteRate = sampleRate.toLong() * blockAlign

        out.ascii("RIFF")
        // Everything after this field: "WAVE" + the fmt chunk (24) + the data chunk header (8).
        out.u32(4L + 24L + 8L + dataBytes)
        out.ascii("WAVE")

        out.ascii("fmt ")
        out.u32(16)
        out.u16(1) // PCM, uncompressed
        out.u16(channels)
        out.u32(sampleRate.toLong())
        out.u32(byteRate)
        out.u16(blockAlign)
        out.u16(BITS_PER_SAMPLE)

        out.ascii("data")
        out.u32(dataBytes)
        return out.toByteArray()
    }

    /** A little-endian byte sink; RIFF is little-endian throughout. */
    private class Builder(capacity: Int) {
        private val buf = ByteArray(capacity)
        private var size = 0

        fun ascii(s: String) {
            for (c in s) buf[size++] = c.code.toByte()
        }

        fun u16(v: Int) {
            buf[size++] = (v and 0xFF).toByte()
            buf[size++] = ((v ushr 8) and 0xFF).toByte()
        }

        fun u32(v: Long) {
            buf[size++] = (v and 0xFF).toByte()
            buf[size++] = ((v ushr 8) and 0xFF).toByte()
            buf[size++] = ((v ushr 16) and 0xFF).toByte()
            buf[size++] = ((v ushr 24) and 0xFF).toByte()
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }
}
