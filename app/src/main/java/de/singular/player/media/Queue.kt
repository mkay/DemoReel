// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.media

import kotlin.random.Random

/**
 * How a run of mixes is ordered before it is handed to the player.
 *
 * ## Why this exists at all, in an app with no queue
 *
 * Demo Reel is for listening to mixes, not for playing music, and the phone deliberately has no
 * queue: one mix, one big button, and repeat if you want to sit with it. Driving is the exception.
 * A head unit cannot be browsed the way a folder can, and picking each next mix at the wheel is
 * precisely what a driver must not be doing — so the car gets three ways to start a run of mixes
 * and then leave them alone: play a folder, shuffle a folder, shuffle everything.
 *
 * None of that is reachable from the phone UI, by design. See the Android Auto plan.
 *
 * ## Generic on purpose
 *
 * These take a `List<T>` rather than a `List<Mix>` so they can be tested on the JVM. A [Mix] holds
 * an `android.net.Uri`, which does not exist off-device, and the ordering is the part actually
 * worth testing — the walk that finds the mixes is storage, and storage needs the phone.
 */
object Queue {

    /**
     * As the folder is listed — newest first, which is what [de.singular.player.storage.MixStore]
     * sorts a listing into and what the library shows.
     *
     * A copy rather than the list itself, so a queue handed to the player cannot change underneath
     * it when the library refreshes.
     */
    fun <T> inOrder(items: List<T>): List<T> = items.toList()

    /**
     * Shuffled, with [random] injectable so a test can pin the order.
     *
     * Every mix appears exactly once: this is a permutation, not a random walk that might play the
     * same one twice before the rest have had a turn. On a drive that difference is the whole point
     * — a shuffle that repeats is indistinguishable from a broken one.
     */
    fun <T> shuffled(items: List<T>, random: Random = Random.Default): List<T> =
        items.shuffled(random)

    /**
     * Shuffled, but starting from [first] if it is in [items].
     *
     * For "shuffle this folder" when a mix is already in mind: it plays, and the rest of the folder
     * follows in some order. If [first] is not there, this is an ordinary shuffle.
     */
    fun <T> shuffledStartingWith(
        items: List<T>,
        first: T,
        random: Random = Random.Default,
    ): List<T> {
        if (first !in items) return shuffled(items, random)
        return listOf(first) + shuffled(items.filterNot { it == first }, random)
    }
}
