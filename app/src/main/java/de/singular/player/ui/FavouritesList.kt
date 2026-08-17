// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.res.stringResource
import de.singular.player.FavouriteMix
import de.singular.player.PlaybackState
import de.singular.player.R
import de.singular.player.storage.Mix
import kotlinx.coroutines.delay

/**
 * Every favourite, wherever it lives.
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
 * Rename and delete are raised to the caller rather than handled here, so the two tabs share one set
 * of dialogs and a mix renamed in either place is renamed the same way.
 */
@Composable
fun FavouritesList(
    mixes: List<FavouriteMix>?,
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
) {
    when {
        // Null rather than empty: not yet looked, as against looked and found nothing. Saying
        // "nothing marked yet" before the lookup returns would be a lie for as long as it took.
        //
        // The bar gets a box of its own rather than this screen's modifier, which carries a
        // `weight` — an indicator 4dp tall stretched over a whole list area reads as a striped
        // block, its indeterminate segments blown up. And it is held back long enough that a quick
        // answer arrives to a still screen: a progress bar that flashes and vanishes reports nothing
        // except that something happened.
        mixes == null -> Box(modifier.fillMaxWidth()) {
            var waited by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(ProgressAfterMs)
                waited = true
            }
            if (waited) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        mixes.isEmpty() -> Hint(stringResource(R.string.favourites_empty), modifier)

        else -> LazyColumn(modifier) {
            itemsIndexed(mixes, key = { _, it -> it.key }) { i, favourite ->
                if (i > 0) RowDivider()
                MixRow(
                    mix = favourite.mix,
                    playing = playback.uri == favourite.mix.uri && playback.playing,
                    selected = false,
                    // No multi-select here. It exists in the files tab to delete or move a batch,
                    // and doing either from a list of favourites is not what this one is for —
                    // unmarking is, and that is already one tap on the row.
                    selecting = false,
                    favourite = true,
                    onToggleFavourite = { onToggleFavourite(favourite.mix) },
                    onPlay = { onPlay(favourite.mix) },
                    onOpen = { onOpen(favourite.mix) },
                    onToggleSelect = {},
                    onRename = { onRename(favourite.mix) },
                    onDelete = { onDelete(favourite.mix) },
                    onShare = { onShare(favourite.mix) },
                    onMove = { onMove(favourite.mix) },
                    onEditTags = { onEditTags(favourite.mix) },
                    folder = favourite.folder,
                )
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
