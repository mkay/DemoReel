// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import android.content.ClipData
import android.os.Build
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.singular.player.BuildConfig
import de.singular.player.R
import kotlinx.coroutines.launch

const val REPO_URL = "https://github.com/mkay/DemoReel"
private const val ISSUES_URL = "$REPO_URL/issues"

/**
 * About: what the app is, where it lives, and how to report a bug.
 *
 * A tab inside Settings rather than an item in the drawer. It is the page you visit once out of
 * curiosity and once when filing an issue, and a tab is one tap from either half of the settings
 * while saying its own name from wherever you are.
 *
 * The honest cost is that two of these tabs set things and the third does not, so the row is not
 * three of a kind. The alternative was a row pinned at the bottom of one half, which puts a
 * destination inside a page with nothing about that page's name to say the app's version is in
 * there — and the bottom of the screen was not available, being the mini player.
 *
 * It brings neither a Scaffold nor a way back out: the app's own bar is above it and already says
 * Settings, and a tab is switched away from rather than left.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    // The clipboard is written from a coroutine: setting a clip suspends, since on some devices it
    // crosses to another process. The press itself is not made to wait on it.
    val scope = rememberCoroutineScope()
    // The versionCode rides along in the copied string: it is what pins a bug report to an exact
    // build when a version has been re-released under the same name.
    val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        // The icon whole, not composited here: ic_app_icon carries its own gradient disc, so it
        // needs no tile behind it and no clip around it — it is opaque where it is the icon and
        // transparent where the launcher's mask would have cut anyway.
        Image(
            painter = painterResource(R.drawable.ic_app_icon),
            contentDescription = null,
            modifier = Modifier.size(112.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        // Long-press to copy, mirroring the library's press-and-hold idiom. Android 13 and up pops
        // its own clipboard confirmation, so only older versions get a toast.
        val appName = stringResource(R.string.app_name)
        val copiedNotice = stringResource(R.string.about_version_copied)
        Text(
            stringResource(R.string.about_version, version),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(ControlShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val copied = "$appName $version"
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copied, copied)))
                        }
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            Toast.makeText(context, copiedNotice, Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Spacer(Modifier.height(28.dp))
        AboutSection(R.string.about_section_about) {
            AboutBody(R.string.about_body)
        }
        AboutSection(R.string.about_section_privacy) {
            AboutBody(R.string.about_privacy_body)
        }
        AboutSection(R.string.about_section_website) {
            AboutBody(R.string.about_website_body)
            AboutLink(REPO_URL) { uriHandler.openUri(REPO_URL) }
        }
        AboutSection(R.string.about_section_bugs) {
            AboutBody(R.string.about_bugs_body)
            AboutLink(ISSUES_URL) { uriHandler.openUri(ISSUES_URL) }
        }
        // GPL §5 requires a derivative to preserve legal notices, so a licence stated *in the app*
        // rather than only in the repo is worth more than it looks: a clone that stripped this
        // screen has done so deliberately, and the before-and-after is a screenshot.
        AboutSection(R.string.about_section_license) {
            AboutBody(R.string.about_copyright)
            AboutBody(R.string.about_license)
            AboutLink(REPO_URL) { uriHandler.openUri(REPO_URL) }
        }
    }
}

@Composable
private fun AboutSection(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun AboutBody(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
}


/** A tappable URL. Kept full-length rather than hidden behind link text so it stays readable. */
@Composable
private fun AboutLink(url: String, onClick: () -> Unit) {
    Text(
        url,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(ControlShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}
