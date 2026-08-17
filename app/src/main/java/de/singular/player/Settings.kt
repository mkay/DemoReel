// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player

/** How the app picks its light/dark colours: follow the OS, or force one. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User preferences, persisted across launches. */
data class Settings(
    /**
     * Lift favourites to the top of the folder they are in.
     *
     * On by default, because a favourite is usually given to a mix you mean to come back to. Off
     * suits anyone who reads a folder as a record of a session: date order is then the thing being
     * read, and a favourite jumping the queue loses the sequence the mixes were bounced in. The
     * Favourites tab gathers them either way, so nothing is out of reach with this off.
     */
    val favouritesFirst: Boolean = true,
    /**
     * Hold the display awake while the app is in front.
     *
     * Off by default, which is the opposite of a recorder's answer and right for a player: nobody
     * is holding an instrument here, and a mix is listened to with the screen off as often as not.
     * On is for when the waveform is being watched through a take.
     */
    val keepScreenOn: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)
