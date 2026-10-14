// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.singular.player.media.VOICE_TAG
import de.singular.player.storage.Mix
import de.singular.player.ui.ApplyModeDialog
import de.singular.player.ui.ControlShape
import de.singular.player.ui.CoverLoader
import de.singular.player.ui.DemoReelTheme
import de.singular.player.ui.DiscardChangesDialog
import de.singular.player.ui.LibraryScreen
import de.singular.player.ui.LibraryTab
import de.singular.player.ui.LocalCoverLoader
import de.singular.player.ui.MiniPlayer
import de.singular.player.ui.MoveDialog
import de.singular.player.ui.NameDialog
import de.singular.player.ui.PlayerOverflowAction
import de.singular.player.ui.PlayerScreen
import de.singular.player.ui.SettingsScreen
import de.singular.player.ui.SettingsTab
import de.singular.player.ui.TagEditorScreen
import de.singular.player.ui.isDark
import de.singular.player.ui.rememberDecoded
import kotlinx.coroutines.launch

private enum class Screen(@StringRes val title: Int) {
    LIBRARY(R.string.screen_library),
    SETTINGS(R.string.screen_settings),

    /** One mix, opened from the library. The bar names the folder it came from, not this. */
    PLAYER(R.string.screen_player),

    /**
     * The tag editor. A screen rather than a dialog: it is a form of eight fields and a picture,
     * which is more than a dialog should hold, and it is reached from three places that all want to
     * be returned to afterwards.
     */
    EDITOR(R.string.screen_editor),
}

/**
 * An [AppCompatActivity] for one reason: the language row in Settings applies its choice through
 * `AppCompatDelegate.setApplicationLocales`, and below Android 13 the storing and re-applying of
 * that choice hangs off this base class. Nothing else about AppCompat is used — the UI is Compose
 * from [setContent] down, and the XML theme draws nothing.
 */
/**
 * What was said to the assistant, if this intent is "play … on Demo Reel": the words, and the extras
 * the assistant may have split them into. Null for any other intent.
 */
private fun spokenRequest(intent: Intent): Pair<String, Bundle?>? =
    if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
        intent.getStringExtra(SearchManager.QUERY).orEmpty() to intent.extras
    } else {
        null
    }

/** The audio a share or an "open with" carries, or null if this intent is neither. */
private fun audioHandedOver(intent: Intent): Uri? = when (intent.action) {
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    Intent.ACTION_VIEW -> intent.data
    else -> null
}

class MainActivity : AppCompatActivity() {

    /**
     * A file another app handed over, waiting to be picked up by the composition.
     *
     * Held as state rather than read straight out of `getIntent()` inside the composable, because
     * the same intent would be read again on every recomposition and on rotation — and re-opening
     * a mix the user had since navigated away from is a hard thing to get out of.
     */
    private var handedOver by mutableStateOf<Uri?>(null)

    /** Held by the Activity rather than fetched in the composition, so intents can reach it too. */
    private val vm: PlayerViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handedOver = audioHandedOver(intent)
        // Only on a fresh start: a recreate — a language change, say — hands the same intent back,
        // and playing the request a second time would restart whatever it had started.
        if (savedInstanceState == null) spokenRequest(intent)?.let { (query, extras) ->
            Log.d(VOICE_TAG, "activity: \"$query\" on a fresh start")
            vm.playFromSearch(query, extras)
        }
        enableEdgeToEdge()
        setContent {
            val view = LocalView.current
            val settings by vm.settings.collectAsStateWithLifecycle()

            // Keep the system bar icons legible against whichever theme is in effect (the
            // enableEdgeToEdge default only tracks the OS setting, not our in-app override).
            val dark = isDark(settings.themeMode)
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            // One loader for the whole tree, tied to the ViewModel's store so its cache outlives
            // navigation between screens — see [CoverLoader].
            val coverLoader = remember(vm) { CoverLoader(vm.mixStore) }

            DemoReelTheme(settings.themeMode) {
                CompositionLocalProvider(LocalCoverLoader provides coverLoader) {
                    val library by vm.library.collectAsStateWithLifecycle()
                    val playback by vm.playback.collectAsStateWithLifecycle()
                    val openMix by vm.openMix.collectAsStateWithLifecycle()
                    val message by vm.message.collectAsStateWithLifecycle()
                    val looping by vm.looping.collectAsStateWithLifecycle()
                    val favourites by vm.favourites.collectAsStateWithLifecycle()
                    val favouriteMixes by vm.favouriteMixes.collectAsStateWithLifecycle()
                    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
                    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
                    val searchLoading by vm.searching.collectAsStateWithLifecycle()
                    val movePicker by vm.movePicker.collectAsStateWithLifecycle()
                    // The uri of the open mix when it came from another app — what turns on
                    // the offer to keep it. See [PlayerViewModel.handedOver].
                    val handedOverMix by vm.handedOver.collectAsStateWithLifecycle()
                    val recents by vm.recents.collectAsStateWithLifecycle()
                    val editing by vm.editing.collectAsStateWithLifecycle()
                    val defaults by vm.defaults.collectAsStateWithLifecycle()
                    val defaultCoverArt by vm.defaultCover.collectAsStateWithLifecycle()
                    val defaultCover = rememberDecoded(defaultCoverArt?.bytes)
                    val busy by vm.busy.collectAsStateWithLifecycle()

                    var screen by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
                    // Where leaving the editor goes back to. Same reasoning as [playerReturn]: it
                    // is reached from a library row, from a favourite, and from the player, and only
                    // the last of those wants the player back.
                    var editorReturn by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
                    // Where leaving the player goes back to. It is reached from a library row, from
                    // the mini player on any screen, and from the drawer's recents — and "back to
                    // the library" is only right for the first.
                    var playerReturn by rememberSaveable { mutableStateOf(Screen.LIBRARY) }
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    val snackbar = remember { SnackbarHostState() }

                    // The folder picker. Only the tree grant is asked for; everything below it is
                    // ours to browse without asking again, and nothing above it is ours at all.
                    val folderPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree(),
                    ) { uri -> uri?.let(vm::onFolderPicked) }

                    // Asking to post notifications, which is what a media notification needs on 13+.
                    //
                    // Asked on the first mix played rather than at launch: cold, before anything has
                    // been listened to, the prompt has nothing to point at and the honest answer is
                    // no. Once a mix is playing, the thing being asked for is the lock-screen
                    // controls for the mix that is playing, which is a question worth answering.
                    //
                    // Refusing it costs the notification and nothing else — playback and the service
                    // are unaffected, so the prompt is asked once and never pressed again.
                    val appContext = LocalContext.current.applicationContext
                    val notificationPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { }
                    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
                    val hasPlayed = playback.mix != null
                    LaunchedEffect(hasPlayed, notificationsAsked) {
                        if (!hasPlayed || notificationsAsked) return@LaunchedEffect
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
                        val granted = ContextCompat.checkSelfPermission(
                            appContext,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        notificationsAsked = true
                        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    // The photo picker, which grants a read on the one image chosen and needs no
                    // permission at all — the app never sees the gallery, only the picked file, and
                    // only long enough to re-encode it. Two launchers rather than one shared with a
                    // mode flag: a result that has to consult a variable to know where it belongs is
                    // the kind of thing that quietly puts a cover on the wrong thing after a
                    // process death.
                    val editorCoverPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickVisualMedia(),
                    ) { uri -> uri?.let(vm::setEditorCover) }
                    val defaultCoverPicker = rememberLauncherForActivityResult(
                        ActivityResultContracts.PickVisualMedia(),
                    ) { uri -> uri?.let(vm::setDefaultCover) }
                    val pickImage = PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                    )

                    // A share or an "open with" arrives outside the navigation entirely, so it is
                    // taken here and handed to the ViewModel, which resolves the uri and asks for
                    // the player through showPlayer below.
                    val incoming = handedOver
                    LaunchedEffect(incoming) {
                        if (incoming != null) {
                            handedOver = null
                            vm.openHandedOver(incoming)
                        }
                    }

                    fun enterPlayer(mix: Mix) {
                        playerReturn = screen
                        vm.openMix(mix)
                        screen = Screen.PLAYER
                    }

                    val showPlayer by vm.showPlayer.collectAsStateWithLifecycle()
                    LaunchedEffect(showPlayer) {
                        if (showPlayer) {
                            playerReturn = Screen.LIBRARY
                            screen = Screen.PLAYER
                            vm.playerShown()
                        }
                    }

                    fun enterEditor(mix: Mix) {
                        editorReturn = screen
                        vm.openEditor(mix)
                        screen = Screen.EDITOR
                    }

                    fun leaveEditor() {
                        vm.closeEditor()
                        screen = editorReturn
                    }

                    // A save closes the editor from the ViewModel's side; the screen has to follow
                    // it back out rather than sit on an editor with nothing in it.
                    LaunchedEffect(editing, screen) {
                        if (screen == Screen.EDITOR && editing == null) screen = editorReturn
                    }

                    // Leaving with unsaved changes asks first. Only when there are changes: a
                    // confirmation on the way out of a form nobody touched is a dialog that teaches
                    // people to dismiss dialogs.
                    var confirmingDiscard by remember { mutableStateOf(false) }
                    if (confirmingDiscard) {
                        DiscardChangesDialog(
                            onDiscard = {
                                confirmingDiscard = false
                                leaveEditor()
                            },
                            onDismiss = { confirmingDiscard = false },
                        )
                    }

                    fun askLeaveEditor() {
                        if (editing?.dirty == true) confirmingDiscard = true else leaveEditor()
                    }

                    // Resolved here rather than where it was raised: the words are chosen in
                    // whatever language is in effect when the snackbar goes up — see [Message].
                    val resources = LocalContext.current.resources
                    LaunchedEffect(message) {
                        message?.let {
                            snackbar.showSnackbar(it.resolve(resources))
                            vm.clearMessage()
                        }
                    }

                    // Hold the display awake if the setting asks for it. Released on dispose, so it
                    // can never leak into whatever is opened next.
                    DisposableEffect(settings.keepScreenOn) {
                        view.keepScreenOn = settings.keepScreenOn
                        onDispose { view.keepScreenOn = false }
                    }

                    fun go(to: Screen) {
                        screen = to
                        scope.launch { drawerState.close() }
                    }

                    var settingsTab by rememberSaveable { mutableStateOf(SettingsTab.LIBRARY) }

                    // Without a folder there is nothing to show, so ask for one straight away — on
                    // the first run, and again if the folder we were given is later revoked or
                    // unmounted. Dismissing it only lasts for this launch: the app is still empty,
                    // and the library keeps offering the picker.
                    var folderPromptDismissed by remember { mutableStateOf(false) }
                    LaunchedEffect(library.rootMissing) {
                        if (!library.rootMissing) folderPromptDismissed = false
                    }
                    if (library.rootMissing && !folderPromptDismissed) {
                        ChooseFolderDialog(
                            hadFolder = library.rootWasSet,
                            onChoose = {
                                folderPromptDismissed = true
                                folderPicker.launch(null)
                            },
                            onDismiss = { folderPromptDismissed = true },
                        )
                    }

                    fun leavePlayer() {
                        vm.closeMix()
                        screen = playerReturn
                    }

                    // Which library rows are picked, by document URI. Held here rather than in the
                    // library screen because the app bar becomes the selection's own toolbar, and
                    // the bar is this far up.
                    var selection by remember { mutableStateOf(emptySet<String>()) }
                    val selecting = screen == Screen.LIBRARY && selection.isNotEmpty()
                    var deletingSelection by remember { mutableStateOf(false) }
                    var deletingOpenMix by remember { mutableStateOf(false) }
                    var renamingOpenMix by remember { mutableStateOf(false) }

                    // Stamping the defaults onto a whole selection — the reason the defaults exist
                    // at all, and the one action in the contextual bar that is not destructive.
                    var applyingToSelection by remember { mutableStateOf(false) }
                    if (applyingToSelection) {
                        ApplyModeDialog(
                            onPick = { mode ->
                                applyingToSelection = false
                                vm.applyDefaultsTo(selection.map(Uri::parse), mode)
                                selection = emptySet()
                            },
                            onDismiss = { applyingToSelection = false },
                        )
                    }

                    // Renaming from the menu, which is the app bar's business rather than the
                    // player's.
                    openMix?.takeIf { renamingOpenMix }?.let { open ->
                        NameDialog(
                            title = stringResource(R.string.rename_title),
                            initial = open.mix.baseName,
                            confirm = stringResource(R.string.action_rename),
                            onConfirm = {
                                renamingOpenMix = false
                                vm.renameOpenMix(it)
                            },
                            onDismiss = { renamingOpenMix = false },
                        )
                    }

                    // Deleting the mix you are listening to also has to get you out of the screen
                    // showing it, or the player is left holding a file that is no longer there.
                    openMix?.takeIf { deletingOpenMix }?.let { open ->
                        AlertDialog(
                            onDismissRequest = { deletingOpenMix = false },
                            title = { Text(stringResource(R.string.delete_one_title)) },
                            text = {
                                Text(stringResource(R.string.delete_one_message, open.mix.name))
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    deletingOpenMix = false
                                    val uri = open.mix.uri
                                    leavePlayer()
                                    vm.deleteDocument(uri)
                                }) { Text(stringResource(R.string.action_delete)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { deletingOpenMix = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }

                    var libraryTab by rememberSaveable { mutableStateOf(LibraryTab.ALL) }
                    val onFavouritesTab =
                        screen == Screen.LIBRARY && libraryTab == LibraryTab.FAVOURITES

                    // The library's search field, brought out by pulling the list down. The query
                    // itself lives in the view model, which walks the tree for it; what is filtered
                    // here is the favourites list, which is already in memory, tags and all, and
                    // narrows instantly.
                    var searchOpen by rememberSaveable { mutableStateOf(false) }
                    val searching = screen == Screen.LIBRARY && searchOpen
                    fun closeSearch() {
                        searchOpen = false
                        vm.setSearchQuery("")
                    }
                    val favouritesShown = remember(favouriteMixes, searchQuery) {
                        val query = MixQuery.of(searchQuery) ?: return@remember favouriteMixes
                        favouriteMixes?.filter { query.matches(it.key, it.mix.tags) }
                    }

                    // Deleting a batch is the one thing this mode exists for, and the one thing in
                    // the app that cannot be undone — so it says how many and what kind before it
                    // goes.
                    if (deletingSelection) {
                        val listing = library.listing
                        val folders = listing?.folders.orEmpty()
                            .count { it.uri.toString() in selection }
                        val mixes = selection.size - folders
                        AlertDialog(
                            onDismissRequest = { deletingSelection = false },
                            title = {
                                Text(
                                    pluralStringResource(
                                        R.plurals.delete_many_title,
                                        selection.size,
                                        selection.size,
                                    ),
                                )
                            },
                            // Three whole sentences rather than one assembled from fragments. The
                            // pieces read as English word order — noun phrase, comma, verb — and a
                            // translator handed "with everything inside" on its own has nothing to
                            // agree it with.
                            text = {
                                val mixPhrase =
                                    pluralStringResource(R.plurals.count_mixes, mixes, mixes)
                                val folderPhrase =
                                    pluralStringResource(R.plurals.count_folders, folders, folders)
                                Text(
                                    when {
                                        mixes > 0 && folders > 0 -> stringResource(
                                            R.string.delete_many_both, mixPhrase, folderPhrase,
                                        )

                                        folders > 0 -> stringResource(
                                            R.string.delete_many_folders, folderPhrase,
                                        )

                                        else -> stringResource(
                                            R.string.delete_many_mixes, mixPhrase,
                                        )
                                    },
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    deletingSelection = false
                                    selection.forEach { vm.deleteDocument(Uri.parse(it)) }
                                    selection = emptySet()
                                }) { Text(stringResource(R.string.action_delete)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { deletingSelection = false }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }

                    // Aiming a move: the tree, browsed for somewhere to put what is being moved.
                    // Held up here with the other dialogs because a move can be started from three
                    // places — a row, a batch, the player — and all three land in the same picker.
                    movePicker?.let { picker ->
                        MoveDialog(
                            picker = picker,
                            onOpenFolder = vm::openMoveFolder,
                            onUp = vm::moveGoUp,
                            onConfirm = {
                                vm.confirmPick()
                                // The rows are on their way out of this folder; the batch is over.
                                selection = emptySet()
                            },
                            onDismiss = vm::cancelMove,
                        )
                    }

                    // A selection belongs to the folder it was made in: leave the library, walk into
                    // a sub-folder, or have a row deleted underneath it, and it is over.
                    // Intersecting rather than clearing keeps it across a bare refresh of the same
                    // listing.
                    LaunchedEffect(screen, library.listing) {
                        val listing = library.listing
                        selection = when {
                            screen != Screen.LIBRARY || listing == null -> emptySet()
                            else -> {
                                val here = listing.folders.map { it.uri.toString() } +
                                    listing.mixes.map { it.uri.toString() }
                                selection.intersect(here.toSet())
                            }
                        }
                    }

                    // The whole back chain, in one place and with mutually exclusive guards — no two
                    // of these are ever enabled at once, so which one runs does not depend on the
                    // order the compositions happen to register them in.
                    //
                    // Drop the selection first: while picking, that is what the gesture is for, and
                    // walking up a folder mid-batch is never what was meant. Then out of the player,
                    // then the search, then off the favourites tab, then up the folder tree, then
                    // home.
                    BackHandler(enabled = selecting) { selection = emptySet() }
                    // Before the player's, because the editor can be open *over* the player and is
                    // the only handler here that may refuse to act — see [askLeaveEditor].
                    BackHandler(enabled = !selecting && screen == Screen.EDITOR) { askLeaveEditor() }
                    BackHandler(
                        enabled = !selecting && screen == Screen.PLAYER,
                    ) { leavePlayer() }
                    // Favourites is a tab, not a place: leaving it returns to the files beside it,
                    // the same way leaving a sub-folder goes up rather than out.
                    // An open search goes before either: the field is the thing most recently
                    // brought out, and the list under it is still the place you were.
                    BackHandler(enabled = !selecting && searching) { closeSearch() }
                    BackHandler(enabled = !selecting && !searching && onFavouritesTab) {
                        libraryTab = LibraryTab.ALL
                    }
                    BackHandler(
                        enabled = !selecting && !searching && !onFavouritesTab &&
                            screen == Screen.LIBRARY && library.canGoUp,
                    ) { vm.goUp() }
                    BackHandler(
                        enabled = !selecting && screen == Screen.SETTINGS,
                    ) { screen = Screen.LIBRARY }

                    // The one handler that is *not* mutually exclusive with the rest, and so the
                    // one whose position matters: registered last, it wins over every handler above
                    // whenever it is enabled. While a rewrite is in flight the app stays where it
                    // is — the file being replaced is what the screen behind is drawn from, and
                    // navigating out mid-write would leave a listing describing bytes that are in
                    // the middle of being replaced.
                    BackHandler(enabled = busy) { /* deliberately nothing */ }

                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            // Four fifths of the width: the strip of scrim left over on the right is
                            // what you tap to close it again.
                            ModalDrawerSheet(Modifier.fillMaxWidth(0.8f)) {
                                Column(Modifier.fillMaxSize()) {
                                    Text(
                                        stringResource(R.string.app_name),
                                        Modifier.padding(24.dp),
                                    )
                                    NavigationDrawerItem(
                                        label = { Text(stringResource(Screen.LIBRARY.title)) },
                                        icon = { Icon(Icons.Default.LibraryMusic, null) },
                                        selected = screen == Screen.LIBRARY ||
                                            screen == Screen.PLAYER,
                                        onClick = {
                                            if (screen == Screen.PLAYER) vm.closeMix()
                                            go(Screen.LIBRARY)
                                        },
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                    NavigationDrawerItem(
                                        label = { Text(stringResource(Screen.SETTINGS.title)) },
                                        icon = { Icon(Icons.Default.Settings, null) },
                                        selected = screen == Screen.SETTINGS,
                                        onClick = { go(Screen.SETTINGS) },
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                    // What was open lately. Drawn from stored keys rather than
                                    // resolved mixes, so the panel arrives full instead of filling
                                    // in — see [Recents].
                                    if (recents.isNotEmpty()) {
                                        HorizontalDivider(
                                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        )
                                        Text(
                                            // "Recent" alone invites the question the play control
                                            // raises: a mix played from the list is recent too, and
                                            // is deliberately not here. This says which door counts.
                                            stringResource(R.string.drawer_recently_opened),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                start = 28.dp, bottom = 4.dp,
                                            ),
                                        )
                                        recents.forEach { key ->
                                            NavigationDrawerItem(
                                                label = {
                                                    Text(
                                                        key.substringAfterLast('/')
                                                            .substringBeforeLast('.'),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                // A bare name in a list of destinations does not
                                                // read as a mix; the waveform glyph gives every row
                                                // in this panel one shared label edge as well.
                                                icon = {
                                                    Icon(
                                                        ImageVector.vectorResource(
                                                            R.drawable.ic_graphic_eq,
                                                        ),
                                                        contentDescription = null,
                                                    )
                                                },
                                                selected = false,
                                                onClick = {
                                                    scope.launch {
                                                        drawerState.close()
                                                        vm.openRecent(key) {
                                                            playerReturn = Screen.LIBRARY
                                                            screen = Screen.PLAYER
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.padding(horizontal = 12.dp),
                                            )
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        },
                    ) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    // While rows are picked the bar becomes the selection's own: a
                                    // count, a way out, and the two actions a batch is worth making.
                                    colors = if (selecting) {
                                        TopAppBarDefaults.topAppBarColors(
                                            containerColor =
                                                MaterialTheme.colorScheme.primaryContainer,
                                            titleContentColor =
                                                MaterialTheme.colorScheme.onPrimaryContainer,
                                            navigationIconContentColor =
                                                MaterialTheme.colorScheme.onPrimaryContainer,
                                            actionIconContentColor =
                                                MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    } else {
                                        TopAppBarDefaults.topAppBarColors()
                                    },
                                    title = {
                                        if (selecting) {
                                            Text(
                                                pluralStringResource(
                                                    R.plurals.selection_count,
                                                    selection.size,
                                                    selection.size,
                                                ),
                                                maxLines = 1,
                                            )
                                            return@TopAppBar
                                        }
                                        // The player names the mix itself, in the middle of the
                                        // screen; the bar names where the arrow goes instead of
                                        // saying the same thing twice — the folder the mix sits in,
                                        // or the library itself for one at the top level. The
                                        // editor is the exception: it has no title of its own on
                                        // screen (the title is a *field* there, and may be empty),
                                        // so the bar has to say what this screen is.
                                        //
                                        // A handed-over mix breaks that rule because the rule's
                                        // premise is gone: the file sits in no folder of ours, and
                                        // the bar would otherwise say "Library" of a file that is
                                        // nowhere near it. It says what the file *is* instead —
                                        // the name is already below in full size, and printing it
                                        // twice a finger's width apart says nothing the second
                                        // time, while the state is the thing not said anywhere
                                        // else in the chrome.
                                        val onLoan = openMix?.mix?.uri == handedOverMix &&
                                            handedOverMix != null
                                        Text(
                                            when {
                                                screen != Screen.PLAYER ->
                                                    stringResource(screen.title)

                                                onLoan ->
                                                    stringResource(R.string.player_title_unsaved)

                                                else -> library.current
                                                    ?.takeIf { library.canGoUp }
                                                    ?.name
                                                    ?: stringResource(Screen.LIBRARY.title)
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    navigationIcon = {
                                        if (selecting) {
                                            IconButton(onClick = { selection = emptySet() }) {
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = stringResource(
                                                        R.string.cd_cancel_selection,
                                                    ),
                                                )
                                            }
                                        } else if (screen == Screen.PLAYER ||
                                            screen == Screen.EDITOR
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    if (screen == Screen.EDITOR) askLeaveEditor()
                                                    else leavePlayer()
                                                },
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.ArrowBack,
                                                    contentDescription = stringResource(
                                                        R.string.cd_back,
                                                    ),
                                                )
                                            }
                                        } else {
                                            IconButton(
                                                onClick = { scope.launch { drawerState.open() } },
                                            ) {
                                                Icon(
                                                    Icons.Default.Menu,
                                                    contentDescription =
                                                        stringResource(R.string.cd_menu),
                                                )
                                            }
                                        }
                                    },
                                    actions = {
                                        if (selecting) {
                                            // First of the three, and the only one that adds
                                            // something rather than moving or removing it.
                                            if (!defaults.isEmpty) {
                                                IconButton(
                                                    onClick = { applyingToSelection = true },
                                                    enabled = !busy,
                                                ) {
                                                    Icon(
                                                        Icons.Default.Sell,
                                                        contentDescription = stringResource(
                                                            R.string.cd_apply_defaults,
                                                        ),
                                                    )
                                                }
                                            }
                                            IconButton(onClick = {
                                                vm.startMove(selection.map(Uri::parse))
                                            }) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.DriveFileMove,
                                                    contentDescription = stringResource(
                                                        R.string.cd_move_selected,
                                                    ),
                                                )
                                            }
                                            IconButton(onClick = { deletingSelection = true }) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = stringResource(
                                                        R.string.cd_delete_selected,
                                                    ),
                                                )
                                            }
                                            return@TopAppBar
                                        }
                                        // The star, left of the menu: a verdict on the mix you are
                                        // listening to, and the one thing here worth pressing while
                                        // it plays rather than after. As an icon it says whether the
                                        // mix is marked just by being on screen, which is what a
                                        // menu item cannot do without being opened.
                                        // Not for a mix on loan from another app: a mark is a path
                                        // under the granted folder, which such a file has none of,
                                        // so the star would be a control that does nothing —
                                        // [PlayerViewModel.toggleFavourite] drops it on the floor.
                                        val starrable = openMix?.takeIf {
                                            screen == Screen.PLAYER && it.mix.uri != handedOverMix
                                        }
                                        starrable?.let { open ->
                                            val marked =
                                                vm.favouriteKey(open.mix.uri) in favourites
                                            IconButton(
                                                onClick = { vm.toggleFavourite(open.mix.uri) },
                                            ) {
                                                Icon(
                                                    if (marked) {
                                                        Icons.Default.Star
                                                    } else {
                                                        Icons.Outlined.StarOutline
                                                    },
                                                    contentDescription = stringResource(
                                                        if (marked) R.string.cd_unfavourite
                                                        else R.string.cd_make_favourite,
                                                    ),
                                                    // Tinted when given, plain when not: a mark is
                                                    // the only thing in this bar that means anything
                                                    // by itself, and the accent is what makes it
                                                    // read as set rather than as another available
                                                    // action.
                                                    tint = if (marked) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        LocalContentColor.current
                                                    },
                                                )
                                            }
                                        }
                                        openMix?.takeIf { screen == Screen.PLAYER }?.let { open ->
                                            PlayerOverflowAction(
                                                onEditTags = { enterEditor(open.mix) },
                                                onRename = { renamingOpenMix = true },
                                                onShare = { share(open.mix) },
                                                onMove = { vm.startMove(listOf(open.mix.uri)) },
                                                onDelete = { deletingOpenMix = true },
                                                // Offered only while the player is on a file
                                                // another app handed in, which is the only mix
                                                // that is not in the library already.
                                                onAddToLibrary = open.mix.uri
                                                    .takeIf { it == handedOverMix }
                                                    ?.let { uri -> { vm.startImport(uri) } },
                                            )
                                        }
                                    },
                                )
                            },
                            bottomBar = {
                                // The only thing at the foot of the screen, and it appears only
                                // while something is loaded: a mix keeps playing when you wander
                                // off, and this is the only thing that can stop it. The player
                                // screen has its own transport, so it is spared this — and so is
                                // the editor, which has a Save button in the same place and must
                                // not have a transport creep under it.
                                if (screen != Screen.PLAYER && screen != Screen.EDITOR) {
                                    MiniPlayer(
                                        playback = playback,
                                        onToggle = vm::toggleCurrent,
                                        onSeek = vm::seekTo,
                                        onOpen = { playback.mix?.let { enterPlayer(it) } },
                                        onDismiss = vm::stopPlayback,
                                        unsaved = playback.uri != null &&
                                            playback.uri == handedOverMix,
                                    )
                                }
                            },
                            // No snackbarHost here: the Scaffold slot is pinned to the bottom, which
                            // is where the mini player lives — a message landing on top of the
                            // transport means waiting for it to clear to use it.
                        ) { padding ->
                            val content = Modifier.padding(padding)
                            Box(Modifier.fillMaxSize()) {
                                when (screen) {
                                    Screen.LIBRARY -> LibraryScreen(
                                        state = library,
                                        playback = playback,
                                        onOpenFolder = vm::openFolder,
                                        onBreadcrumb = vm::goTo,
                                        onCreateFolder = vm::createFolder,
                                        onChooseFolder = { folderPicker.launch(null) },
                                        onPlay = { vm.togglePlayback(it) },
                                        onOpen = { enterPlayer(it) },
                                        onRename = vm::renameDocument,
                                        onDelete = vm::deleteDocument,
                                        onShare = ::share,
                                        onEditTags = { enterEditor(it) },
                                        onMove = vm::startMove,
                                        favouriteKeys = favourites,
                                        favouriteKeyOf = vm::favouriteKey,
                                        onToggleFavourite = { vm.toggleFavourite(it.uri) },
                                        favourites = favouritesShown,
                                        onLoadFavourites = vm::loadFavourites,
                                        tab = libraryTab,
                                        onTabChange = { libraryTab = it },
                                        onPlayPause = vm::toggleCurrent,
                                        searchOpen = searchOpen,
                                        query = searchQuery,
                                        searchResults = searchResults,
                                        searchLoading = searchLoading,
                                        onOpenSearch = { searchOpen = true },
                                        onQueryChange = vm::setSearchQuery,
                                        onCloseSearch = ::closeSearch,
                                        selection = selection,
                                        onToggleSelect = { uri ->
                                            selection = if (uri in selection) {
                                                selection - uri
                                            } else {
                                                selection + uri
                                            }
                                        },
                                        modifier = content,
                                    )

                                    Screen.SETTINGS -> SettingsScreen(
                                        settings = settings,
                                        defaults = defaults,
                                        tab = settingsTab,
                                        onTabChange = { settingsTab = it },
                                        folderLabel = library.path.firstOrNull()?.name,
                                        onChooseFolder = { folderPicker.launch(null) },
                                        onSetFavouritesFirst = vm::setFavouritesFirst,
                                        onSetKeepScreenOn = vm::setKeepScreenOn,
                                        onSetThemeMode = vm::setThemeMode,
                                        onSetDefaults = vm::setDefaults,
                                        onPickDefaultCover = {
                                            defaultCoverPicker.launch(pickImage)
                                        },
                                        onRemoveDefaultCover = { vm.setDefaultCover(null) },
                                        onPasteDefaultCover = vm::pasteDefaultCover,
                                        defaultCover = defaultCover,
                                        modifier = content,
                                    )

                                    Screen.EDITOR -> editing?.let { edit ->
                                        TagEditorScreen(
                                            edit = edit,
                                            defaults = defaults,
                                            busy = busy,
                                            onEdit = vm::editTags,
                                            onPickCover = {
                                                editorCoverPicker.launch(pickImage)
                                            },
                                            onRemoveCover = { vm.setEditorCover(null) },
                                            onPasteCover = vm::pasteCoverInEditor,
                                            onApplyDefaults = vm::applyDefaultsInEditor,
                                            onSave = vm::saveEditor,
                                            modifier = content,
                                        )
                                    } ?: Spacer(Modifier.fillMaxSize())

                                    // A mix that vanished under us (deleted from another app) leaves
                                    // nothing to show, so fall back to the list it came from.
                                    Screen.PLAYER -> openMix?.let { open ->
                                        PlayerScreen(
                                            open = open,
                                            playback = playback,
                                            onPlayPause = vm::togglePlayback,
                                            onSeek = vm::seekTo,
                                            onRestart = vm::restartPlayback,
                                            looping = looping,
                                            onToggleLoop = vm::toggleLoop,
                                            onEditTags = { enterEditor(open.mix) },
                                            onCopyCover = { vm.copyCover(open.mix) },
                                            targets = vm.targets,
                                            busy = busy,
                                            onNormalize = vm::normalizeOpenMix,
                                            onConvert = vm::convertOpenMix,
                                            onSaveNote = vm::saveNote,
                                            // Non-null only for a handed-over file, which is what
                                            // dims the edits and puts the notice above them.
                                            onAddToLibrary = open.mix.uri
                                                .takeIf { it == handedOverMix }
                                                ?.let { uri -> { vm.startImport(uri) } },
                                            modifier = content,
                                        )
                                    } ?: LaunchedEffect(Unit) { screen = Screen.LIBRARY }
                                }

                                // Over the middle of the screen: a message is about what just
                                // happened, and the middle is the one place nothing is being
                                // pressed. Slightly transparent, so what it is reporting on still
                                // shows through and it reads as a note laid over the screen, not a
                                // new surface.
                                SnackbarHost(
                                    snackbar,
                                    Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                                ) { data ->
                                    Snackbar(
                                        // Only as wide as what it has to say. A full-width slab
                                        // across the middle of the screen is a dialog; this is a
                                        // remark.
                                        modifier = Modifier
                                            .wrapContentWidth()
                                            .border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant
                                                    .copy(alpha = 0.6f),
                                                ControlShape,
                                            ),
                                        shape = ControlShape,
                                        // The app's own surface rather than Material's inverse pair.
                                        // Inverting puts a near-white slab over a dark screen, which
                                        // is louder than any of these messages deserve — and in a
                                        // palette with one hue it would be the single brightest
                                        // thing in the app. This follows the theme, and the outline
                                        // is what separates it from the panel underneath now that
                                        // the two are close in tone.
                                        containerColor = MaterialTheme.colorScheme
                                            .surfaceContainerHigh.copy(alpha = 0.92f),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ) {
                                        Text(
                                            data.visuals.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Asks for the folder the library is read from — on the first launch, and again if that folder
     * stops being reachable.
     *
     * This is the app's one setup step and its whole privacy story: nothing on the device is visible
     * to Demo Reel until this grant exists, and only what is under it is visible afterwards. The
     * dialog says so, because "choose a folder" on its own reads as a chore rather than as the thing
     * that keeps the rest of the phone's audio out.
     */
    @Composable
    private fun ChooseFolderDialog(
        hadFolder: Boolean,
        onChoose: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            // A tap outside does not put this away. It is the one dialog in the app whose subject
            // is the app being unusable — there is nothing behind it to tap *at* — so a stray touch
            // beside it would only leave someone looking at an empty library wondering where the
            // question went. Back still works, because back is a decision rather than a slip.
            properties = DialogProperties(dismissOnClickOutside = false),
            title = {
                Text(
                    stringResource(
                        if (hadFolder) R.string.folder_missing_title
                        else R.string.folder_choose_title,
                    ),
                )
            },
            text = {
                Text(
                    if (hadFolder) {
                        stringResource(
                            R.string.folder_missing_body,
                            stringResource(R.string.app_name),
                        )
                    } else {
                        stringResource(R.string.folder_choose_body)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = onChoose) {
                    Text(stringResource(R.string.action_choose_folder))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }

    /** Hand a mix to whichever app the share sheet lands on, read-only and for this share only. */
    private fun share(mix: Mix) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = shareType(mix)
            putExtra(Intent.EXTRA_STREAM, mix.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_mix_title)))
    }

    /**
     * What to tell the share sheet a mix is.
     *
     * The type is what the system filters the chooser on, so naming the file's own format is what
     * puts an app that handles it in the list at all, and a receiver that trusts the label rather
     * than sniffing the container needs it to be true.
     *
     * The provider is asked first, but is inconsistent about audio and will call a file
     * `application/octet-stream` — so anything that isn't audio falls through to the extension, which
     * gets the same vote here that it gets in the library listing. Failing both, the audio wildcard
     * still reaches every audio app, which is better than asserting a concrete type the bytes may
     * not match.
     */
    private fun shareType(mix: Mix): String {
        contentResolver.getType(mix.uri)
            ?.takeIf { it.startsWith("audio/") }
            ?.let { return it }
        mix.tagFormat?.let { return it.mime }
        val extension = mix.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?.takeIf { it.startsWith("audio/") }
            ?: "audio/*"
    }

    /**
     * A second file handed over while the app is already open.
     *
     * `setIntent` matters: without it `getIntent()` would go on returning the one this task was
     * started with, and a later rotation would re-open the wrong mix.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        audioHandedOver(intent)?.let { handedOver = it }
        spokenRequest(intent)?.let { (query, extras) ->
            Log.d(VOICE_TAG, "activity: \"$query\" to the running app")
            vm.playFromSearch(query, extras)
        }
    }
}
