// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The 44 bytes a converted WAV stands or falls on. */
class WavTest {

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `the header describes the audio behind it`() {
        val data = 44_100L * 4 * 3 // three seconds of 16-bit stereo
        val header = Wav.header(data, sampleRate = 44_100, channels = 2)

        assertEquals(44, header.size)
        assertEquals("RIFF", ascii(header, 0))
        assertEquals("WAVE", ascii(header, 8))
        assertEquals("fmt ", ascii(header, 12))
        assertEquals(16L, u32(header, 16))
        assertEquals(1, u16(header, 20)) // uncompressed PCM
        assertEquals(2, u16(header, 22))
        assertEquals(44_100L, u32(header, 24))
        assertEquals(44_100L * 4, u32(header, 28)) // byte rate
        assertEquals(4, u16(header, 32)) // block align
        assertEquals(16, u16(header, 34))
        assertEquals("data", ascii(header, 36))
        assertEquals(data, u32(header, 40))
        // RIFF's size covers everything after its own field.
        assertEquals(data + 36, u32(header, 4))
    }

    @Test
    fun `mono halves the rates the header quotes`() {
        val header = Wav.header(1000, sampleRate = 48_000, channels = 1)
        assertEquals(48_000L * 2, u32(header, 28))
        assertEquals(2, u16(header, 32))
    }
}
