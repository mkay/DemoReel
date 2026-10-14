// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.singular.player.KeyedMix
import de.singular.player.LibraryState
import de.singular.player.PlaybackState
import de.singular.player.R
import de.singular.player.storage.Folder
import de.singular.player.storage.Mix

/**
 * The granted folder, browsed in place.
 *
 * The tree the user pointed at is browsed as the tree it already is, sub-folders and all, rather
 * than being flattened into a library with tags of the app's own. Whatever they organise here is the
 * same thing they see from a desktop, which is the point of pointing the app at their own storage
 * rather than importing into ours.
 */
@Composable
fun LibraryScreen(
    state: LibraryState,
    playback: PlaybackState,
    onOpenFolder: (Folder) -> Unit,
    onBreadcrumb: (Int) -> Unit,
    onCreateFolder: (String) -> Unit,
    /** Opens the system folder picker — the library is empty until one is granted. */
    onChooseFolder: () -> Unit,
    onPlay: (Mix) -> Unit,
    onOpen: (Mix) -> Unit,
    onRename: (Uri, String) -> Unit,
    onDelete: (Uri) -> Unit,
    onShare: (Mix) -> Unit,
    onEditTags: (Mix) -> Unit,
    /** Opens the destination picker for one mix, or for a whole batch of them. */
    onMove: (List<Uri>) -> Unit,
    selection: Set<String>,
    onToggleSelect: (String) -> Unit,
    favouriteKeys: Set<String>,
    favouriteKeyOf: (Uri) -> String?,
    onToggleFavourite: (Mix) -> Unit,
    favourites: List<KeyedMix>?,
    onLoadFavourites: () -> Unit,
    // Hoisted, because back has to be able to leave the favourites tab, and the whole back chain
    // lives with the screen state a level up. Handling it here would leave which handler wins to
    // the order the two compositions happen to register in.
    tab: LibraryTab,
    onTabChange: (LibraryTab) -> Unit,
    /** Start or stop whatever the mini player holds — what the wordmark's disc does. */
    onPlayPause: () -> Unit,
    /**
     * The search, hoisted for the same reason as [tab]: Back closes it, and it has to be able to
     * say so before the favourites tab or the folder tree claims the press. [searchOpen] is whether
     * the field is out; [query] is what is in it. On the favourites tab [favourites] arrive already
     * narrowed by the query, and on the files tab [searchResults] replace the folder, with
     * [searchLoading] while files are still being opened for their tags.
     */
    searchOpen: Boolean,
    query: String,
    searchResults: List<KeyedMix>?,
    searchLoading: Boolean,
    onOpenSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selecting = selection.isNotEmpty()

    // Resolved on arrival rather than held: mixes can be renamed, moved or deleted between one look
    // and the next, and a stale list of favourites is worse than a moment's wait.
    LaunchedEffect(tab, favouriteKeys) { if (tab == LibraryTab.FAVOURITES) onLoadFavourites() }
    var newFolder by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) } // uri being renamed
    var renamingName by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingName by rememberSaveable { mutableStateOf("") }

    // Out of the way while picking, like the tabs: a batch belongs to the folder it was made in.
    val searching = searchOpen && !selecting
    // Whether the search field holds the keyboard — what a drag of the results takes away again.
    var fieldFocused by remember { mutableStateOf(false) }
    val dismissKeyboard = rememberDismissKeyboardOnDrag { fieldFocused }

    Column(modifier.fillMaxSize()) {
        // The lockup heads the whole screen rather than the listing, so it stays put when the tabs
        // are switched — a mark that moved or vanished on the way to the favourites would read as
        // part of the list rather than as the top of the app.
        //
        // Except while searching. With the keyboard up, the mark, the tabs and the folder path
        // together left the results under half of what was left of the screen, and the mark is
        // the one of the three that says nothing about the search. It folds away rather than
        // vanishing, so the list does not jump by its height in one frame.
        AnimatedVisibility(
            visible = !searching,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Wordmark(
                spinning = playback.playing,
                // Only with something in the mini player to start or stop. With nothing loaded the
                // disc is the mark and nothing more: a control that does nothing is worse than
                // one that does not offer itself.
                onPlayPause = onPlayPause.takeIf { playback.mix != null },
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        // Hidden while picking: the contextual bar has taken the top of the screen, and switching
        // tabs mid-batch would abandon a selection that only means anything in the folder it was
        // made in.
        if (!selecting) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
                LibraryTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { onTabChange(entry) },
                        text = { Text(stringResource(entry.title)) },
                    )
                }
            }
        }

        // The field is drawn where the pull opened its gap, directly above the list, so it arrives
        // where the finger left it — under the folder path on the files tab, not over it.
        val searchHint: @Composable () -> Unit = {
            Icon(Icons.Default.Search, contentDescription = null)
        }
        val matching = searching && query.isNotBlank()

        if (tab == LibraryTab.FAVOURITES) {
            if (searching) SearchField(query, onQueryChange, onCloseSearch) { fieldFocused = it }
            PullToReveal(
                enabled = !searchOpen && !selecting,
                onReveal = onOpenSearch,
                hint = searchHint,
                modifier = Modifier.weight(1f).nestedScroll(dismissKeyboard),
            ) {
                FlatMixList(
                    mixes = favourites,
                    empty = stringResource(
                        if (matching) R.string.search_no_match else R.string.favourites_empty,
                    ),
                    playback = playback,
                    onPlay = onPlay,
                    onOpen = onOpen,
                    onToggleFavourite = onToggleFavourite,
                    onRename = { mix ->
                        renaming = mix.uri.toString()
                        renamingName = mix.baseName
                    },
                    onDelete = { mix ->
                        deleting = mix.uri.toString()
                        deletingName = mix.name
                    },
                    onShare = onShare,
                    onMove = { mix -> onMove(listOf(mix.uri)) },
                    onEditTags = onEditTags,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Breadcrumb(state.path, onBreadcrumb, Modifier.weight(1f))
            IconButton(onClick = { newFolder = true }, enabled = state.current != null) {
                Icon(
                    Icons.Default.CreateNewFolder,
                    contentDescription = stringResource(R.string.cd_new_subfolder),
                )
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()

        val listing = state.listing
        if (searching) SearchField(query, onQueryChange, onCloseSearch) { fieldFocused = it }
        PullToReveal(
            // Nothing to search before a folder has been granted.
            enabled = !searchOpen && !selecting && state.current != null,
            onReveal = onOpenSearch,
            hint = searchHint,
            modifier = Modifier.weight(1f).nestedScroll(dismissKeyboard),
        ) {
            when {
                state.current == null ->
                    Hint(
                        stringResource(R.string.library_no_folder),
                        action = stringResource(R.string.action_choose_folder),
                        onAction = onChooseFolder,
                    )

                // The breadcrumb above stays, and says where the search starts: this folder and
                // everything under it. Moving up the crumbs widens it.
                matching -> FlatMixList(
                    mixes = searchResults,
                    empty = stringResource(R.string.search_no_match),
                    playback = playback,
                    onPlay = onPlay,
                    onOpen = onOpen,
                    onToggleFavourite = onToggleFavourite,
                    onRename = { mix ->
                        renaming = mix.uri.toString()
                        renamingName = mix.baseName
                    },
                    onDelete = { mix ->
                        deleting = mix.uri.toString()
                        deletingName = mix.name
                    },
                    onShare = onShare,
                    onMove = { mix -> onMove(listOf(mix.uri)) },
                    onEditTags = onEditTags,
                    favouriteKeys = favouriteKeys,
                    loading = searchLoading,
                    modifier = Modifier.fillMaxSize(),
                )

                listing?.error != null -> Hint(stringResource(listing.error.message))
                listing != null && listing.folders.isEmpty() && listing.mixes.isEmpty() ->
                    Hint(stringResource(R.string.library_empty))

                listing != null -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(listing.folders, key = { _, it -> it.uri.toString() }) { i, folder ->
                        if (i > 0) RowDivider()
                        FolderRow(
                            folder = folder,
                            selected = folder.uri.toString() in selection,
                            selecting = selecting,
                            onOpen = { onOpenFolder(folder) },
                            onToggleSelect = { onToggleSelect(folder.uri.toString()) },
                            onRename = {
                                renaming = folder.uri.toString()
                                renamingName = folder.name
                            },
                            onDelete = {
                                deleting = folder.uri.toString()
                                deletingName = folder.name
                            },
                        )
                    }
                    itemsIndexed(listing.mixes, key = { _, it -> it.uri.toString() }) { i, mix ->
                        if (i > 0 || listing.folders.isNotEmpty()) RowDivider()
                        MixRow(
                            mix = mix,
                            playing = playback.uri == mix.uri && playback.playing,
                            selected = mix.uri.toString() in selection,
                            selecting = selecting,
                            favourite = favouriteKeyOf(mix.uri) in favouriteKeys,
                            onToggleFavourite = { onToggleFavourite(mix) },
                            onPlay = { onPlay(mix) },
                            onOpen = { onOpen(mix) },
                            onToggleSelect = { onToggleSelect(mix.uri.toString()) },
                            onRename = {
                                renaming = mix.uri.toString()
                                renamingName = mix.baseName
                            },
                            onDelete = {
                                deleting = mix.uri.toString()
                                deletingName = mix.name
                            },
                            onShare = { onShare(mix) },
                            onMove = { onMove(listOf(mix.uri)) },
                            onEditTags = { onEditTags(mix) },
                        )
                    }
                }

                else -> Spacer(Modifier.fillMaxSize())
            }
        }
    }

    if (newFolder) {
        NameDialog(
            title = stringResource(R.string.folder_new_title),
            initial = "",
            confirm = stringResource(R.string.action_create),
            onConfirm = {
                newFolder = false
                onCreateFolder(it)
            },
            onDismiss = { newFolder = false },
        )
    }

    renaming?.let { uri ->
        NameDialog(
            title = stringResource(R.string.rename_title),
            initial = renamingName,
            confirm = stringResource(R.string.action_rename),
            onConfirm = {
                renaming = null
                onRename(Uri.parse(uri), it)
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { uri ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_one_title)) },
            text = { Text(stringResource(R.string.delete_one_message, deletingName)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(Uri.parse(uri))
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * The lockup at the top of the library: the disc, the word beside it, and both of the jobs a mark
 * at the top of a screen can do.
 *
 * **It turns while something is playing.** The mark is a record, and a record that is not moving is
 * a record that is not playing — so the screen says what the transport says, in the one place the
 * eye lands on first.
 *
 * Drawn in three layers rather than as one rotating image, because two parts of it must not turn.
 * The flat lockup is the ground, so the *word* stays level; a second copy of the disc turns over
 * it — same circle, same size, so it covers the still one exactly; and the glyph from the hub goes
 * back on top, held still, because a record spins and a play button does not. A glyph going round
 * with the grooves would read as a loading spinner.
 *
 * **The disc is the play/pause button for the mini player's mix.** A turning record invites the
 * hand that stops it, and the disc already turns exactly while something plays, so the thing that
 * shows the state is the thing that changes it. The hub glyph says what a tap will do, as the mini
 * player's button does: pause bars while it turns, the triangle while it is still. Only the disc —
 * the word is left alone, which keeps the target the size of what it means and makes a stray tap on
 * the logo less likely to start the music.
 *
 * It used to be Home. That went: the breadcrumb's first crumb and the tab row reach the same places,
 * and the disc had a better use.
 *
 * Decorative to a screen reader when it is only a mark; it takes a label once it is also a button,
 * because then it is a control and unlabelled controls are unreachable. The bar already names this
 * screen either way, and the wordmark says the same word again in a picture.
 */
@Composable
private fun Wordmark(
    spinning: Boolean,
    onPlayPause: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // An Animatable rather than an infinite transition, because the disc has to *stop* where it is
    // and carry on from there: a rotation that snapped back to zero on pause would read as the
    // player being reset. Each turn is animated on its own and the angle folded back under 360°, so
    // an afternoon of playback cannot walk a float out of precision.
    val turn = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        while (spinning) {
            turn.snapTo(turn.value % 360f)
            turn.animateTo(turn.value + 360f, tween(SpinMillis, easing = LinearEasing))
        }
    }

    val action = stringResource(if (spinning) R.string.cd_pause else R.string.cd_play)
    Box(modifier.height(WordmarkHeight)) {
        Image(
            painter = painterResource(R.drawable.ic_wordmark),
            contentDescription = null,
            modifier = Modifier.fillMaxHeight(),
        )
        val disc = Modifier.fillMaxHeight().aspectRatio(1f)
        Box(
            if (onPlayPause == null) {
                disc
            } else {
                disc.clip(CircleShape)
                    .clickable(onClick = onPlayPause)
                    .semantics { contentDescription = action }
            },
        ) {
            Image(
                painter = painterResource(R.drawable.ic_wordmark_reel),
                contentDescription = null,
                modifier = disc.graphicsLayer { rotationZ = turn.value },
            )
            Image(
                painter = painterResource(
                    if (spinning) R.drawable.ic_wordmark_pause else R.drawable.ic_wordmark_play,
                ),
                contentDescription = null,
                modifier = disc,
            )
        }
    }
}

/**
 * The search box, once a pull has brought it out. It takes the keyboard on arrival — the pull was
 * the request to type — and its close button empties it and puts it away in one, as Back does.
 *
 * A filled pill rather than Material's outlined field. The outline is drawn in the accent while
 * focused, and since this field is focused for its whole life that was an accent frame round the
 * top of the list for as long as a search was open — the loudest thing on the screen, saying only
 * that there was a box. A step off the page says the same thing quietly, and the accent is left to
 * the cursor, which is the one part of the field that is news. Built on [BasicTextField] because
 * the Material fields will not go below 56dp, and this sits between the folder path and the rows.
 *
 * Round rather than the [ControlShape] corners the buttons use, on purpose: a search field is the
 * one control here whose shape is recognised before its contents are read.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    val text = MaterialTheme.typography.bodyLarge
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focus)
                .onFocusChanged { onFocusChange(it.isFocused) },
            singleLine = true,
            textStyle = text.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            // Results arrive as the words are typed, so the action key has nothing left to do but
            // get the keyboard off the list — the same as dragging the list does, see
            // [rememberDismissKeyboardOnDrag]. Focus goes with it, so the field is not left with a
            // cursor blinking in it and no keyboard to type with.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.search_hint),
                            style = text,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    field()
                }
            },
        )
        IconButton(onClick = onClose) {
            Icon(
                Icons.Default.Close,
                stringResource(R.string.cd_close_search),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Put the keyboard away when the list under the search field is dragged.
 *
 * Not every keyboard has a key that hides it, and one that has none leaves the results a strip
 * between the field and the keys for as long as the search is open. A button in the field to do the
 * keyboard's job would sit beside the close button and look like a second way of closing. Dragging
 * the list is what someone who wants to see more of it does anyway, so that is what hides it — the
 * way the platform's own search screens behave. Tapping the field brings the keyboard back, with
 * the words still in it.
 *
 * Only a drag counts, not a fling's tail or a list scrolled from code, and only while the field
 * [hasKeyboard]; otherwise this passes every scroll through untouched.
 */
@Composable
private fun rememberDismissKeyboardOnDrag(hasKeyboard: () -> Boolean): NestedScrollConnection {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val holding by rememberUpdatedState(hasKeyboard)
    return remember(focusManager, keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && holding()) {
                    // Clearing focus is what hides the keyboard of a Compose text field; the
                    // explicit hide covers a keyboard that does not take the hint.
                    focusManager.clearFocus()
                    keyboard?.hide()
                }
                return Offset.Zero
            }
        }
    }
}

@Composable
private fun Breadcrumb(path: List<Folder>, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        path.forEachIndexed { index, folder ->
            if (index > 0) {
                Icon(Icons.Default.ChevronRight, contentDescription = null, Modifier.size(16.dp))
            }
            Text(
                folder.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (index == path.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clickable(enabled = index != path.lastIndex) { onJump(index) }
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderRow(
    folder: Folder,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectionTint(selected)
            .rowClicks(selecting, onOpen, onToggleSelect)
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectionTick(selected)
        } else {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                // The same footprint a cover takes, so folder names and mix names start at the same
                // place however the list is mixed.
                Modifier.size(LeadingSlot).padding(12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(RowGap))
        Text(folder.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!selecting) RowMenu(onRename = onRename, onDelete = onDelete, onShare = null)
    }
}

/**
 * One mix: the cover, what it calls itself, and what it is.
 *
 * The cover leads, which is the difference between this row and a recorder's. A demo mix is a
 * finished thing with artwork on it, and a folder of them is scanned by picture far faster than by
 * name — the names being, very often, a date and a version number.
 *
 * The play control is the cover itself rather than a triangle beside it: at 44dp the artwork is
 * already a comfortable target, and putting the transport on it saves the row a whole 48dp slot in a
 * line that has a name, a duration, a favourite and a menu to fit as well. A triangle appears over
 * the cover on the mix that is playing, which is also how the row says it is the one making noise.
 * Tapping the name opens it in the player — one meaning per target.
 */
@Composable
internal fun MixRow(
    mix: Mix,
    playing: Boolean,
    selected: Boolean,
    selecting: Boolean,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onEditTags: () -> Unit,
    // Where the mix lives, for lists that gather mixes from more than one folder. Null in the
    // library itself, where every row is in the folder named at the top of the screen already.
    folder: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectionTint(selected)
            .rowClicks(selecting, onOpen, onToggleSelect)
            .padding(RowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tick takes the cover's slot rather than the menu's, which is not where the pattern
        // usually puts it: play is the one control here that would make a noise if it were still
        // live while picking, so it is the one that has to go.
        if (selecting) {
            SelectionTick(selected)
        } else {
            RowCover(mix = mix, playing = playing, onPlay = onPlay)
        }
        Spacer(Modifier.width(RowGap))
        Column(Modifier.weight(1f)) {
            Text(
                mix.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val separator = stringResource(R.string.meta_separator)
            // The artist gets its own line rather than joining the facts below, because it is the
            // only thing here a person reads *as words* — everything on the third line is a number
            // or a code, and mixing a name into that column makes it scan as one of them. Absent on
            // a file with no artist tag, so an untagged folder is two lines rather than a column of
            // blanks with the facts pushed down.
            if (mix.tags.artist.isNotEmpty()) {
                Text(
                    mix.tags.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                buildString {
                    formatKind(mix.name).takeIf { it.isNotEmpty() }?.let {
                        append(it)
                        append(separator)
                    }
                    append(formatDuration(mix.durationMs))
                    append(separator)
                    append(formatSize(mix.sizeBytes))
                    val date = formatDate(mix.modifiedAt)
                    if (date.isNotEmpty()) {
                        append(separator)
                        append(date)
                    }
                    // Last, not first: the name is what is being scanned for, and a folder prefixed
                    // to every line would push the useful part of each one rightwards.
                    if (folder != null) {
                        append(separator)
                        append(folder)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Hidden while picking, as the cover's play gesture is: one meaning per tap, and a row being
        // added to a batch is not the moment to be changing what it is.
        if (!selecting) {
            IconButton(onClick = onToggleFavourite, modifier = Modifier.size(StarSlot)) {
                Icon(
                    if (favourite) Icons.Default.Star else Icons.Outlined.StarOutline,
                    contentDescription = stringResource(
                        if (favourite) R.string.cd_favourite else R.string.cd_not_favourite,
                    ),
                    tint = if (favourite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        // Faint: an unmarked mix is the normal case, and a folder of them should not
                        // read as a column of grey stars down the side of the list.
                        MaterialTheme.colorScheme.onSurface.copy(alpha = UnmarkedTint)
                    },
                )
            }
            RowMenu(
                onRename = onRename,
                onDelete = onDelete,
                onShare = onShare,
                onMove = onMove,
                // Offered only where it would work. A menu item that opens onto "this app cannot
                // write this format" is a worse answer than an item that is not there.
                onEditTags = onEditTags.takeIf { mix.tagFormat != null },
            )
        }
    }
}

/**
 * The cover, doubling as the row's transport.
 *
 * While this mix is playing a scrim goes over the artwork with a pause glyph on it. The scrim is
 * what makes the glyph legible over an image nobody has seen — artwork is arbitrary, so a bare white
 * triangle would disappear over half the covers it landed on and a bare dark one over the other
 * half. It also does the second job the row needs: it visibly *dims* the playing mix's cover, which
 * is a mark that can be found while scrolling without reading anything.
 *
 * Nothing is drawn over a mix that is not playing. An overlaid triangle on every row would put a
 * control on top of thirty pictures to say what one tap already discovers.
 */
@Composable
private fun RowCover(mix: Mix, playing: Boolean, onPlay: () -> Unit) {
    // Read here rather than hoisted: a row is transient and recycled, and the loader is what holds
    // the decoded thumbnail across that — see [CoverLoader].
    val cover by rememberThumb(mix)
    Box(
        Modifier
            .size(LeadingSlot)
            .clickable(
                onClick = onPlay,
                onClickLabel = stringResource(if (playing) R.string.cd_pause else R.string.cd_play),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Cover(cover, LeadingSlot)
        if (playing) {
            Box(
                Modifier
                    .size(LeadingSlot)
                    .background(PlayingScrim, CoverShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Pause,
                    contentDescription = null,
                    Modifier.size(22.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Long-press starts picking, and once picking a tap picks too rather than opening — one meaning per
 * tap, so a batch can be built without a stray tap navigating out of the list halfway through.
 */
private fun Modifier.rowClicks(
    selecting: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
) = composed {
    val haptic = LocalHapticFeedback.current
    combinedClickable(
        onClick = { if (selecting) onToggleSelect() else onOpen() },
        onLongClick = {
            // Confirm the grab in the hand: the gesture has no on-screen affordance until it fires.
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggleSelect()
        },
    )
}

/**
 * A picked row, as a wash of the accent rather than a slab of it.
 *
 * A low-alpha tint reads as "picked" just as plainly as a filled row and leaves the row's own text
 * colours alone, which matters here because these rows carry three tiers of type. In a palette with
 * no hue to spare it matters more than it would elsewhere: a filled row would have to be a step of
 * the same grey ramp the row's own text is drawn from, and the text would go with it.
 */
private fun Modifier.selectionTint(selected: Boolean) = composed {
    if (selected) background(MaterialTheme.colorScheme.primary.copy(alpha = SelectedTint)) else this
}

@Composable
private fun SelectionTick(selected: Boolean) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = stringResource(
            if (selected) R.string.cd_selected else R.string.cd_not_selected,
        ),
        Modifier.size(LeadingSlot).padding(10.dp),
        tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        },
    )
}

/**
 * Rows are as tall as the cover in them: 44dp of artwork plus the padding, which comes to the same
 * 52dp the three lines of text beside it need. Neither one is padding the other out.
 */
/**
 * The lockup is wide — the disc with the wordmark beside it, near enough 3:1 — so height is the
 * cheap dimension: 64dp comes to 183dp across, half the width of a phone, and costs the list barely
 * more than one row. The drawable keeps its own aspect, so this is the only number the header needs.
 */
private val WordmarkHeight = 64.dp

/**
 * One turn of the disc, in milliseconds. A 12" at 33 rpm is 1.8 seconds a turn, which at this size
 * is a blur; slow enough to read as motion rather than as spin is the point, so this is a third of
 * that speed.
 */
private const val SpinMillis = 5400

private val RowPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val LeadingSlot = 44.dp

/** The cover has no inset of its own, unlike an icon in a 48dp button, so the gap is explicit. */
private val RowGap = 10.dp

/** Enough accent to mark the row, not enough to colour it — see [selectionTint]. */
private const val SelectedTint = 0.16f

/** The wash over a playing mix's cover — see [RowCover]. Dark, so a white glyph reads on it. */
private val PlayingScrim = Color(0x99000000)

/**
 * The star is narrower than the 48dp a menu takes. Three targets on one row is already most of its
 * width and the name is what gets squeezed — 40dp is still comfortably over the 32dp a thumb needs
 * when the target beside it is full size.
 */
private val StarSlot = 40.dp
private const val UnmarkedTint = 0.30f

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun RowMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: (() -> Unit)?,
    onMove: (() -> Unit)? = null,
    /** Absent on a folder, which has no tags, and on a format this app will not rewrite. */
    onEditTags: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The same order the player's own menu uses — the two are the same list of jobs and
            // should not have to be learned twice.
            if (onEditTags != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit_info)) },
                    leadingIcon = { Icon(Icons.Default.Sell, null) },
                    onClick = {
                        open = false
                        onEditTags()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            if (onMove != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move_to)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) },
                    onClick = {
                        open = false
                        onMove()
                    },
                )
            }
            if (onShare != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = {
                        open = false
                        onShare()
                    },
                )
            }
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

/** The two ways of looking at the same mixes: where they sit, and which ones were marked. */
enum class LibraryTab(@StringRes val title: Int) {
    ALL(R.string.library_tab_all),
    FAVOURITES(R.string.library_tab_favourites),
}
