// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * The three encoders, on a device, because on a device is the only place they exist: FLAC is
 * [android.media.MediaCodec] and MP3 is the vendored LAME, and neither runs on a JVM.
 *
 * Each format is written from known PCM and then read back through the platform's own extractor —
 * the same one the player uses. That is the property that actually matters: not that bytes came
 * out, but that what came out is a file this app, and everything else on the phone, can open.
 */
@RunWith(AndroidJUnit4::class)
class RenderTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val sampleRate = 44_100
    private val channels = 2
    private val seconds = 2

    /** Two seconds of a quiet 440 Hz tone, interleaved stereo, at a quarter of full scale. */
    private fun tone(): ByteArray {
        val frames = sampleRate * seconds
        val out = ByteArray(frames * channels * 2)
        var at = 0
        for (i in 0 until frames) {
            val value = (sin(2.0 * PI * 440.0 * i / sampleRate) * 8192).toInt()
            repeat(channels) {
                out[at++] = (value and 0xFF).toByte()
                out[at++] = ((value shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    /** What the platform makes of [file]: its mime, rate, channel count and length. */
    private fun describe(file: File): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertTrue("no track in ${file.name}", extractor.trackCount > 0)
            return extractor.getTrackFormat(0)
        } finally {
            extractor.release()
        }
    }

    private fun render(target: AudioTarget, pcm: ByteArray, gain: Float = 1f): File {
        val raw = File(context.cacheDir, "tone.pcm").apply { writeBytes(pcm) }
        val out = File(context.cacheDir, "tone.${target.extension}")
        val decoded = Render.Decoded(raw, sampleRate, channels, peak = 0.25f, rms = 0.18f)
        out.outputStream().buffered().use {
            Render.encode(decoded, it, target, gain, softClip = false, cacheDir = context.cacheDir)
        }
        raw.delete()
        return out
    }

    @Test
    fun wavIsWrittenAndReadsBack() {
        val pcm = tone()
        val file = render(AudioTarget.WAV, pcm)
        // Header and payload, exactly — a WAV adds nothing else.
        assertEquals(44L + pcm.size, file.length())
        val format = describe(file)
        assertEquals(sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
    }

    @Test
    fun flacIsWrittenAndReadsBack() {
        val file = render(AudioTarget.FLAC, tone())
        val format = describe(file)
        // Android's FLAC extractor decodes in the extractor and reports the track as raw PCM, so
        // both answers mean "this is a FLAC the platform opened".
        assertTrue(
            "unexpected track: ${format.getString(MediaFormat.KEY_MIME)}",
            format.getString(MediaFormat.KEY_MIME) in
                listOf(MediaFormat.MIMETYPE_AUDIO_FLAC, MediaFormat.MIMETYPE_AUDIO_RAW),
        )
        assertEquals(sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        // A tone is about as compressible as audio gets, but lossless is lossless: it must still be
        // a real file rather than a header with nothing behind it.
        assertTrue("suspiciously small: ${file.length()}", file.length() > 10_000)
    }

    @Test
    fun mp3IsWrittenAndReadsBack() {
        assertTrue("the MP3 encoder did not load", Mp3.available)
        val file = render(AudioTarget.MP3, tone())
        val format = describe(file)
        assertEquals(MediaFormat.MIMETYPE_AUDIO_MPEG, format.getString(MediaFormat.KEY_MIME))
        assertEquals(sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        // 320 kbit/s CBR over two seconds is around 80 KB; the check is only that it is in the
        // right order of magnitude, not that LAME hit a byte count.
        assertTrue("unexpected size: ${file.length()}", file.length() in 40_000..200_000)
    }

    @Test
    fun theGainIsWrittenIntoTheAudio() {
        val pcm = tone()
        val lifted = render(AudioTarget.WAV, pcm, gain = 2f)
        val bytes = lifted.readBytes()
        val meter = Gain.Meter()
        meter.add(bytes, 44, bytes.size - 44)
        val before = Gain.Meter().apply { add(pcm, 0, pcm.size) }
        assertEquals(before.peak * 2f, meter.peak, 0.01f)
    }

    @Test
    fun aDecodeMeasuresWhatItDecodes() {
        // Round trip through the app's own path: encode a tone, then decode it back as the renderer
        // would, and check the level it reports is the level that went in.
        val source = render(AudioTarget.FLAC, tone())
        val pcm = File(context.cacheDir, "roundtrip.pcm")
        val decoded = Render.decode(context, android.net.Uri.fromFile(source), pcm)
        assertTrue("nothing decoded", decoded != null)
        requireNotNull(decoded)
        assertEquals(sampleRate, decoded.sampleRate)
        assertEquals(channels, decoded.channels)
        assertEquals(0.25f, decoded.peak, 0.01f)
        pcm.delete()
    }
}
