// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import de.singular.player.R
import de.singular.player.Settings
import de.singular.player.TagDefaults
import de.singular.player.ThemeMode
import java.util.Locale

/**
 * The settings, and the page that is not a setting at all.
 *
 * **Library** is the app's set-up: which folder it is pointed at, how it sorts, what the screen
 * does. **Defaults** is the tag fields to stamp onto untagged mixes — its own tab rather than a
 * section of Library because it is the one thing here that is *content* rather than configuration,
 * and because it is the half of the app people will come back to. **About** is what it says on the
 * tin, and is a tab for the reasons given on [AboutScreen].
 *
 * Defaults sits in the middle deliberately, next to the thing it acts on rather than at the end
 * beside About — the two settings tabs stay adjacent and the odd one out stays last.
 */
enum class SettingsTab(@StringRes val title: Int) {
    LIBRARY(R.string.settings_tab_library),
    DEFAULTS(R.string.settings_tab_defaults),
    ABOUT(R.string.settings_tab_about),
}

@Composable
fun SettingsScreen(
    settings: Settings,
    defaults: TagDefaults,
    tab: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    folderLabel: String?,
    onChooseFolder: () -> Unit,
    onSetFavouritesFirst: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetDefaults: (TagDefaults) -> Unit,
    onPickDefaultCover: () -> Unit,
    onRemoveDefaultCover: () -> Unit,
    onPasteDefaultCover: () -> Unit,
    defaultCover: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab.ordinal, containerColor = Color.Transparent) {
            SettingsTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry,
                    onClick = { onTabChange(entry) },
                    text = { Text(stringResource(entry.title)) },
                )
            }
        }
        // Each tab scrolls on its own rather than sharing one scroller. About brings its own layout
        // and its own scroll — it is a page, not a column of rows — and a shared scroll state would
        // carry its offset across a tab switch, landing you halfway down the other one.
        when (tab) {
            SettingsTab.LIBRARY -> SettingsPage {
                LibrarySettings(
                    settings = settings,
                    folderLabel = folderLabel,
                    onChooseFolder = onChooseFolder,
                    onSetFavouritesFirst = onSetFavouritesFirst,
                    onSetKeepScreenOn = onSetKeepScreenOn,
                    onSetThemeMode = onSetThemeMode,
                )
            }

            SettingsTab.DEFAULTS -> SettingsPage {
                DefaultsSettings(
                    defaults = defaults,
                    cover = defaultCover,
                    onSetDefaults = onSetDefaults,
                    onPickCover = onPickDefaultCover,
                    onRemoveCover = onRemoveDefaultCover,
                    onPasteCover = onPasteDefaultCover,
                )
            }

            SettingsTab.ABOUT -> AboutScreen()
        }
    }
}

/**
 * The tag defaults — see [TagDefaults] for what they are for.
 *
 * Every field writes through on each keystroke rather than waiting for a Save button. These are
 * preferences, not a document: there is nothing to commit and nothing to abandon, and a settings
 * page with a Save button is a page you can leave having silently lost what you typed.
 *
 * There is no Title field and there cannot be, so the switch that stands in for one sits where a
 * title field would — first, above the rest.
 */
@Composable
private fun DefaultsSettings(
    defaults: TagDefaults,
    cover: ImageBitmap?,
    onSetDefaults: (TagDefaults) -> Unit,
    onPickCover: () -> Unit,
    onRemoveCover: () -> Unit,
    onPasteCover: () -> Unit,
) {
    Text(
        stringResource(R.string.defaults_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(20.dp))
    Section(R.string.defaults_section_cover)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable(onClick = onPickCover)) { Cover(cover, DefaultCoverSize) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            OutlinedButton(onClick = onPickCover, shape = ControlShape) {
                Text(
                    stringResource(
                        if (defaults.hasCover) R.string.action_change_image
                        else R.string.action_choose_image,
                    ),
                )
            }
            // Labelled buttons here rather than the icons the editor lays over its artwork: this
            // cover is 96dp and shares a row with its own controls, where the editor's is 180dp and
            // is the thing being looked at. Same two actions, sized to the space they are in.
            //
            // Only while there is something to paste, as in the editor — and the same shared watcher
            // decides, so the two never disagree about whether the clipboard holds an image.
            if (rememberClipboardHasImage()) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onPasteCover, shape = ControlShape) {
                    Text(stringResource(R.string.action_paste_image))
                }
            }
            if (defaults.hasCover) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRemoveCover) {
                    Text(stringResource(R.string.action_remove_image))
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Section(R.string.defaults_section_fields)
    SwitchRow(
        title = R.string.setting_title_from_filename_title,
        detail = R.string.setting_title_from_filename_detail,
        checked = defaults.titleFromFilename,
        onCheckedChange = { onSetDefaults(defaults.copy(titleFromFilename = it)) },
    )
    Spacer(Modifier.height(12.dp))
    DefaultField(R.string.field_artist, defaults.artist) {
        onSetDefaults(defaults.copy(artist = it))
    }
    DefaultField(R.string.field_album_artist, defaults.albumArtist) {
        onSetDefaults(defaults.copy(albumArtist = it))
    }
    DefaultField(R.string.field_album, defaults.album) {
        onSetDefaults(defaults.copy(album = it))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        DefaultField(R.string.field_year, defaults.year, Modifier.weight(1f), numeric = true) {
            onSetDefaults(defaults.copy(year = it))
        }
        DefaultField(R.string.field_genre, defaults.genre, Modifier.weight(1f)) {
            onSetDefaults(defaults.copy(genre = it))
        }
    }
    DefaultField(
        R.string.field_comment,
        defaults.comment,
        capitalization = KeyboardCapitalization.Sentences,
    ) {
        onSetDefaults(defaults.copy(comment = it))
    }

    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(R.string.defaults_how_to_apply),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DefaultField(
    @StringRes label: Int,
    value: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    // As in the tag editor's fields: words for names, sentences for the comment, none for numbers.
    capitalization: KeyboardCapitalization =
        if (numeric) KeyboardCapitalization.None else KeyboardCapitalization.Words,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        shape = ControlShape,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            capitalization = capitalization,
        ),
        modifier = modifier.fillMaxWidth().padding(bottom = 10.dp),
    )
}

/** Big enough to tell one piece of artwork from another; not the subject of the page. */
private val DefaultCoverSize = 96.dp

/** The ground a settings page stands on: the whole tab, scrolling, at the page's margin. */
@Composable
private fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        content = content,
    )
}

@Composable
private fun LibrarySettings(
    settings: Settings,
    folderLabel: String?,
    onChooseFolder: () -> Unit,
    onSetFavouritesFirst: (Boolean) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
) {
    Section(R.string.settings_section_folder)
    Text(
        folderLabel ?: stringResource(R.string.settings_folder_none),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        stringResource(R.string.settings_folder_detail),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onChooseFolder, shape = ControlShape) {
        Text(
            stringResource(
                if (folderLabel == null) R.string.action_choose_folder
                else R.string.action_change_folder,
            ),
        )
    }

    Spacer(Modifier.height(24.dp))
    Section(R.string.settings_section_library)
    SwitchRow(
        title = R.string.setting_favourites_first_title,
        detail = R.string.setting_favourites_first_detail,
        checked = settings.favouritesFirst,
        onCheckedChange = onSetFavouritesFirst,
    )

    Spacer(Modifier.height(24.dp))
    Section(R.string.settings_section_display)
    SwitchRow(
        title = R.string.setting_keep_screen_on_title,
        detail = R.string.setting_keep_screen_on_detail,
        checked = settings.keepScreenOn,
        onCheckedChange = onSetKeepScreenOn,
    )

    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.setting_theme), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in ThemeMode.entries) {
            val label = stringResource(mode.label)
            if (settings.themeMode == mode) {
                // Filled rather than outlined, which is the whole of how "picked" is said here.
                // With no second hue available, a chip cannot be marked by colour alone against its
                // neighbours — it is marked by being solid where they are hollow.
                Button(onClick = {}, shape = ControlShape) { Text(label) }
            } else {
                OutlinedButton(onClick = { onSetThemeMode(mode) }, shape = ControlShape) {
                    Text(label)
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    LanguageSetting()
}

/** What each [ThemeMode] is called on screen. The enum's own name is an identifier, not a label. */
private val ThemeMode.label: Int
    @StringRes get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

/**
 * A setting that is on or off, with the sentence that says what off means.
 *
 * **The gap before the switch is explicit**, and has to be. The obvious spelling of this row is
 * `Arrangement.SpaceBetween` with a `weight(1f)` on the text — which reads as "text on the left,
 * switch on the right, space between them" and delivers no space at all: the weight consumes every
 * pixel the row has spare, so `SpaceBetween` is handed nothing to distribute and the two land flush
 * against each other. A short label gets away with it and a two-line explanation does not, which is
 * why it survived until a detail line was long enough to wrap under the switch.
 *
 * So the spacing is stated as a gap between the children, and the arrangement says only what it
 * actually does.
 */
@Composable
private fun SwitchRow(
    @StringRes title: Int,
    @StringRes detail: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(SwitchGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(detail),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * How far the explanation stays clear of the switch.
 *
 * Wider than the 8dp that would merely stop them touching: a switch is a wide, filled lozenge, and
 * text ending a hair from one reads as a line that has been clipped rather than one that has
 * wrapped. This is the page margin's own step, so the column of settings text has the same rhythm
 * down its right edge that it has down its left.
 */
private val SwitchGap = 20.dp

@Composable
private fun Section(@StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

/**
 * One offered language: its BCP-47 [tag], or null for "follow the device".
 *
 * [label] is deliberately the language's name *in that language* — Deutsch, not German. Someone who
 * has landed in a language they can't read needs to find their way out, and the only word on the
 * screen they are sure to recognise is their own language's name for itself.
 */
private data class LanguageChoice(val tag: String?, val label: String)

/**
 * The languages on offer: the device default, then everything listed in `supported_locales`, sorted
 * by how each names itself.
 */
@Composable
private fun rememberLanguageChoices(): List<LanguageChoice> {
    val systemLabel = stringResource(R.string.language_system)
    val tags = stringArrayResource(R.array.supported_locales)
    return remember(systemLabel, tags) {
        val offered = tags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            // Ask the locale to name itself, then fix the case: several languages write their own
            // name lowercase mid-sentence (français, español) but expect a capital when it stands
            // alone as a label.
            val own = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
            LanguageChoice(tag, own)
        }.sortedBy { it.label }
        listOf(LanguageChoice(null, systemLabel)) + offered
    }
}

/** The current app language as a BCP-47 tag, or null when it is following the device. */
private fun currentLanguageTag(): String? =
    AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotEmpty() }
        ?.substringBefore(',')

/**
 * The language row and its picker.
 *
 * Applying a choice goes through [AppCompatDelegate], not a preference of our own: on Android 13+
 * that writes through to the system's per-app language, so this picker and the one in Android's own
 * Settings agree with each other instead of quietly disagreeing. Selecting recreates the activity,
 * which is what re-reads the resources — so there is nothing to do afterwards.
 */
@Composable
private fun LanguageSetting() {
    val choices = rememberLanguageChoices()
    // Read once per composition rather than held in state: the activity is recreated on change, so
    // this is re-read with the new value on the way back up.
    val currentTag = currentLanguageTag()
    val current = choices.firstOrNull { it.tag == currentTag } ?: choices.first()
    var picking by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .clickable { picking = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Language, contentDescription = null, Modifier.padding(end = 16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.setting_language),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                current.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.setting_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    choices.forEach { choice ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(ControlShape)
                                .clickable {
                                    picking = false
                                    AppCompatDelegate.setApplicationLocales(
                                        if (choice.tag == null) {
                                            LocaleListCompat.getEmptyLocaleList()
                                        } else {
                                            LocaleListCompat.forLanguageTags(choice.tag)
                                        },
                                    )
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = choice.tag == current.tag,
                                onClick = null, // the whole row is the target
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
