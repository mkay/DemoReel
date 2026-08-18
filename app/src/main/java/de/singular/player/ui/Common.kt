// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.singular.player.R
import de.singular.player.storage.ImageClipboard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/**
 * `1:23`, or `1:02:03` once a mix runs past the hour.
 *
 * `Locale.US` on purpose. A timecode is a timecode in every language: minutes and seconds are
 * separated by a colon on a stopwatch and in a DAW alike, and handing this `Locale.getDefault()`
 * would put a comma in the middle of it in half of Europe, which reads as a different quantity
 * rather than as a local convention. Sizes are the opposite case — see [formatSize].
 *
 * The hour appears only when there is one, rather than a leading `0:` on every row: a demo mix is
 * minutes long, and a column of `0:03:41` costs three characters on every line to be right about the
 * one file in a hundred that is a whole live set.
 */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * `1.5 MB` — or `1,5 MB` to a reader whose language writes it that way.
 *
 * The number follows the language, unlike a timecode: a size is an ordinary decimal, and `1.5` in
 * German is how you write one and a half thousandths of nothing. The unit symbol does not follow it
 * — MB is MB, and the SI symbols are the same word in every language this will be translated into,
 * so there is nothing here for a translator to get hold of.
 */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000f)
    bytes >= 1_000 -> "${bytes / 1_000} kB"
    else -> "$bytes B"
}

/**
 * `MP3`, `FLAC` — a file's format, from its name.
 *
 * Worth saying out loud on every row: the folder is the user's own, so it holds whatever was bounced
 * into it, and what a file *is* decides what can be done to it — only some formats can have their
 * tags rewritten. Empty for a name with no extension to read.
 */
fun formatKind(name: String): String =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase() ?: ""

/**
 * `1 Aug, 23:27` for a mix from this year, `2 Jun 2025, 13:26` for one from any other.
 *
 * The year is written only where it says something. Within the current year the date is already
 * unambiguous without it, and a library is mostly this year's work, so carrying it on every row
 * costs five characters on the row that can least afford them to repeat what the calendar says.
 *
 * It is a convention rather than a statement — a reader who does not know the rule cannot tell
 * `2 Jun` from the same date last year. What makes it safe is that the exception is visible: any mix
 * from another year carries its year, so a folder spanning years teaches the pattern on sight, and a
 * folder that does not span years has only one year to mean.
 */
fun formatDate(epochMs: Long): String {
    if (epochMs <= 0) return ""
    val date = Date(epochMs)
    val year = Calendar.getInstance().apply { time = date }.get(Calendar.YEAR)
    val pattern =
        if (year == Calendar.getInstance().get(Calendar.YEAR)) "d MMM, HH:mm"
        else "d MMM yyyy, HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

/**
 * Linear amplitude (0f..1f) to a fraction of the height available, over a 60 dB window.
 *
 * Every level in this app is drawn through here, because they are all pictures of the same thing and
 * must agree. A mix peaking at −15 dBFS is a perfectly good bounce; drawn linearly it fills a sixth
 * of the height and reads as a failure.
 *
 * 60 dB is the window a mix on a phone actually occupies: below that is the noise floor, and giving
 * it height only makes silence look like signal.
 */
fun amplitudeToHeight(level: Float): Float {
    if (level <= 0f) return 0f
    val db = 20f * log10(max(level, 1e-6f))
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

/** One text field and two buttons: what naming a folder and renaming a mix both come down to. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = ControlShape,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * A dialog whose answers are its buttons — a line of explanation, then one full-width choice per
 * line, and Cancel where a confirm button would be.
 *
 * For questions where the *options* are the content: "how loud", "what format". A pair of
 * confirm/dismiss buttons under a paragraph can only ask a yes-or-no, and turning a three-way
 * choice into two yes-or-nos is how a dialog ends up asking a question nobody can answer without
 * reading it twice.
 */
@Composable
fun ChoiceDialog(
    title: String,
    body: @Composable () -> Unit,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                body()
                options.forEach { (label, onClick) ->
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onClick,
                        Modifier.fillMaxWidth(),
                        shape = ControlShape,
                    ) { Text(label, textAlign = TextAlign.Center) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** What a dialog's explanation is set in — one step down from the title, and quiet. */
val DialogBody
    @Composable get() = MaterialTheme.typography.bodyMedium

/**
 * A mix's cover, or the mark that stands in for one.
 *
 * Always square and always the same size whether there is artwork or not, because the alternative is
 * a layout that moves when a cover finishes loading — and covers load a beat after the screen does.
 *
 * The placeholder is the app's own reel glyph on the waveform panel's ground, at a low tint. It is
 * deliberately quiet: an untagged folder is the state every folder starts in, and a column of loud
 * "no image" boxes would make the ordinary case look like a fault.
 */
@Composable
fun Cover(
    cover: ImageBitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CoverShape)
            .background(MaterialTheme.colorScheme.waveformPanel),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                // Null rather than a description: the cover is decoration beside a name that
                // already says which mix this is, and there is nothing true to say about an image
                // whose contents we cannot see.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Crop rather than fit: covers are square by convention but arrive at whatever
                // aspect the person who made them chose, and letterboxing one inside a square frame
                // puts two different greys in the same box.
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                ImageVector.vectorResource(R.drawable.ic_graphic_eq),
                contentDescription = null,
                Modifier.size(size / 3),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = PlaceholderTint),
            )
        }
    }
}

/** Enough to read as a mark, not enough to read as a missing image. */
private const val PlaceholderTint = 0.25f

/**
 * Whether the clipboard is holding an image right now.
 *
 * Lives here beside [Cover] because both places that offer a cover — the tag editor and the tag
 * defaults — need the same answer, and a paste affordance that appeared in one and not the other
 * would read as a limitation rather than as an oversight.
 *
 * Watched rather than sampled once, because the clipboard is very often filled *while the screen
 * asking is already open*: the whole point of copying a cover is to go somewhere else and paste it.
 * A value read at first composition would leave the button missing at exactly the moment it was
 * wanted.
 *
 * Two sources, because neither is enough alone. The listener catches a change made while this
 * screen is in front; the resume check catches one made in *another app*, since a listener does not
 * fire for a process that was not running and can be throttled while backgrounded.
 *
 * Only the clipboard's description is read, never its contents — see [ImageClipboard.hasImage].
 */
@Composable
fun rememberClipboardHasImage(): Boolean {
    val context = LocalContext.current
    var present by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        present = ImageClipboard.hasImage(context)
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            present = ImageClipboard.hasImage(context)
        }
        manager?.addPrimaryClipChangedListener(listener)
        onDispose { manager?.removePrimaryClipChangedListener(listener) }
    }

    LifecycleResumeEffect(context) {
        present = ImageClipboard.hasImage(context)
        onPauseOrDispose { }
    }

    return present
}

/** A thumb-height, full-width action — the shape of every button hit without looking. */
@Composable
fun BigButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val sized = modifier.height(64.dp).let { if (modifier == Modifier) it.fillMaxWidth() else it }
    if (onLongClick != null) {
        // Material's buttons take one gesture only, so a button with two is built from a surface.
        // Same shape, same height, same colours — it is the same button, holdable.
        HoldableButton(sized, enabled, onClick, onLongClick, content)
    } else if (outlined) {
        OutlinedButton(onClick, sized, enabled = enabled, shape = ControlShape) { content() }
    } else {
        Button(onClick, sized, enabled = enabled, shape = ControlShape) { content() }
    }
}

@Composable
private fun HoldableButton(
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.combinedClickable(
            enabled = enabled,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        shape = ControlShape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContainer)
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContent)
        },
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

/** Material's own disabled steps, restated because the surface-built button sets its own colours. */
private const val DisabledContainer = 0.12f
private const val DisabledContent = 0.38f

/** A hint in the middle of an empty area — no folder chosen, nothing in this one, nothing marked. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
