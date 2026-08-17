// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.audio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A cover image, ready to be written into a file: the encoded bytes, what they are, and how big.
 *
 * The dimensions are carried rather than looked up on demand because FLAC's `PICTURE` block states
 * them in the block itself. They can legally be zero there, and a lot of taggers write zero — but a
 * player that lays out its artwork from the header before decoding then has nothing to work with,
 * and the numbers cost one bounds-only decode to obtain. ID3's `APIC` does not carry them, so for
 * MP3 they are simply unused.
 */
data class Artwork(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
) {
    // A data class holding an array: the generated equals compares identities, which would make two
    // reads of the same file unequal. Compare what a caller actually means by "the same cover".
    override fun equals(other: Any?): Boolean =
        other is Artwork &&
            mime == other.mime &&
            width == other.width &&
            height == other.height &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        ((bytes.contentHashCode() * 31 + mime.hashCode()) * 31 + width) * 31 + height

    companion object {

        /**
         * Describe [bytes] as they stand, without re-encoding — for a cover already inside a file.
         *
         * Null when the bytes will not decode as an image at all, which is the honest answer for a
         * picture block holding something that is not a picture.
         */
        fun of(bytes: ByteArray): Artwork? {
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            return Artwork(
                bytes = bytes,
                // The decoder's own answer, not a guess from the magic bytes: it has just parsed
                // the header and knows, and a mime type that disagrees with the payload is the one
                // thing a picture block must not carry.
                mime = bounds.outMimeType ?: sniff(bytes),
                width = bounds.outWidth,
                height = bounds.outHeight,
            )
        }

        /**
         * Take an image the user just picked and make it fit to embed.
         *
         * **This is not optional, and it is the reason a picked image is never used as-is.** A photo
         * from a phone camera is four thousand pixels across and several megabytes, and it would go
         * into the head of every file it was applied to — a bulk apply over thirty mixes would add
         * a hundred megabytes to a folder to show a thumbnail. [MAX_EDGE] is generous for artwork
         * that will be looked at on a phone and is what the streaming services settled on for the
         * same reason.
         *
         * Always JPEG, whatever came in. A cover is a photograph or a piece of flat artwork, and
         * neither is what PNG is good at; a PNG of a photograph is several times the size of the
         * JPEG for no visible difference at [QUALITY]. The one thing lost is transparency, which
         * nothing displaying a cover honours anyway — it is composited onto some background either
         * way, and this way the background is decided once, here, rather than differently by every
         * player.
         *
         * Null if [bytes] will not decode.
         */
        fun encodeForEmbedding(bytes: ByteArray): Artwork? {
            if (bytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            // Already a JPEG that fits: keep the exact bytes.
            //
            // Not merely an optimisation. Copying a cover off one mix and pasting it onto the next
            // goes through here, and re-compressing a JPEG that needs no change costs a generation
            // of quality every time — so the same artwork moved across five mixes would arrive at
            // the fifth visibly worse than it left the first, for no reason anyone could see while
            // doing it. Anything that actually needs shrinking or converting falls through below.
            if (longest <= MAX_EDGE && bounds.outMimeType == MIME_JPEG) {
                return Artwork(bytes, MIME_JPEG, bounds.outWidth, bounds.outHeight)
            }

            // Halve on the way in as far as we can, so a very large source is never fully decoded
            // just to be shrunk. What is left over is a small exact scale afterwards.
            var sample = 1
            while (longest / (sample * 2) >= MAX_EDGE) sample *= 2
            val decoded = runCatching {
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull() ?: return null

            val scaled = fit(decoded)
            val out = ByteArrayOutputStream(64 * 1024)
            val ok = runCatching {
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            }.getOrDefault(false)
            val width = scaled.width
            val height = scaled.height
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            if (!ok) return null

            return Artwork(out.toByteArray(), MIME_JPEG, width, height)
        }

        /** [source] brought down to [MAX_EDGE] on its longest side, or itself if it already fits. */
        private fun fit(source: Bitmap): Bitmap {
            val longest = max(source.width, source.height)
            if (longest <= MAX_EDGE) return source
            val scale = MAX_EDGE.toFloat() / longest
            val width = (source.width * scale).roundToInt().coerceAtLeast(1)
            val height = (source.height * scale).roundToInt().coerceAtLeast(1)
            return runCatching { Bitmap.createScaledBitmap(source, width, height, true) }
                .getOrDefault(source)
        }

        /** Last resort when the decoder declines to name the format — the magic bytes do say. */
        private fun sniff(bytes: ByteArray): String = when {
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte() -> MIME_JPEG

            bytes.size >= 4 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'N'.code.toByte() &&
                bytes[3] == 'G'.code.toByte() -> "image/png"

            else -> MIME_JPEG
        }

        const val MIME_JPEG = "image/jpeg"

        /**
         * How large an embedded cover is allowed to be, on its longest side. Enough to fill a
         * phone screen at 3x and then some; small enough that a cover costs tens of kilobytes per
         * file rather than megabytes.
         */
        private const val MAX_EDGE = 800

        /** Where JPEG stops buying visible quality for artwork at this size. */
        private const val QUALITY = 88
    }
}
