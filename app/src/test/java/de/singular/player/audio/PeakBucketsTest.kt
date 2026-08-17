// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The envelope arithmetic, which is the whole reason a mastered mix draws as anything but a slab.
 *
 * Worth testing here rather than by looking at the screen: the failure mode of the layer this
 * covers is *plausible* — an RMS that is subtly wrong still produces a waveform-shaped picture, and
 * the only way to notice is to know what the numbers should have been. The peak layer is the
 * opposite and is easy to eyeball; it is checked anyway, because the two share an accumulator and a
 * bucket boundary and it is the boundary that gets things wrong.
 *
 * Pure Kotlin with no Android dependency, so it runs on the JVM.
 */
class PeakBucketsTest {

    @Test
    fun `a constant level has an RMS equal to its peak`() {
        // Square-wave-ish: every frame at the same magnitude, so mean-square equals that magnitude
        // squared, and its root is the magnitude. Anything else means the accumulator is wrong.
        val envelope = feed(buckets = 4, frames = 400) { 0.5f }!!
        for (i in 0 until envelope.size) {
            assertEquals(0.5f, envelope.peaks[i], TOLERANCE)
            assertEquals(0.5f, envelope.rms[i], TOLERANCE)
        }
    }

    @Test
    fun `RMS sits below peak on anything that is not constant`() {
        // Half the frames loud, half silent: peak is the loud value, RMS is it over root two.
        val envelope = feed(buckets = 2, frames = 400) { i -> if (i % 2 == 0) 0.8f else 0f }!!
        for (i in 0 until envelope.size) {
            assertEquals(0.8f, envelope.peaks[i], TOLERANCE)
            assertEquals(0.8f / sqrt(2f), envelope.rms[i], TOLERANCE)
        }
    }

    @Test
    fun `a limited master is flat in peak and shaped in RMS`() {
        // The case the two layers exist for. Every bucket touches full scale — which is what a
        // limiter guarantees — while the *density* of loud frames falls away across the file. The
        // peak layer therefore says nothing, and the RMS layer says everything.
        val buckets = 4
        val perBucket = 250
        val envelope = feed(buckets, buckets * perBucket) { i ->
            val bucket = i / perBucket
            // One frame in (bucket + 1) reaches the ceiling; the rest are silent.
            if (i % (bucket + 1) == 0) 1f else 0f
        }!!

        // Flat: every bucket reports the same peak, so nothing can be read from this layer.
        for (i in 0 until envelope.size) assertEquals(1f, envelope.peaks[i], TOLERANCE)

        // Shaped: strictly falling, which is the shape the drawing needs.
        for (i in 1 until envelope.size) {
            assertTrue(
                "RMS should fall across the file, but bucket $i is not below ${i - 1}",
                envelope.rms[i] < envelope.rms[i - 1],
            )
        }
        // And the fall is large enough to see: the first bucket is several times the last, where
        // the peak layer differs by nothing at all.
        assertTrue(envelope.rms[0] > envelope.rms[envelope.size - 1] * 1.5f)
    }

    @Test
    fun `every bucket gets its own frames and no others`() {
        // Two halves at different levels, on an exact bucket boundary. A bucket that leaked frames
        // across the seam would land between the two values rather than on one of them.
        val envelope = feed(buckets = 2, frames = 1_000) { i -> if (i < 500) 0.9f else 0.1f }!!
        assertEquals(0.9f, envelope.rms[0], TOLERANCE)
        assertEquals(0.1f, envelope.rms[1], TOLERANCE)
    }

    @Test
    fun `a file shorter than the bucket count fills every column`() {
        // One frame spans several columns here, and the columns after the first have no samples of
        // their own. They must take the frame's value: filling them with a reset accumulator's zero
        // would draw a gap in the middle of audio that is plainly there.
        val envelope = feed(buckets = 8, frames = 3) { 0.6f }!!
        for (i in 0 until envelope.size) {
            assertEquals("bucket $i is empty", 0.6f, envelope.peaks[i], TOLERANCE)
            assertEquals("bucket $i is empty", 0.6f, envelope.rms[i], TOLERANCE)
        }
    }

    @Test
    fun `a source that stops early still owns the bucket it stopped in`() {
        // Declared long, delivered short — which is the ordinary case, since the frame count comes
        // from the container's own duration and is a claim rather than a count.
        val buckets = PeakBuckets(4, totalFrames = 1_000)
        buckets.addPcmFloat(FloatArray(600) { 0.4f }, 600, channels = 1)
        val envelope = buckets.finish()!!
        // The two whole buckets, and the partial third, all carry the level; nothing was dropped.
        assertEquals(0.4f, envelope.rms[0], TOLERANCE)
        assertEquals(0.4f, envelope.rms[1], TOLERANCE)
        assertEquals(0.4f, envelope.rms[2], TOLERANCE)
    }

    @Test
    fun `stereo collapses to the louder channel in both layers`() {
        // A silent right channel must not halve the drawing — and the two layers have to agree
        // about that, or the body could sit above the outline that is supposed to enclose it.
        val frames = 200
        val interleaved = FloatArray(frames * 2) { if (it % 2 == 0) 0.7f else 0f }
        val buckets = PeakBuckets(2, totalFrames = frames.toLong())
        buckets.addPcmFloat(interleaved, interleaved.size, channels = 2)
        val envelope = buckets.finish()!!
        assertEquals(0.7f, envelope.peaks[0], TOLERANCE)
        assertEquals(0.7f, envelope.rms[0], TOLERANCE)
    }

    @Test
    fun `16-bit PCM and float agree`() {
        // The two entry points are separate loops over the same idea, so they are the obvious place
        // for one to drift from the other.
        val frames = 400
        val fromFloat = PeakBuckets(4, frames.toLong()).apply {
            addPcmFloat(FloatArray(frames) { 0.5f }, frames, channels = 1)
        }.finish()!!

        val pcm = ByteArray(frames * 2)
        val value = (0.5f * 32768f).toInt() // exactly representable, so the two paths must match
        for (i in 0 until frames) {
            pcm[i * 2] = (value and 0xFF).toByte()
            pcm[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        val fromPcm = PeakBuckets(4, frames.toLong()).apply {
            addPcm16(pcm, pcm.size, channels = 1)
        }.finish()!!

        for (i in 0 until fromFloat.size) {
            assertEquals(fromFloat.peaks[i], fromPcm.peaks[i], TOLERANCE)
            assertEquals(fromFloat.rms[i], fromPcm.rms[i], TOLERANCE)
        }
    }

    @Test
    fun `nothing fed in is nothing to draw`() {
        assertNull(PeakBuckets(4, totalFrames = 100).finish())
    }

    @Test
    fun `the two layers are always the same length`() {
        val envelope = feed(buckets = 37, frames = 5_000) { 0.3f }
        assertNotNull(envelope)
        assertEquals(37, envelope!!.size)
        assertEquals(envelope.peaks.size, envelope.rms.size)
    }

    /** Run [frames] frames of mono through [buckets] columns, each frame given by [level]. */
    private fun feed(buckets: Int, frames: Int, level: (Int) -> Float): Envelope? {
        val collector = PeakBuckets(buckets, frames.toLong())
        val block = FloatArray(frames) { level(it) }
        collector.addPcmFloat(block, frames, channels = 1)
        return collector.finish()
    }

    private companion object {
        /** Float accumulation over hundreds of terms; anything tighter is testing the FPU. */
        const val TOLERANCE = 1e-4f
    }
}
