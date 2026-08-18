// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.OutputStream

/**
 * What a normalise or a convert can write a mix out as.
 *
 * All three are containers this app writes itself — see [Wav], [Flac] and [Mp3] — because the
 * platform muxer writes none of them. [MP3] is the only one that can be missing at runtime: it
 * needs the vendored encoder to have loaded, and [available] is what the UI offers rather than the
 * whole list.
 */
enum class AudioTarget(val mime: String, val extension: String, val label: String) {
    /** Lossy, and what a demo mix is actually sent as. */
    MP3(Mp3.MIME, "mp3", "MP3"),

    /** Lossless, roughly half the size of the same audio as WAV — the one to keep. */
    FLAC(Flac.MIME, "flac", "FLAC"),

    /** Lossless and plain: no codec, no tags, nothing to disagree about. */
    WAV(Wav.MIME, "wav", "WAV"),
    ;

    /** Whether this app can write tags into the file afterwards. WAV cannot carry them. */
    val tagFormat: TagFormat? get() = TagFormat.of("x.$extension")

    companion object {
        /** The targets this device can actually write — see [Mp3.available]. */
        fun available(): List<AudioTarget> = entries.filter { it != MP3 || Mp3.available }

        /** What [name] already is, when it is one of these. */
        fun of(name: String): AudioTarget? {
            val extension = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == extension }
        }
    }
}

/**
 * Turning one audio file into another: decode it to PCM, then write that PCM out as something.
 *
 * **One path for every format, including the ones that could be copied.** A WAV lifted by 3 dB
 * could have its samples scaled in place without a codec anywhere near it, and normalising a FLAC
 * to a FLAC could in principle keep the same encoder settings. Neither shortcut is taken: both are
 * a second implementation of the same job, differing only in what they get wrong, and this app is
 * not a DAW. Everything decodes to 16-bit PCM in the cache and is written back out from there.
 *
 * **Why the cache file rather than a stream.** Two things want the audio, and they want it in this
 * order: the level has to be *measured* over the whole mix before the gain is known, and only then
 * can the first sample be written. A single decode into a cache file gives both, and it is also
 * what makes a WAV's length known before its header goes down. The cost is a copy of something that
 * was already on disk, for the length of one edit.
 *
 * 16-bit throughout. The mixes this sees are bounces, the output formats offered are 16-bit, and
 * carrying float samples through only to round them at the end would buy nothing but code.
 */
object Render {

    /** Read and written in blocks of this — big enough to keep the codecs fed, small in memory. */
    const val BLOCK = 64 * 1024

    /** The decoded audio, and what was found out about it on the way past. */
    class Decoded(
        val pcm: File,
        val sampleRate: Int,
        val channels: Int,
        val peak: Float,
        val rms: Float,
    )

    /**
     * Decode [uri] into [into] as interleaved little-endian 16-bit PCM, measuring it as it goes.
     *
     * Null when the device has no decoder for the file, or when the decode produced nothing at all —
     * which for the caller are the same answer: there is nothing here to write out.
     */
    fun decode(context: Context, uri: Uri, into: File): Decoded? {
        val sink = PcmFile(into)
        val ok = sink.use { AudioDecoder.decode(context, uri, it) }
        if (!ok || into.length() <= 0 || sink.sampleRate <= 0) {
            into.delete()
            return null
        }
        return Decoded(into, sink.sampleRate, sink.channels, sink.meter.peak, sink.meter.rms)
    }

    /**
     * Write [pcm] out to [out] as [target], scaling every sample by [gain] on the way.
     *
     * [gain] of 1f with [softClip] off is a straight pass-through, which is what a convert is: the
     * same call, with nothing to scale.
     */
    fun encode(
        decoded: Decoded,
        out: OutputStream,
        target: AudioTarget,
        gain: Float,
        softClip: Boolean,
        cacheDir: File,
    ) {
        val sampleRate = decoded.sampleRate
        val channels = decoded.channels
        when (target) {
            AudioTarget.WAV -> {
                out.write(Wav.header(decoded.pcm.length(), sampleRate, channels))
                copyScaled(decoded.pcm, out, gain, softClip)
            }

            AudioTarget.FLAC -> Flac.Writer(out, sampleRate, channels, cacheDir).use {
                copyScaled(decoded.pcm, it, gain, softClip)
            }

            AudioTarget.MP3 -> Mp3.Writer(out, sampleRate, channels).use {
                copyScaled(decoded.pcm, it, gain, softClip)
            }
        }
    }

    /** The PCM, block by block, with the gain applied to each block before it is handed on. */
    private fun copyScaled(pcm: File, out: OutputStream, gain: Float, softClip: Boolean) {
        val buffer = ByteArray(BLOCK)
        pcm.inputStream().buffered(BLOCK).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                if (gain != 1f || softClip) Gain.applyPcm16(buffer, 0, read, gain, softClip)
                out.write(buffer, 0, read)
            }
        }
        out.flush()
    }

    /**
     * A [PcmSink] that writes what it is given to a file as 16-bit PCM, metering it on the way.
     *
     * Float buffers are folded down to 16 bits here rather than kept: every format this writes is
     * 16-bit, so the extra width has nothing left to carry — and folding early means the meter and
     * the encoder see exactly the same samples, which is what makes a peak normalise land on the
     * nose rather than a fraction of a dB off.
     */
    private class PcmFile(file: File) : PcmSink, AutoCloseable {
        private val out = file.outputStream().buffered(BLOCK)
        val meter = Gain.Meter()

        var sampleRate = 0
            private set
        var channels = 1
            private set

        private var bytes = ByteArray(0)

        override fun onStart(sampleRate: Int, channels: Int, totalFrames: Long) {
            this.sampleRate = sampleRate
            this.channels = channels
        }

        // The codec's answer overrides the container's: an HE-AAC decoder hands back twice the
        // sample rate the container declares, and a file written at the declared rate would play
        // back at half speed.
        override fun onOutputFormat(sampleRate: Int, channels: Int) {
            if (sampleRate > 0) this.sampleRate = sampleRate
            if (channels > 0) this.channels = channels
        }

        override fun onPcm16(pcm: ByteArray, count: Int, channels: Int) {
            meter.add(pcm, 0, count)
            out.write(pcm, 0, count)
        }

        override fun onPcmFloat(pcm: FloatArray, count: Int, channels: Int) {
            if (bytes.size < count * 2) bytes = ByteArray(count * 2)
            for (i in 0 until count) {
                val s = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt()
                bytes[i * 2] = (s and 0xFF).toByte()
                bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            meter.add(bytes, 0, count * 2)
            out.write(bytes, 0, count * 2)
        }

        override fun close() {
            out.flush()
            out.close()
        }
    }
}
