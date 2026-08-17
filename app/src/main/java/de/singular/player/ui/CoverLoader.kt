// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.singular.player.storage.Mix
import de.singular.player.storage.MixStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Cover art, decoded at the size it will actually be drawn at, and remembered.
 *
 * This exists because the obvious implementation does not survive contact with a real folder.
 * Embedded artwork is commonly 600×600 or larger, which is 1.4 MB decoded; a folder of thirty mixes
 * drawn as a list of 44dp thumbnails would decode 40 MB of bitmaps to display about a megabyte of
 * pixels. So nothing here ever decodes a picture at full size for a thumbnail: [BitmapFactory]'s
 * `inSampleSize` is set from the bounds pass, and the decoder gives back something already close to
 * the size wanted.
 *
 * Two caches, because the two uses have opposite shapes. Thumbnails are many, small, and scrolled
 * back past — worth keeping. The player's cover is one, large, and replaced whenever a different mix
 * is opened — not worth keeping, and keeping it would undo the point of the first cache.
 *
 * Keyed on the file's identity rather than its uri: a mix whose tags have just been rewritten is the
 * same uri with a new size and mtime, and its cover may be the thing that changed. Nothing has to
 * remember to invalidate anything.
 */
class CoverLoader(private val store: MixStore) {

    /**
     * Decoded thumbnails, oldest evicted first once [THUMB_LIMIT] are held.
     *
     * A `LinkedHashMap` in access order is an LRU in one line, which is the whole of what is needed
     * here — a folder is tens of rows, not a media library, and reaching for a real cache library
     * would be more code than this file.
     *
     * The value is nullable and null is *cached*: "this file has no artwork" is an answer worth
     * remembering, and without it every scroll past an untagged mix would re-open the file to be
     * told the same thing again.
     */
    private val thumbs = object : LinkedHashMap<String, ImageBitmap?>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>) =
            size > THUMB_LIMIT
    }

    /** The thumbnail for [mix], or null if it carries no artwork this device can decode. */
    suspend fun thumb(mix: Mix): ImageBitmap? {
        val key = key(mix)
        synchronized(thumbs) { if (thumbs.containsKey(key)) return thumbs[key] }
        val decoded = decode(mix, THUMB_PX)
        synchronized(thumbs) { thumbs[key] = decoded }
        return decoded
    }

    /**
     * The player's cover for [mix], at a size worth looking at.
     *
     * Not cached: this is one image at a time, and the next one opened replaces it. Holding a few
     * megabytes for the mix you have just navigated away from is exactly the cost the thumbnail
     * cache exists to avoid spending.
     */
    suspend fun full(mix: Mix): ImageBitmap? = decode(mix, FULL_PX)

    /**
     * Read the artwork out of [mix] and decode it to roughly [targetPx] on its longest side.
     *
     * Two passes over the same bytes. The first decodes bounds only — no pixels are allocated — and
     * the second decodes for real with `inSampleSize` set from what the first found. `inSampleSize`
     * only halves, so the result is between one and two times the target rather than exactly it;
     * that is the point, since the alternative is a scaling decode that costs more than the little
     * extra memory saves.
     */
    private suspend fun decode(mix: Mix, targetPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = store.cover(mix) ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@withContext null

        var sample = 1
        while (longest / (sample * 2) >= targetPx) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
            .getOrNull()
            ?.asImageBitmap()
    }

    private fun key(mix: Mix) = "${mix.uri}|${mix.sizeBytes}|${mix.modifiedAt}"

    private companion object {
        /**
         * A 44dp row thumbnail is about 130px on a 3x screen. Decoding to 128 leaves it sharp
         * without holding a picture ten times the size of the frame it goes in.
         */
        const val THUMB_PX = 128

        /** The player draws its cover around 240dp — 720px on a 3x screen. */
        const val FULL_PX = 720

        /**
         * Enough for a long folder scrolled end to end and back. At 128px square that is under
         * 8 MB held at the very worst, and in practice far less: most entries are the cached null
         * for a mix with no artwork, which costs nothing.
         */
        const val THUMB_LIMIT = 120
    }
}

/**
 * The loader, reached without threading it through every list and row.
 *
 * A composition local rather than a parameter because a cover is a detail of *drawing* a mix, not
 * part of what a mix is — every row and both lists would otherwise carry a parameter they only pass
 * along. Static, because it is provided once at the top of the tree and never changes.
 */
val LocalCoverLoader = staticCompositionLocalOf<CoverLoader> {
    error("No CoverLoader provided")
}

/**
 * The thumbnail for [mix], filled in when it arrives.
 *
 * Null while loading and null for a mix with no artwork — the same value for two states, which is
 * deliberate: both draw the placeholder, and a row that flashed a spinner and then a picture for
 * every line of a scrolling list would be noisier than one that simply fills in. A cache hit
 * resolves in the same frame, so a folder scrolled back past does not flicker at all.
 */
@Composable
fun rememberThumb(mix: Mix): State<ImageBitmap?> {
    val loader = LocalCoverLoader.current
    val state = remember(mix.uri) { mutableStateOf<ImageBitmap?>(null) }
    // Keyed on identity, not on the mix: the row is recycled down the list, and a new file at the
    // same uri (a re-tag) has to re-read while a mere recomposition must not.
    LaunchedEffect(mix.uri, mix.sizeBytes, mix.modifiedAt) {
        state.value = loader.thumb(mix)
    }
    return state
}

/**
 * Cover bytes already in hand, decoded for display — what the editor draws while a cover is being
 * changed but not yet written.
 *
 * Not routed through [CoverLoader]: these bytes belong to no file yet, so there is nothing to key a
 * cache on, and they have already been through [de.singular.player.audio.Artwork.encodeForEmbedding]
 * and are small. Keyed on the array's identity, which is sound here because the bytes are replaced
 * wholesale and never edited in place.
 */
@Composable
fun rememberDecoded(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    if (bytes == null || bytes.isEmpty()) return@remember null
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        .getOrNull()
        ?.asImageBitmap()
}

/** The player's cover for [mix], at full size. Null while loading, and for a mix without one. */
@Composable
fun rememberFullCover(mix: Mix): State<ImageBitmap?> {
    val loader = LocalCoverLoader.current
    val state = remember(mix.uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mix.uri, mix.sizeBytes, mix.modifiedAt) {
        state.value = loader.full(mix)
    }
    return state
}
