// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.singular.player.ApplyMode
import de.singular.player.R
import de.singular.player.TagDefaults
import de.singular.player.TagEdit
import de.singular.player.audio.Tags

/**
 * What a mix says about itself, made editable.
 *
 * The cover is at the top and is the only thing here that is not a text field, so it gets the
 * layout's one piece of emphasis — it is also the field most likely to be the *reason* someone
 * opened this screen, a folder of bounces being far more likely to have names than artwork.
 *
 * **The defaults button is above the fields, not below them.** It fills the form rather than writing
 * the file, so it is something you press *before* typing, and a control that acts on the fields
 * under it belongs over them. Pressing it and then correcting one field is the intended path through
 * this screen, and it reads that way top to bottom.
 *
 * Saving is a full-width button at the bottom rather than a tick in the app bar. A re-tag rewrites
 * the whole file — it is not a cheap incremental change, and it deserves a control with the weight
 * of one.
 */
@Composable
fun TagEditorScreen(
    edit: TagEdit,
    defaults: TagDefaults,
    busy: Boolean,
    onEdit: ((Tags) -> Tags) -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onPasteCover: () -> Unit,
    onApplyDefaults: (ApplyMode) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (edit.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    // Two different refusals, and they are worth telling apart: one is "this app will never write
    // this kind of file", the other is "this particular file is not what it says it is".
    if (!edit.supported || !edit.readable) {
        Hint(
            stringResource(
                if (!edit.supported) R.string.editor_unsupported else R.string.editor_unreadable,
            ),
            modifier,
        )
        return
    }

    var askingMode by remember { mutableStateOf(false) }
    val cover = rememberDecoded(edit.cover?.bytes)

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.clickable(onClick = onPickCover)) {
                    Cover(cover, CoverSize)
                }
                // The two cover actions sit on the artwork rather than under it as buttons: they
                // are about the thing they are on, and a row of labelled buttons beneath a picture
                // reads as belonging to the fields below instead.
                Row(
                    Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Only while there is something to paste. A permanently visible paste button
                    // that usually does nothing teaches people to ignore it; one that appears
                    // exactly when a cover has been copied is the whole affordance for the
                    // copy-from-the-last-mix path, and needs no explaining.
                    if (rememberClipboardHasImage()) {
                        CoverAction(
                            icon = Icons.Default.ContentPaste,
                            label = stringResource(R.string.cd_paste_cover),
                            onClick = onPasteCover,
                        )
                    }
                    if (edit.cover != null) {
                        CoverAction(
                            icon = Icons.Default.Close,
                            label = stringResource(R.string.cd_remove_cover),
                            onClick = onRemoveCover,
                        )
                    }
                    CoverAction(
                        icon = Icons.Default.Edit,
                        label = stringResource(R.string.cd_change_cover),
                        onClick = onPickCover,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // The filename, which is not editable here — renaming is its own action, on the file rather
        // than on what it says. Shown because the title field above it very often started life as
        // this, and knowing which is which is the difference between the two.
        Text(
            edit.mix.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        if (!defaults.isEmpty) {
            OutlinedButton(
                onClick = { askingMode = true },
                Modifier.fillMaxWidth(),
                enabled = !busy,
                shape = ControlShape,
            ) { Text(stringResource(R.string.editor_use_defaults)) }
            Spacer(Modifier.height(16.dp))
        }

        Field(R.string.field_title, edit.tags.title) { v -> onEdit { it.copy(title = v) } }
        Field(R.string.field_artist, edit.tags.artist) { v -> onEdit { it.copy(artist = v) } }
        Field(R.string.field_album, edit.tags.album) { v -> onEdit { it.copy(album = v) } }
        Field(R.string.field_album_artist, edit.tags.albumArtist) { v ->
            onEdit { it.copy(albumArtist = v) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Side by side because both are short and numeric, and a full-width field for four
            // digits invites the question of what else is meant to go in it.
            Field(
                R.string.field_year,
                edit.tags.year,
                Modifier.weight(1f),
                numeric = true,
            ) { v -> onEdit { it.copy(year = v) } }
            Field(
                R.string.field_track,
                edit.tags.track,
                Modifier.weight(1f),
                numeric = true,
            ) { v -> onEdit { it.copy(track = v) } }
        }
        Field(R.string.field_genre, edit.tags.genre) { v -> onEdit { it.copy(genre = v) } }
        Field(R.string.field_comment, edit.tags.comment, lines = 3) { v ->
            onEdit { it.copy(comment = v) }
        }

        Spacer(Modifier.height(20.dp))
        BigButton(
            text = stringResource(R.string.action_save),
            icon = null,
            enabled = !busy,
            onClick = onSave,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.editor_rewrite_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }

    if (askingMode) {
        ApplyModeDialog(
            onPick = {
                askingMode = false
                onApplyDefaults(it)
            },
            onDismiss = { askingMode = false },
        )
    }
}

/**
 * Fill gaps, or replace? — the one question applying defaults has to ask.
 *
 * Asked every time rather than remembered as a preference. The two answers differ in whether
 * anything can be lost, and a remembered setting is exactly the shape of mistake where someone
 * chooses "replace" once for a folder that needed it and then wipes a tagged folder a month later
 * without being asked. It is two taps, once per batch.
 */
@Composable
fun ApplyModeDialog(onPick: (ApplyMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apply_defaults_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.apply_defaults_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { onPick(ApplyMode.FILL_GAPS) },
                    Modifier.fillMaxWidth(),
                    shape = ControlShape,
                ) { Text(stringResource(R.string.apply_defaults_fill)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onPick(ApplyMode.REPLACE) },
                    Modifier.fillMaxWidth(),
                    shape = ControlShape,
                ) { Text(stringResource(R.string.apply_defaults_replace)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Asked when leaving an editor with unsaved changes — see the back chain in MainActivity. */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_discard_title)) },
        text = { Text(stringResource(R.string.editor_discard_body)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_keep_editing)) }
        },
    )
}

/**
 * One labelled field.
 *
 * [numeric] only asks for the number pad; it does not restrict what can be typed. A year field that
 * refused non-digits would also refuse a paste, and the formats these go into are all free text —
 * a track number of `A1` is legal in every one of them, and is what a vinyl-side bounce is called.
 */
@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    lines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = lines == 1,
        minLines = lines,
        maxLines = if (lines == 1) 1 else lines + 3,
        shape = ControlShape,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            // Names, album titles and genres are all capitalised; only the numeric fields are not.
            capitalization = if (numeric) {
                KeyboardCapitalization.None
            } else {
                KeyboardCapitalization.Words
            },
        ),
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

/**
 * A small round button laid over the artwork.
 *
 * On its own dark scrim rather than tinted from the theme: it sits on an arbitrary image, so
 * whatever colour the palette would give it might land on something the same colour. The scrim is
 * the same one the library row uses to mark a playing mix, so the two agree about what "a control
 * over artwork" looks like.
 */
@Composable
private fun CoverAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .background(ActionScrim, CircleShape)
            .clickable(onClick = onClick, onClickLabel = label),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, Modifier.size(18.dp), tint = Color.White)
    }
}

private val ActionScrim = Color(0xAA000000)

/** Large enough to judge artwork by, small enough to leave the first fields on screen with it. */
private val CoverSize = 180.dp
