// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level maths a normalise is made of.
 *
 * Worth testing without a device because it is the part that can be wrong *quietly*: a gain that is
 * a shade too high does not throw, it clips a master, and the file it clipped has already replaced
 * the original by the time anyone hears it.
 */
class GainTest {

    private fun pcm(vararg samples: Short): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun samples(bytes: ByteArray): List<Short> =
        (bytes.indices step 2).map {
            ((bytes[it + 1].toInt() shl 8) or (bytes[it].toInt() and 0xFF)).toShort()
        }

    @Test
    fun `peak mode lifts the loudest sample to full scale`() {
        val gain = Gain.linearFor(NormalizeMode.PEAK, peak = 0.5f, rms = 0.1f)
        assertEquals(2f, gain, 0.001f)
        assertFalse("peak mode cannot overshoot", Gain.needsSoftClip(gain, 0.5f))
    }

    @Test
    fun `nothing is ever turned down`() {
        // Already at full scale, and louder than the loudness target: both modes leave it alone.
        assertEquals(1f, Gain.linearFor(NormalizeMode.PEAK, peak = 1f, rms = 0.5f), 0.0001f)
        assertEquals(1f, Gain.linearFor(NormalizeMode.LOUDNESS, peak = 1f, rms = 0.5f), 0.0001f)
    }

    @Test
    fun `silence does not divide by zero`() {
        assertEquals(1f, Gain.linearFor(NormalizeMode.PEAK, peak = 0f, rms = 0f), 0.0001f)
        assertEquals(1f, Gain.linearFor(NormalizeMode.LOUDNESS, peak = 0f, rms = 0f), 0.0001f)
    }

    @Test
    fun `a near-silent mix is not amplified into noise`() {
        val gain = Gain.linearFor(NormalizeMode.PEAK, peak = 0.0001f, rms = 0.00005f)
        assertEquals(Gain.dbToLinear(Gain.MAX_PEAK_BOOST_DB), gain, 0.001f)
    }

    @Test
    fun `loudness mode aims at the target and needs the clipper to get there`() {
        // -26 dBFS RMS, but already peaking near full scale: the boost to -14 must overshoot.
        val rms = Gain.dbToLinear(-26f)
        val gain = Gain.linearFor(NormalizeMode.LOUDNESS, peak = 0.9f, rms = rms)
        assertEquals(12f, Gain.linearToDb(gain), 0.1f)
        assertTrue(Gain.needsSoftClip(gain, 0.9f))
    }

    @Test
    fun `scaling without the clipper is exact`() {
        val buf = pcm(1000, -1000, 0)
        Gain.applyPcm16(buf, 0, buf.size, gain = 2f, softClip = false)
        assertEquals(listOf<Short>(2000, -2000, 0), samples(buf))
    }

    @Test
    fun `the clipper bends the top of the scale rather than flattening it`() {
        val buf = pcm(30000, -30000, 20000)
        Gain.applyPcm16(buf, 0, buf.size, gain = 1.2f, softClip = true)
        val scaled = samples(buf)
        // Past the knee: lifted, but short of the rail, and symmetric about zero.
        assertTrue(scaled[0] > 30000)
        assertTrue(scaled[0] < Short.MAX_VALUE)
        assertEquals(-scaled[0].toInt(), scaled[1].toInt())
        // Below the knee: scaled exactly, as though the clipper were not there.
        assertEquals(24000, scaled[2].toInt())
    }

    @Test
    fun `a boost far past the rail still lands inside the scale`() {
        val buf = pcm(30000, -30000, 20000)
        Gain.applyPcm16(buf, 0, buf.size, gain = 8f, softClip = true)
        assertTrue(samples(buf).all { it in -32768..32767 })
    }

    @Test
    fun `the meter reports peak and rms of what it was fed`() {
        val meter = Gain.Meter()
        val buf = pcm(16384, -16384, 16384, -16384)
        meter.add(buf, 0, buf.size)
        assertEquals(0.5f, meter.peak, 0.001f)
        assertEquals(0.5f, meter.rms, 0.001f)
    }

    @Test
    fun `a trailing odd byte is ignored rather than read past the end`() {
        val meter = Gain.Meter()
        val buf = pcm(16384) + byteArrayOf(0x7F)
        meter.add(buf, 0, buf.size)
        assertEquals(0.5f, meter.peak, 0.001f)
    }
}
