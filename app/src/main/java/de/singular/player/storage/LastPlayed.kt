// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.storage

import android.content.Context
import androidx.core.content.edit

/**
 * The mix the transport was last loaded with, and how far into it.
 *
 * Playback survives the UI now, but it does not survive the process being killed — and a mix paused
 * an hour ago, with the app since swept out of memory, is exactly the one someone comes back to.
 * Without this the mini player returns empty and the position is gone; with it the app opens where
 * it was left.
 *
 * ## Why a key and not a uri
 *
 * The same key [Favourites] and [Recents] use — the document id with the root's stripped off, a path
 * like `2024/rough mix.mp3`. A uri is not stable across a re-grant of the root, and this value has
 * to outlive precisely the kind of interruption that can mint new ones. [MixStore.mixAt] turns it
 * back into a mix.
 *
 * ## Paused, never playing
 *
 * What is restored is a *loaded* mix, not a playing one. Coming back to an app that starts making
 * noise on its own is worse than coming back to silence, and the mini player's whole purpose is to
 * sit there with something ready to resume.
 */
class LastPlayed(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("last_played", Context.MODE_PRIVATE)

    /** The key and position last written, or null if there is nothing to come back to. */
    fun read(): Pair<String, Long>? {
        val key = prefs.getString(KEY, null)?.takeIf(String::isNotEmpty) ?: return null
        return key to prefs.getLong(POSITION, 0L).coerceAtLeast(0L)
    }

    fun write(key: String, positionMs: Long) {
        prefs.edit {
            putString(KEY, key)
            putLong(POSITION, positionMs.coerceAtLeast(0L))
        }
    }

    /** For when the mix is going away rather than merely stopping — deleted, moved, unloaded. */
    fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val KEY = "key"
        const val POSITION = "position"
    }
}
