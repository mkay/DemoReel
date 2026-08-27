// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.singular.player.PlaybackState
import de.singular.player.R

/**
 * The mix being listened to, wherever you happen to be.
 *
 * It sits at the foot of every screen, because playback outlives the screen it was started from:
 * without this, wandering off to Settings would leave a mix playing with nothing anywhere to stop
 * it.
 *
 * It shows while a mix is playing, and goes on showing one that was stopped part-way — that mix is
 * still the one in hand, and the bar is how it gets started again from where it stopped. What it
 * does not do is outlive a mix that ran out on its own: reaching the end leaves it loaded at 0:00,
 * which would otherwise leave the bar sitting there reporting silence about something already
 * finished with.
 *
 * So stop and close stay two different things: stop silences the mix and keeps it, close puts it
 * away.
 *
 * The cover appears here as it does in the list, at the same size, and for the same reason — it is
 * the fastest way to say *which* mix without reading. It is not a control here, unlike in the list:
 * the transport is a button of its own on this bar, and there is nothing left for a tap on the
 * artwork to mean except the one the name already carries.
 */
@OptIn(ExperimentalMaterial3Api::class) // Slider's thumb and track slots
@Composable
fun MiniPlayer(
    playback: PlaybackState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    /**
     * The mix is a file another app shared in, which this bar's close button is the end of: the
     * grant dies with the task and nothing on disk is ours. Said in the title, because the cross
     * sits an inch away and nothing else here would warn that pressing it is final.
     */
    unsaved: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val mix = playback.mix?.takeIf { playback.playing || !playback.finished } ?: return
    val cover by rememberThumb(mix)
    Surface(modifier.fillMaxWidth(), tonalElevation = BottomBarElevation) {
        Column(
            Modifier
                // Whatever the system reserves at the bottom — gesture handle or button bar — stays
                // clear. This bar is the last thing above it, so the inset is its to carry.
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(cover, CoverSize)
                Spacer(Modifier.width(10.dp))
                // The name is the way back into the full player, the same as it is in the list.
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onOpen)
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        if (unsaved) {
                            stringResource(R.string.mini_player_unsaved, mix.displayTitle)
                        } else {
                            mix.displayTitle
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val of = stringResource(R.string.mini_player_of)
                    Text(
                        formatDuration(playback.positionMs) + of +
                            formatDuration(playback.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onToggle) {
                    Icon(
                        if (playback.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = stringResource(
                            if (playback.playing) R.string.cd_stop else R.string.cd_play,
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                // Stop only silences the mix; this is how it stops taking up the screen. Dimmed,
                // because it is the least likely of the two to be wanted and reads at the same
                // weight as the transport beside it otherwise — a cross at full strength pulls
                // harder than a filled triangle does.
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_close_player),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = DismissTint),
                    )
                }
            }
            Slider(
                value = playback.positionMs.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..playback.durationMs.toFloat().coerceAtLeast(1f),
                // The track alone, no handle: at this height a thumb is bigger than the bar it rides
                // on, and the seek target is the whole width either way.
                thumb = {},
                // Drawn rather than themed. Material's own track is as tall as a control and splits
                // into two lozenges with a gap, which at the foot of the screen reads as a second
                // toolbar; and it paints both halves in the accent, so the part not yet played is as
                // loud as the part that has. This is a hairline, and only the played part is
                // coloured — the rest is faint ink.
                track = { state ->
                    val span = state.valueRange.endInclusive - state.valueRange.start
                    val played = if (span > 0f) {
                        ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(TimelineHeight)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(played)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                },
                // Taller than the line it draws, so the seek target stays a thumb rather than
                // shrinking to the hairline.
                modifier = Modifier.fillMaxWidth().height(TimelineTouch),
            )
        }
    }
}

/** The same 44dp the list uses, so the artwork does not change size on the way down the screen. */
private val CoverSize = 44.dp

/** A line under the mix, not a bar beside it — see the track slot above. */
private val TimelineHeight = 3.dp

/** What the finger gets, regardless of what the eye gets. */
private val TimelineTouch = 20.dp

/** Present, but plainly the quieter of the two buttons. */
private const val DismissTint = 0.45f
