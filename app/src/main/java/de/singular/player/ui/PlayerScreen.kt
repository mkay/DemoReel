// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.singular.player.OpenMix
import de.singular.player.PlaybackState
import de.singular.player.R
import de.singular.player.audio.AudioTarget
import de.singular.player.audio.Envelope
import de.singular.player.audio.NormalizeMode
import de.singular.player.storage.Mix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * One mix, opened: its cover, what it says about itself, its waveform, and somewhere to put a thumb.
 *
 * The cover and the waveform are both here on purpose, and they are answering different questions.
 * The cover says *which* mix this is — it is the thing recognised across a room and the thing a
 * finished bounce is given. The waveform says *where in it* you are: a seek bar tells you the
 * position, but the shape tells you where the intro ends, where the drop is, where it fades — which
 * is what you are looking for when you re-open a mix at all.
 *
 * They are stacked rather than side by side, and the waveform takes whatever height is left. On a
 * phone in portrait a cover big enough to be worth showing and a waveform wide enough to be worth
 * scrubbing cannot both have the middle of the screen; the cover has a fixed size it stops growing
 * past, and everything else is the waveform's.
 */
@Composable
fun PlayerScreen(
    open: OpenMix,
    playback: PlaybackState,
    onPlayPause: (Mix, Long) -> Unit,
    onSeek: (Long) -> Unit,
    onRestart: (Mix) -> Unit,
    looping: Boolean,
    onToggleLoop: () -> Unit,
    onEditTags: () -> Unit,
    onCopyCover: () -> Unit,
    /** What this device can write a copy as — see [de.singular.player.audio.AudioTarget]. */
    targets: List<AudioTarget>,
    /** True while a file is being rewritten: the two edits that rewrite one must not overlap. */
    busy: Boolean,
    onNormalize: (NormalizeMode, AudioTarget, Boolean) -> Unit,
    onConvert: (AudioTarget) -> Unit,
    onSaveNote: (String) -> Unit,
    /**
     * Given only when the mix came from another app, and then it is both the notice's button and
     * the flag that dims everything that writes — see [PlayerOverflowAction].
     */
    onAddToLibrary: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val mix = open.mix
    // A file on loan from another app: readable for as long as the grant lasts, and writable
    // nowhere. Every edit on this screen rewrites the file in place or writes a second one beside
    // it, and neither is something a uri lent for reading can do — so they are shown dimmed with
    // the notice above them saying what would make them work, rather than left live to fail.
    val onLoan = onAddToLibrary != null
    // "Loaded" and "playing" are not the same thing: a stopped mix keeps its place, here and in the
    // mini player, so that Play picks up where it left off rather than starting over.
    val loaded = playback.uri == mix.uri
    val playing = loaded && playback.playing
    val cover by rememberFullCover(mix)

    // Where the playhead sits before this mix has been loaded at all — the "press play here" mark.
    var scrubMs by remember(mix.uri) { mutableLongStateOf(0L) }
    val positionMs = if (loaded) playback.positionMs else scrubMs
    val durationMs = max(
        1L,
        if (loaded && playback.durationMs > 0) playback.durationMs else mix.durationMs,
    )

    var noting by remember(mix.uri) { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverWithMenu(
            cover = cover,
            // Only offered when there is something to copy. The alternative — a greyed-out item —
            // would say "this mix could have a cover copied from it, but not today", which is not
            // what an untagged mix means.
            canCopy = cover != null,
            onCopy = onCopyCover,
            // Copying the image out is a read and stays; editing is not on offer until the mix is
            // in the library, which is what the notice below explains.
            onEditTags = onEditTags.takeIf { !onLoan },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            mix.displayTitle,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Artist and album, on the line under the title, in the order a record sleeve puts them.
        // Absent entirely rather than shown blank: an untagged mix is the state every mix starts in,
        // and an empty line under the title would be a gap the layout has to explain.
        val separator = stringResource(R.string.meta_separator)
        val byline = listOf(mix.tags.artist, mix.tags.album).filter { it.isNotEmpty() }
        if (byline.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                byline.joinToString(separator),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                formatKind(mix.name).takeIf { it.isNotEmpty() }?.let {
                    append(it)
                    append(separator)
                }
                append(formatDuration(mix.durationMs))
                append(separator)
                append(formatSize(mix.sizeBytes))
                mix.tags.year.takeIf { it.isNotEmpty() }?.let {
                    append(separator)
                    append(it)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The note, where the mix describes itself: under the title, with the rest of what the file
        // says about itself, rather than in a panel of its own. Absent when there is none — an
        // empty line reserved for text that is usually not there is a gap the layout has to
        // explain — and tappable, because the thing you are reading is the thing you want to edit.
        if (open.note.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                open.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clickable(enabled = mix.tagFormat != null && !onLoan) { noting = true }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        // The waveform takes whatever is left: this is what the screen is for below the cover, and a
        // fixed strip in the middle of an empty half-page reads as a placeholder.
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                open.loadingWaveform -> CircularProgressIndicator()
                open.envelope == null -> Text(
                    stringResource(R.string.player_no_waveform),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                else -> WaveformView(
                    envelope = open.envelope,
                    progress = positionMs.toFloat() / durationMs,
                    onScrub = { fraction ->
                        val ms = (fraction * durationMs).roundToLong().coerceIn(0, durationMs)
                        if (loaded) onSeek(ms) else scrubMs = ms
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(positionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.bodySmall)
        }

        // The notice sits directly above the tools it explains, which is where the dimming is: a
        // row of grey buttons with the reason at the top of the screen would be two things to
        // connect. It is the way out as well as the explanation — the same action the menu offers,
        // put where the dead end is met.
        onAddToLibrary?.let { add ->
            OnLoanNotice(onAddToLibrary = add)
        }

        Spacer(Modifier.height(12.dp))
        PlayerTools(
            mix = mix,
            targets = targets,
            busy = busy,
            hasNote = open.note.isNotEmpty(),
            onLoan = onLoan,
            onNote = { noting = true },
            onNormalize = onNormalize,
            onConvert = onConvert,
        )

        Spacer(Modifier.height(12.dp))
        // Every tap acts at once, and a second tap inside the double-tap window means "from the
        // top". Compose's own double-click would hold the *first* tap back to see whether a second
        // one is coming, which is a fifth of a second of nothing happening on the one button that
        // must never feel slow.
        var lastTapAt by remember { mutableLongStateOf(0L) }
        // A mix whose waveform could not be read is one this device cannot decode, and the transport
        // says so by not being a transport. The panel above has already given the reason, so the
        // button does not repeat it — it just stops inviting a press that ends in silence.
        val playable = open.loadingWaveform || open.envelope != null
        BigButton(
            text = stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
            enabled = playable,
            // In loop mode the lemniscate takes the transport's place rather than sitting next to
            // it: the label already says what a press will do, so the icon is free to say what kind
            // of playback this is.
            icon = when {
                looping -> ImageVector.vectorResource(R.drawable.ic_all_inclusive)
                playing -> Icons.Default.Pause
                else -> Icons.Default.PlayArrow
            },
            onClick = {
                val now = SystemClock.uptimeMillis()
                val second = now - lastTapAt < DOUBLE_TAP_MS
                lastTapAt = now
                // -1 resumes wherever the mix was left; a mark set before loading is passed as-is.
                if (second) onRestart(mix) else onPlayPause(mix, if (loaded) -1L else scrubMs)
            },
            // Holding the transport is where a mode belongs: repeat is something playback *is*, not
            // a second button competing with it.
            onLongClick = onToggleLoop,
        )
    }

    if (noting) {
        NoteDialog(
            initial = open.note,
            onSave = {
                noting = false
                onSaveNote(it)
            },
            onDismiss = { noting = false },
        )
    }
}

/**
 * Why half this screen is dimmed, and the one press that undoes it.
 *
 * A line rather than a dialog: nothing has gone wrong and nothing is waiting on an answer — the mix
 * plays perfectly well as it is, and this is only the reason the edits beneath it are grey. A
 * dialog on arrival would be a door to close before hearing what someone sent you.
 *
 * It carries the action as well as the reason, because the alternative is a sentence that names a
 * menu item and leaves the finding of it as an exercise.
 */
@Composable
private fun OnLoanNotice(onAddToLibrary: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = ControlShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Info,
                null,
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.player_on_loan),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            TextButton(onClick = onAddToLibrary) {
                Text(stringResource(R.string.action_add_to_library_short))
            }
        }
    }
}

/**
 * What can be done *to* the open mix, rather than with it — side by side above the transport, where
 * they can be seen rather than remembered.
 *
 * **Note is here and not in the overflow menu**, which is the opposite of where Rename ended up,
 * and the difference is what the two are. Rename is an action on a file, and the menu is where
 * every file action lives. A note is *content* — the only thing on this screen a person writes
 * rather than does — and content nobody can find is content nobody writes.
 */
@Composable
private fun PlayerTools(
    mix: Mix,
    targets: List<AudioTarget>,
    busy: Boolean,
    hasNote: Boolean,
    /** The mix is another app's file — see [PlayerScreen]. All three of these write; none can. */
    onLoan: Boolean,
    onNote: () -> Unit,
    onNormalize: (NormalizeMode, AudioTarget, Boolean) -> Unit,
    onConvert: (AudioTarget) -> Unit,
) {
    // Null until a mode is picked, then holds it while the second question is answered.
    var mode by remember { mutableStateOf<NormalizeMode?>(null) }
    var levelling by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton(
            text = stringResource(R.string.tool_level),
            icon = ImageVector.vectorResource(R.drawable.ic_graphic_eq),
            // A rewrite takes a moment on a long mix, and the spinner sits in the button that
            // started it rather than somewhere else on the screen.
            busy = busy,
            enabled = !busy && !onLoan,
            onClick = { levelling = true },
        )
        ToolButton(
            text = stringResource(R.string.tool_convert),
            icon = Icons.Default.SwapHoriz,
            busy = false,
            enabled = !busy && !onLoan && targets.isNotEmpty(),
            onClick = { converting = true },
        )
        ToolButton(
            text = stringResource(R.string.tool_note),
            // A bubble with writing in it against an empty one with a plus: the icon carries the
            // state, so the label does not have to change length under the thumb as a note is
            // saved — and a button whose width moves when you use it reads as a different button.
            icon = if (hasNote) Icons.AutoMirrored.Filled.Comment else Icons.Default.AddComment,
            busy = false,
            // A format this app cannot re-tag has nowhere to keep a note; the editor refuses those
            // for the same reason.
            enabled = !busy && !onLoan && mix.tagFormat != null,
            tint = if (hasNote) MaterialTheme.colorScheme.primary else null,
            onClick = onNote,
        )
    }

    // Two questions, one at a time: how loud, then what to do with the result. Every combination in
    // one dialog would be a grid to read; two lists are two glances.
    if (levelling) {
        ChoiceDialog(
            title = stringResource(R.string.level_title),
            body = { Text(stringResource(R.string.level_body), style = DialogBody) },
            options = listOf(
                stringResource(R.string.level_peak) to {
                    mode = NormalizeMode.PEAK
                    levelling = false
                },
                stringResource(R.string.level_loudness) to {
                    mode = NormalizeMode.LOUDNESS
                    levelling = false
                },
            ),
            onDismiss = { levelling = false },
        )
    }

    mode?.let { chosen ->
        // Overwriting is only on offer when the mix is already in a format this app writes: putting
        // the level back into an m4a would mean encoding it as something else and calling the
        // result the same file, which is not what "overwrite" means to anyone.
        val itsOwn = AudioTarget.of(mix.name)?.takeIf { it in targets }
        val overwriteLabel = stringResource(R.string.action_overwrite_mix)
        val copyLabels = targets.map { it to stringResource(R.string.action_save_normalised, it.label) }
        ChoiceDialog(
            title = stringResource(R.string.level_title),
            body = {
                Text(stringResource(R.string.level_where_body), style = DialogBody)
                if (itsOwn != null) {
                    Spacer(Modifier.height(8.dp))
                    // A lossy mix cannot be overwritten without being encoded again, and that costs
                    // a generation of quality on top of the usual "no undo".
                    val lossy = itsOwn == AudioTarget.MP3
                    val before = stringResource(
                        if (lossy) R.string.level_lossy_before else R.string.level_overwrite_before,
                    )
                    val after = stringResource(
                        if (lossy) R.string.level_lossy_after else R.string.level_overwrite_after,
                    )
                    val noUndo = stringResource(R.string.warning_no_undo)
                    Text(
                        buildAnnotatedString {
                            append(before)
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(noUndo) }
                            append(after)
                        },
                        style = DialogBody,
                    )
                }
            },
            options = buildList {
                if (itsOwn != null) {
                    add(
                        overwriteLabel to {
                            mode = null
                            onNormalize(chosen, itsOwn, true)
                        },
                    )
                }
                for ((target, label) in copyLabels) {
                    add(
                        label to {
                            mode = null
                            onNormalize(chosen, target, false)
                        },
                    )
                }
            },
            onDismiss = { mode = null },
        )
    }

    if (converting) {
        // Its own format is left off the list: a copy of a FLAC as a FLAC is a decode and an encode
        // to arrive back where it started.
        val others = targets.filter { it != AudioTarget.of(mix.name) }
        val labels = others.map { it to stringResource(R.string.action_convert_to, it.label) }
        val lossy = stringResource(R.string.convert_lossy, AudioTarget.MP3.label)
        ChoiceDialog(
            title = stringResource(R.string.convert_title),
            body = {
                Text(stringResource(R.string.convert_body), style = DialogBody)
                if (AudioTarget.MP3 in others) {
                    Spacer(Modifier.height(8.dp))
                    Text(lossy, style = DialogBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            options = labels.map { (target, label) ->
                label to {
                    converting = false
                    onConvert(target)
                }
            },
            onDismiss = { converting = false },
        )
    }
}

/** One of the three: an icon, a word, and a third of the row. */
@Composable
private fun RowScope.ToolButton(
    text: String,
    icon: ImageVector,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color? = null,
) {
    OutlinedButton(
        onClick = onClick,
        Modifier.weight(1f).height(48.dp),
        enabled = enabled,
        shape = ControlShape,
        contentPadding = ToolPadding,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(18.dp),
                // The accent is the button's own content colour, so saying it changes nothing on
                // its own — what makes it read as a *state* is being the one icon in the row that
                // is not the default ink.
                tint = tint ?: LocalContentColor.current,
            )
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * Writing the note: a field, and what becomes of what is typed in it.
 *
 * Emptying it is a legitimate answer, so Save is never disabled — unlike [NameDialog], where a
 * blank name is not a name. Clearing the field and saving removes the comment.
 */
@Composable
private fun NoteDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_title)) },
        text = {
            Column {
                Text(stringResource(R.string.note_body), style = DialogBody)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.note_hint)) },
                    shape = ControlShape,
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * The cover, with the two things that are about *the artwork itself* on a long press.
 *
 * Long press rather than a tap, and rather than buttons laid over the picture. The library already
 * teaches long-press-for-more on every row, so the gesture is not new here; and the player's cover
 * is the one large, quiet thing on the screen — putting controls on top of it would spend the
 * artwork to save one gesture, on a screen whose whole point is that the artwork is the subject.
 *
 * A plain tap is left doing nothing, deliberately, and with no ripple: there is no third action
 * worth inventing for it, and a ripple that leads nowhere is a promise the screen does not keep.
 * That is why this uses a raw gesture detector rather than `combinedClickable`, which insists on an
 * `onClick` and draws the indication for it.
 *
 * **Edit info is here as well as in the overflow menu**, which is a duplicate on purpose. The
 * overflow is where every *file* action lives — rename, move, share, delete — and it is where
 * someone who thinks "I want to change this file" will look. The cover is where someone who is
 * looking straight at wrong artwork will press, and making them find the corner menu instead is
 * the kind of small refusal that stops people tagging anything.
 */
@Composable
private fun CoverWithMenu(
    cover: ImageBitmap?,
    canCopy: Boolean,
    onCopy: () -> Unit,
    /** Null while the mix is another app's file, which nothing here can write to. */
    onEditTags: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        Box(
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        // The gesture has no on-screen affordance until it fires, so the hand gets
                        // the confirmation — as the library's own long press does.
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        open = true
                    },
                )
            },
        ) {
            Cover(cover, CoverSize)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (canCopy) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy_image)) },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = {
                        open = false
                        onCopy()
                    },
                )
            }
            onEditTags?.let { edit ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit_info)) },
                    leadingIcon = { Icon(Icons.Default.Sell, null) },
                    onClick = {
                        open = false
                        edit()
                    },
                )
            }
        }
    }
}

/**
 * Everything that can be done to the open mix, folded into one menu, in the order a library row's
 * menu uses — the two are the same list of jobs and should not have to be learned twice.
 *
 * Sharing leaves the app entirely and deleting is done rarely and never by accident, so neither
 * earns a permanent button: a delete beside a play button is an invitation to a mistake. The
 * favourite star is not here, being in the bar itself — a two-state control cannot show its state
 * from inside a menu that has to be opened to see it.
 *
 * [onAddToLibrary] being given is what says the mix came from another app, and it shortens the menu
 * to the two things such a file can actually do: keep it, and pass it on. Renaming, moving and
 * deleting are writes into the granted folder — the one place this file is not — so on a
 * handed-over mix they would fail against a uri the sender lent us read-only, or, on the rare
 * sender that lends more, quietly reach into another app's storage. Neither is something to offer.
 * Editing the tags goes with them: the editor is a form whose only purpose is saving, and one that
 * cannot save is a screen that wastes a person's typing. They all come back the moment the file is
 * saved, because by then it is an ordinary mix.
 */
@Composable
fun PlayerOverflowAction(
    onEditTags: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onAddToLibrary: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Only for a mix handed in from another app, and then it is the first thing in the
            // menu: the file is on loan for as long as this task lives, and keeping it is the one
            // thing worth doing to it before that runs out. Absent for library mixes, which are
            // already where this puts things.
            onAddToLibrary?.let { add ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_to_library)) },
                    leadingIcon = { Icon(Icons.Default.LibraryAdd, null) },
                    onClick = {
                        open = false
                        add()
                    },
                )
            }
            if (onAddToLibrary == null) {
                // First, and above Rename, which is the pair worth getting the right way round: the
                // title someone wants to fix is nearly always the one *in* the file, and renaming is
                // the thing you do to the file it lives in.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit_info)) },
                    leadingIcon = { Icon(Icons.Default.Sell, null) },
                    onClick = {
                        open = false
                        onEditTags()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                    onClick = {
                        open = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_to)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                    onClick = {
                        open = false
                        onMove()
                    },
                )
            }
            // Sharing a file on is reading it, which is exactly what the sender granted, so it
            // stays whichever kind of mix this is.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = {
                    open = false
                    onShare()
                },
            )
            if (onAddToLibrary == null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = {
                        open = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * The envelope, mirrored about the centre line, with everything up to [progress] filled in —
 * zoomable.
 *
 * **Two layers.** The average energy is the solid body and the peak is a faint outline behind it;
 * see [Envelope] for why a demo mix needs both, and why the body is the one carrying the shape.
 *
 * A viewport of [zoom] and offset maps fractions of the mix to pixels: `x = (frac - offset) * width
 * * zoom`. Zoom is what makes a long mix seekable to a bar rather than to a smudge — at 1x a thumb
 * covers several seconds of a five-minute file.
 *
 * All the gestures live in one detector, because they compete for the same finger:
 *  - **Tap** anywhere seeks there.
 *  - **Drag** pans when zoomed in, and scrubs at 1x.
 *  - **Two fingers** pinch to zoom and pan.
 *  - **Drag the strip along the bottom** (only when zoomed) to scroll fast.
 */
@Composable
private fun WaveformView(
    envelope: Envelope,
    progress: Float,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The played part is the waveform's own ink, the unplayed part the same ink dimmed. Position is
    // carried by the step in weight rather than by a change of colour, which is the reading a
    // waveform wants — and in this palette it is also the only reading available, there being no
    // second hue to colour the played half with.
    val played = MaterialTheme.colorScheme.onSurface.copy(alpha = WaveformInk)
    val unplayed = MaterialTheme.colorScheme.onSurface.copy(alpha = WaveformMuted)
    val peakPlayed = MaterialTheme.colorScheme.onSurface.copy(alpha = WaveformPeakInk)
    val peakUnplayed = MaterialTheme.colorScheme.onSurface.copy(alpha = WaveformPeakMuted)
    val panel = MaterialTheme.colorScheme.waveformPanel
    val zeroLine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    val scrollTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val scrollThumb = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

    // The viewport. Kept here rather than hoisted: it is how this mix is being *looked at*, and it
    // starts again from the whole file whenever a different one is opened.
    var zoom by remember(envelope) { mutableFloatStateOf(1f) }
    var offset by remember(envelope) { mutableFloatStateOf(0f) }

    // Zoomed in, a playing mix walks off the right edge within seconds and you are left watching a
    // stretch of waveform the sound has already left. The window comes after it whenever the
    // playhead leaves the visible span, landing it a tenth of the way in so there is a run of
    // waveform ahead of it rather than a jump on every frame.
    //
    // Always on, with no setting. The only reason to want the view somewhere the playhead is not is
    // to look ahead while it plays, and a pan does exactly that until the playhead catches up —
    // which is the moment you would have wanted to be brought back anyway. At 1x nothing happens,
    // because the whole mix is already in view.
    //
    // `progress` is mirrored through [rememberUpdatedState] because this effect is keyed on the file
    // and must not restart per frame: the mirror is snapshot state, so `snapshotFlow` registers it
    // as a dependency and re-emits on every update, where a closure over the parameter itself would
    // not.
    val currentProgress by rememberUpdatedState(progress)
    LaunchedEffect(envelope) {
        snapshotFlow { currentProgress }.collect { at ->
            val window = 1f / zoom
            if (zoom > 1f && (at < offset || at > offset + window)) {
                offset = (at - window * FollowLead).coerceIn(0f, (1f - window).coerceAtLeast(0f))
            }
        }
    }

    Canvas(
        modifier
            .clip(ControlShape)
            .background(panel)
            // Keep the system's edge swipes off the waveform: a scrub that starts near the screen
            // edge should seek the mix, not navigate back out of the app.
            .systemGestureExclusion()
            .pointerInput(envelope) {
                val slop = viewConfiguration.touchSlop
                val scrollbarTouch = ScrollbarTouch.toPx()

                fun clampOffset(o: Float) = o.coerceIn(0f, (1f - 1f / zoom).coerceAtLeast(0f))

                fun xToFrac(x: Float): Float {
                    val w = size.width.toFloat()
                    if (w <= 0f) return 0f
                    return (offset + (x / w) / zoom).coerceIn(0f, 1f)
                }

                fun applyZoom(factor: Float, focalX: Float) {
                    val w = size.width.toFloat()
                    if (w <= 0f) return
                    val newZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
                    val focalFrac = offset + (focalX / w) / zoom
                    zoom = newZoom
                    offset = clampOffset(focalFrac - (focalX / w) / newZoom)
                }

                fun applyPan(dxPixels: Float) {
                    val w = size.width.toFloat()
                    if (w <= 0f) return
                    offset = clampOffset(offset - dxPixels / (w * zoom))
                }

                /** Centre the visible window on the touched point — jump-to-position scrolling. */
                fun scrollTo(x: Float) {
                    val w = size.width.toFloat()
                    if (w <= 0f) return
                    offset = clampOffset((x / w) - (1f / zoom) / 2f)
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    var mode = GestureMode.NONE
                    var moved = false
                    var multiTouch = false

                    if (zoom > 1f && down.position.y >= size.height - scrollbarTouch) {
                        mode = GestureMode.SCROLLBAR
                        scrollTo(downX)
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break

                        if (pressed >= 2) {
                            multiTouch = true
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoomChange != 1f) applyZoom(zoomChange, centroid.x)
                            if (panChange.x != 0f) applyPan(panChange.x)
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val change = event.changes.first { it.pressed }
                        if (abs(change.position.x - downX) > slop) moved = true
                        val x = change.position.x
                        when (mode) {
                            GestureMode.SCROLLBAR -> {
                                scrollTo(x)
                                change.consume()
                            }

                            GestureMode.PAN -> {
                                applyPan(change.positionChange().x)
                                change.consume()
                            }

                            GestureMode.SCRUB -> {
                                onScrub(xToFrac(x))
                                change.consume()
                            }

                            GestureMode.NONE -> if (moved) {
                                // What a drag means depends on whether there is anywhere to go:
                                // panning when zoomed, scrubbing when the whole mix is already in
                                // view and a drag can only mean "move the playhead".
                                mode = if (zoom > 1f) GestureMode.PAN else GestureMode.SCRUB
                                if (mode == GestureMode.PAN) applyPan(change.positionChange().x)
                                if (mode == GestureMode.SCRUB) onScrub(xToFrac(x))
                                change.consume()
                            }
                        }
                    }

                    // A touch that never became anything else is a tap: seek there.
                    if (!moved && !multiTouch && mode == GestureMode.NONE) onScrub(xToFrac(downX))
                }
            },
    ) {
        val n = envelope.size
        if (n == 0) return@Canvas
        val w = size.width
        val mid = size.height / 2

        drawLine(
            color = zeroLine,
            start = Offset(0f, mid),
            end = Offset(w, mid),
            strokeWidth = 1.dp.toPx(),
        )

        // One column per pixel. At 1x that is several buckets folded together; zoomed in it is one
        // bucket stretched over several columns, which is where the extra resolution read from the
        // file earns itself.
        val edge = (progress.coerceIn(0f, 1f) - offset) * w * zoom
        val columns = max(1, w.toInt())
        for (px in 0 until columns) {
            val fL = offset + (px.toFloat() / w) / zoom
            val fR = offset + ((px + 1).toFloat() / w) / zoom
            if (fL > 1f) break
            val bL = (fL * n).toInt().coerceIn(0, n - 1)
            val bR = (fR * n).toInt().coerceIn(bL + 1, n)

            var peak = 0f
            var sumSquares = 0.0
            for (b in bL until bR) {
                peak = max(peak, envelope.peaks[b])
                // Folding several buckets into one column is a widening of the averaging window,
                // not a pick between them — so the squares are summed and rooted again rather than
                // the larger value taken. Taking the max would flatten exactly the variation this
                // layer is here to show.
                val r = envelope.rms[b]
                sumSquares += r.toDouble() * r
            }
            val rms = sqrt(sumSquares / (bR - bL)).toFloat()

            // Decibels, the same window everything else in the app uses: drawn linearly a passage
            // at −15 dBFS fills a sixth of the height and looks like a failure. A floor of one pixel
            // keeps silence on the zero line, so a gap reads as a gap in the file rather than as a
            // quiet bar.
            val peakHalf = max(1f, amplitudeToHeight(peak) * mid * 0.95f)
            val rmsHalf = max(1f, amplitudeToHeight(rms) * mid * 0.95f)
            val lit = px <= edge

            // The outline first, so the body lands on top of it. They are drawn as two rectangles
            // rather than one plus a difference: the body is opaque enough to cover what is behind
            // it, and a hollow outline would need the column split into two pieces above and below
            // the body, for a distinction nobody can see.
            drawRect(
                color = if (lit) peakPlayed else peakUnplayed,
                topLeft = Offset(px.toFloat(), mid - peakHalf),
                size = Size(1f, peakHalf * 2),
            )
            drawRect(
                color = if (lit) played else unplayed,
                topLeft = Offset(px.toFloat(), mid - rmsHalf),
                size = Size(1f, rmsHalf * 2),
            )
        }

        if (progress > 0f && edge >= 0f && edge <= w) {
            drawRect(
                color = played.copy(alpha = 0.9f),
                topLeft = Offset(edge - 1f, 0f),
                size = Size(2f, size.height),
            )
        }

        // The scroll strip, only once there is somewhere to scroll to: the thumb is the part of the
        // mix on screen, and dragging it covers the whole file in one movement.
        if (zoom > 1f) {
            val stripH = ScrollbarHeight.toPx()
            val y = size.height - stripH
            val radius = CornerRadius(stripH / 2f, stripH / 2f)
            drawRoundRect(
                scrollTrack,
                topLeft = Offset(0f, y),
                size = Size(w, stripH),
                cornerRadius = radius,
            )
            val thumbW = ((1f / zoom) * w).coerceAtLeast(stripH)
            val thumbX = (offset * w).coerceIn(0f, w - thumbW)
            drawRoundRect(
                scrollThumb,
                topLeft = Offset(thumbX, y),
                size = Size(thumbW, stripH),
                cornerRadius = radius,
            )
        }
    }
}

/** What a single finger is currently doing to the waveform. */
private enum class GestureMode { NONE, PAN, SCRUB, SCROLLBAR }

/**
 * How big the cover gets, and where it stops.
 *
 * Fixed rather than a fraction of the width. On the widest phone a cover that grew with the screen
 * would take the waveform's room without becoming any more useful — past about this size artwork
 * stops telling you anything more about which mix it is.
 */
private val CoverSize = 220.dp

/**
 * A tool button's inset. Tighter than a button's default, because three of them share the width of
 * one and the default would ellipsise the longest of the three words on a narrow phone.
 */
private val ToolPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)

/**
 * The waveform's four weights: a body and an outline, each played and unplayed.
 *
 * Wider gaps than a chromatic app would need, in both directions. Where the played and unplayed
 * halves could differ in hue as well as level, a small step in alpha is enough to read; here the
 * only difference available *is* the step. The same goes for the body against the outline behind
 * it — two layers of the same grey a few percent apart would read as one slightly fuzzy shape.
 *
 * The played outline is deliberately fainter than the *unplayed body*. The layers are a hierarchy
 * before they are a position: the body is the shape you read, and the outline is context around it,
 * so a peak should never out-shout an average that has not been reached yet.
 */
private const val WaveformInk = 0.85f
private const val WaveformMuted = 0.32f
private const val WaveformPeakInk = 0.30f
private const val WaveformPeakMuted = 0.13f

/** As far in as the viewport goes: a couple of seconds across the screen on a long mix. */
private const val MAX_ZOOM = 40f

/**
 * Where the playhead is put when the view is brought back to it: a tenth of the window in from the
 * left, so what follows is a stretch of waveform still to come rather than the edge of the screen.
 * Small, because the point is to keep playing in view, not to centre it — centring throws away half
 * the window on audio already heard.
 */
private const val FollowLead = 0.1f

/** The scroll strip along the bottom, and the reach for it. */
private val ScrollbarHeight = 10.dp
private val ScrollbarTouch = 32.dp

/**
 * How soon a second tap on Play counts as "start from the beginning" rather than as a new press.
 * The platform's own double-tap window, which is what a thumb expects.
 */
private const val DOUBLE_TAP_MS = 300L
