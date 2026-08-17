// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** How wide a waveform is read, and how wide it is drawn. */
object Waveform {

    /** Enough columns to look like a waveform on a phone, few enough to draw every frame. */
    const val BUCKETS = 420

    /**
     * What the player reads at. Ten times the columns a phone has, because the player zooms: past
     * about 8x the drawing would otherwise be showing stretched buckets rather than the mix. Two
     * arrays of this length is 32 kB of floats, and still one pass over the file.
     */
    const val DETAIL = 4_096
}

/**
 * What a waveform is drawn from: a loudest and an average value for every column.
 *
 * **Two layers, because one is not enough for the material this app holds.** A demo mix is usually
 * a finished, limited bounce, and in one the loudest sample of any 60 ms window is pressed against
 * the ceiling by definition — that is what limiting does. So a peak envelope of a master is very
 * nearly a straight line, and no amount of rescaling recovers a shape from it, because the shape was
 * discarded at bucketing time rather than at drawing time.
 *
 * The arrangement lives in the *average* energy instead. Between a dense chorus and a sparse verse
 * the peak may not move at all while the RMS moves 5 or 6 dB, and between either of those and an
 * intro it moves 15. That is the picture someone re-opening a mix is looking for.
 *
 * The peak is kept as well rather than replaced. It is the informative layer for the other half of
 * what lands in a folder — a rough unmastered bounce with real dynamics — and it is the only one
 * that shows a transient or a moment slamming the ceiling. Drawing both means neither kind of file
 * gets a display tuned against it, which is what every DAW settled on for the same reason.
 *
 * Both arrays are the same length, and index `i` of each describes the same slice of the file.
 */
class Envelope(val peaks: FloatArray, val rms: FloatArray) {
    init {
        require(peaks.size == rms.size) { "peaks and rms must describe the same columns" }
    }

    val size: Int get() = peaks.size
}

/**
 * Collects PCM into [buckets] columns, keeping the loudest and the average of each.
 *
 * Fed in whatever blocks the source produces — a codec's output buffer — and finished once.
 * [totalFrames] only has to be close: it sets how wide a bucket is, and being a fraction of a
 * percent out moves the right-hand edge by less than a pixel.
 *
 * The average is the root mean square of the same per-frame values the peak is taken from, so the
 * two layers describe the same signal and can be drawn over one another. That means a stereo file's
 * RMS is the RMS of its louder channel rather than of the pair, which is a slight abuse — but it
 * keeps the two layers on one footing, and the alternative is a body that can sit *above* the
 * outline enclosing it.
 */
class PeakBuckets(private val buckets: Int, totalFrames: Long) {

    private val peaks = FloatArray(buckets)
    private val rms = FloatArray(buckets)

    // Frames per bucket as a float: at 44.1 kHz a ten-second mix is 1050.0 of them, and rounding
    // that down walks the last fraction of a second off the right-hand edge.
    private val perBucket = totalFrames.toDouble() / buckets

    private var frameIndex = 0L
    private var peak = 0f

    /**
     * Sum of squares for the bucket being filled, and how many frames went into it.
     *
     * A Double rather than a Float: this accumulates one term per sample, so at 44.1 kHz a bucket
     * of a long file can hold tens of thousands of them, and a Float accumulator loses the small
     * ones once the running total is large — which is precisely the quiet detail this layer exists
     * to show.
     */
    private var sumSquares = 0.0
    private var inBucket = 0

    private var bucket = 0
    private var seen = 0L

    /** True once every column has been filled — the caller can stop decoding. */
    val full: Boolean get() = bucket >= buckets

    /** [count] bytes of interleaved little-endian 16-bit PCM. */
    fun addPcm16(pcm: ByteArray, count: Int, channels: Int) {
        val stride = channels * 2
        var i = 0
        while (i + stride <= count && !full) {
            var frame = 0f
            for (c in 0 until channels) {
                val at = i + c * 2
                val s = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort()
                frame = max(frame, abs(s.toInt()) / 32768f)
            }
            advance(frame)
            i += stride
        }
    }

    /** [count] floats of interleaved PCM in `-1f..1f`, as a codec may hand back. */
    fun addPcmFloat(pcm: FloatArray, count: Int, channels: Int) {
        var i = 0
        while (i + channels <= count && !full) {
            var frame = 0f
            for (c in 0 until channels) frame = max(frame, abs(pcm[i + c]))
            advance(frame)
            i += channels
        }
    }

    /**
     * A mix is normally stereo; it collapses to its loudest channel, which is what keeps a stereo
     * file from drawing at half height.
     */
    private fun advance(frame: Float) {
        peak = max(peak, frame)
        sumSquares += frame.toDouble() * frame
        inBucket++
        frameIndex++
        seen++
        // Close every bucket this frame has crossed. Usually one; on a file shorter than the bucket
        // count a single frame spans several columns, and the ones after the first have no samples
        // of their own — so they take the frame's own value rather than the zero that a reset
        // accumulator would otherwise hand them, which would draw as a gap in the middle of audio.
        while (!full && frameIndex >= (bucket + 1) * perBucket) {
            if (inBucket > 0) {
                peaks[bucket] = peak
                rms[bucket] = sqrt(sumSquares / inBucket).toFloat()
            } else {
                peaks[bucket] = frame
                rms[bucket] = frame
            }
            peak = 0f
            sumSquares = 0.0
            inBucket = 0
            bucket++
        }
    }

    /** The envelope, or null if nothing was ever fed in. */
    fun finish(): Envelope? {
        if (seen == 0L) return null
        // A source that ran out early still owns the bucket it stopped in.
        if (bucket < buckets && inBucket > 0) {
            peaks[bucket] = max(peaks[bucket], peak)
            rms[bucket] = max(rms[bucket], sqrt(sumSquares / inBucket).toFloat())
        }
        return Envelope(peaks, rms)
    }
}
