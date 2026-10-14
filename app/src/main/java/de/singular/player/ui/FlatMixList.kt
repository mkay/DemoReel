// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import de.singular.player.KeyedMix
import de.singular.player.PlaybackState
import de.singular.player.storage.Mix
import kotlinx.coroutines.delay

/**
 * Mixes from all over the tree in one flat list: every favourite, or what a search found.
 *
 * The one list in the app that is not a folder. Mixes are browsed as the tree they sit in, which is
 * the right way round for finding a session's work but no way at all to find the four keepers
 * scattered across a year of it. Marking one puts it here — flat, newest first, each row naming the
 * folder it came from because the mix's own name does not.
 *
 * It sits beside the files as a tab rather than off in the drawer. A drawer is for the places you
 * visit occasionally, and if the keepers are worth marking they are worth having a thumb's reach
 * away from the list they were marked in.
 *
 * A search's results are the same shape — mixes from anywhere, each needing its folder named — so
 * they are drawn by the same list, with [favouriteKeys] to say which rows carry a star, [empty] for
 * what to say when nothing came back, and [loading] for a search still opening files, whose answer
 * may yet grow.
 *
 * Rename and delete are raised to the caller rather than handled here, so the two tabs share one set
 * of dialogs and a mix renamed in either place is renamed the same way.
 */
@Composable
fun FlatMixList(
    mixes: List<KeyedMix>?,
    /** Said in place of the list when it comes back empty. */
    empty: String,
    playback: PlaybackState,
    onPlay: (Mix) -> Unit,
    onOpen: (Mix) -> Unit,
    onToggleFavourite: (Mix) -> Unit,
    onRename: (Mix) -> Unit,
    onDelete: (Mix) -> Unit,
    onShare: (Mix) -> Unit,
    onMove: (Mix) -> Unit,
    onEditTags: (Mix) -> Unit,
    modifier: Modifier = Modifier,
    /** Which rows are favourites; null when every row is, as in the favourites list itself. */
    favouriteKeys: Set<String>? = null,
    /** More may be on the way: [mixes] is an answer so far, not the answer. */
    loading: Boolean = false,
) {
    Box(modifier) {
        when {
            // Null rather than empty: not yet looked, as against looked and found nothing. Saying
            // "nothing marked yet" before the lookup returns would be a lie for as long as it took,
            // and so would "no mixes match" while a search is still opening files.
            mixes == null || (mixes.isEmpty() && loading) -> {}

            mixes.isEmpty() -> Hint(empty)

            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(mixes, key = { _, it -> it.key }) { i, keyed ->
                    if (i > 0) RowDivider()
                    MixRow(
                        mix = keyed.mix,
                        playing = playback.uri == keyed.mix.uri && playback.playing,
                        selected = false,
                        // No multi-select here. It exists in the files tab to delete or move a batch,
                        // and doing either from a list of favourites is not what this one is for —
                        // unmarking is, and that is already one tap on the row.
                        selecting = false,
                        favourite = favouriteKeys == null || keyed.key in favouriteKeys,
                        onToggleFavourite = { onToggleFavourite(keyed.mix) },
                        onPlay = { onPlay(keyed.mix) },
                        onOpen = { onOpen(keyed.mix) },
                        onToggleSelect = {},
                        onRename = { onRename(keyed.mix) },
                        onDelete = { onDelete(keyed.mix) },
                        onShare = { onShare(keyed.mix) },
                        onMove = { onMove(keyed.mix) },
                        onEditTags = { onEditTags(keyed.mix) },
                        folder = keyed.folder,
                    )
                }
            }
        }

        // Over the list rather than in place of it, so the rows found so far stay readable while
        // the rest are looked for. Held back long enough that a quick answer arrives to a still
        // screen: a progress bar that flashes and vanishes reports nothing except that something
        // happened.
        if (mixes == null || loading) {
            var waited by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(ProgressAfterMs)
                waited = true
            }
            if (waited) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}

/**
 * How long a lookup may take before it is worth saying so.
 *
 * Long enough that the usual case — a folder tree already in the file system's cache — never puts a
 * bar on screen, short enough that a slow one does not look stuck.
 */
private const val ProgressAfterMs = 250L
