// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import de.singular.player.ThemeMode

/**
 * The whole app is one colour scale: Tailwind's **slate**, top to bottom.
 *
 * This is the decision that shapes everything else here, and it is not the usual one. An app
 * normally has a neutral ramp for its surfaces and a *chromatic* accent laid over it — Title Track's
 * greys and its amber. Slate is a neutral, so choosing it as the accent means there is nothing left
 * to contrast against by hue: the accent and the page are the same family, and everything that
 * separates them is level.
 *
 * That is the point rather than a limitation. This app is a library of demo mixes, and what a demo
 * mix is *for* is being listened to and having its cover looked at. Album art is arbitrary colour —
 * whatever the mix was given — and a chromatic accent would be a second opinion beside it on every
 * screen where it appears. A monochrome shell has none, and the artwork is the only colour in the
 * app, which is what makes it read as the subject rather than as decoration.
 *
 * The practical consequence is that **hue is never doing any work here**, so contrast has to be
 * measured rather than eyeballed. Every step below carries its ratio. Nothing is left to the
 * assumption that a colour will stand out because it is a different colour, because none of them
 * are.
 *
 * Both schemes are stated in full. The tempting shortcut — take Material's default neutrals and
 * only set the accent — is what a chromatic accent allows and this cannot: Material's greys carry a
 * faint violet, which beside a blue-grey slate reads not as neutral but as *slightly the wrong
 * slate*, and the mismatch is most visible exactly where the two meet.
 *
 * Scale, for reference, since every value below is one of these:
 *
 * ```
 * 50  #F8FAFC   400 #94A3B8   800 #1E293B
 * 100 #F1F5F9   500 #64748B   900 #0F172A
 * 200 #E2E8F0   600 #475569   950 #020617
 * 300 #CBD5E1   700 #334155
 * ```
 */
private object Slate {
    val S50 = Color(0xFFF8FAFC)
    val S100 = Color(0xFFF1F5F9)
    val S200 = Color(0xFFE2E8F0)
    val S300 = Color(0xFFCBD5E1)
    val S400 = Color(0xFF94A3B8)
    val S500 = Color(0xFF64748B)
    val S600 = Color(0xFF475569)
    val S700 = Color(0xFF334155)
    val S800 = Color(0xFF1E293B)
    val S900 = Color(0xFF0F172A)
    val S950 = Color(0xFF020617)

    /**
     * Two steps that are not on the scale: the ramp needs a rung between 50 and white, and one
     * between 900 and 800, and Tailwind provides neither. Both are straight interpolations of their
     * neighbours in sRGB, which is close enough at this spacing that the ramp still reads as even —
     * these are a hundredth of a stop apart from the steps either side, not a colour of their own.
     */
    val LightNearWhite = Color(0xFFFCFDFE)
    val DarkMid = Color(0xFF16203A)
}

/**
 * The accent: **slate-400 on dark, slate-600 on light**.
 *
 * Both are read as text — the selected tab, a setting's value, the timecode under a waveform — so
 * both clear 4.5:1 comfortably: slate-400 measures **7.9:1** on the dark page, slate-600 **7.6:1**
 * on white. Nothing here is a compromise between a look and a number, which is worth saying because
 * the app this borrows from spent a long paragraph on exactly that trade for its amber. A neutral
 * accent has the whole scale available and can simply take a legible rung.
 *
 * They are four steps apart, and they have to be: the accent's job is to sit clearly *above* the
 * body text on dark and clearly *below* it on light, and the two pages are at opposite ends. What
 * makes it read as an accent rather than as more grey text is that it is the one step of the scale
 * used for nothing else — the surfaces stop at 200 and 800, the ink is 100 and 900, and 400/600 sit
 * in the gap between them.
 *
 * **There is no separate fill colour**, unlike Title Track's `brandFill`. That split exists there
 * because amber-600 is legible as type and muddy as a slab, so a button and a label could not share
 * a step. Neither slate step has that problem — slate-600 fills a button on white and takes a
 * slate-50 label at 7.6:1; slate-400 fills one on the dark page and takes slate-950 at 7.9:1 — so
 * `primary` is the accent everywhere, and there is no second gold to keep in agreement with the
 * first. If a fill ever does need to leave the accent, add the token here rather than at the call
 * site; the reason `brandFill` existed at all was that Material's defaults all point at `primary`
 * and a control that forgets to ask lands on the wrong one.
 */
private val AccentOnDark = Slate.S400
private val AccentOnLight = Slate.S600

/**
 * Content sitting *on* the accent, which the two themes answer with opposite ends of the scale
 * because their accents are four rungs apart.
 *
 * On slate-400 the scale's darkest step reads **7.9:1** and white only 2.6:1, so dark content is not
 * a preference but the only legible one. On slate-600 that inverts — slate-50 at **7.3:1**,
 * slate-950 at 2.9:1 — and the light theme takes the light label.
 *
 * Neither is pure black or pure white. The ends of the scale are a hair off both, which is what
 * keeps a filled button reading as part of this app rather than as a system control that wandered
 * in; and at these ratios the fraction of contrast it costs is not the fraction that matters.
 */
private val OnAccentDark = Slate.S950
private val OnAccentLight = Slate.S50

/**
 * The tinted container pair — Material's `primaryContainer`, which here is what the app bar becomes
 * while rows are being picked.
 *
 * A step of the ramp rather than the accent thinned. The accent at full strength across a full-width
 * bar is the one thing a monochrome palette cannot get away with: with no hue to distinguish it, a
 * slab of slate-400 across the top of a slate page reads as the page having gone wrong rather than
 * as a mode. slate-200 on light and slate-800 on dark are each about a quarter of a stop off their
 * page — plainly a tint, and unmistakably not the page — and carry their labels at 11.9:1 and
 * 14.5:1.
 */
private val ContainerOnLight = Slate.S200
private val OnContainerOnLight = Slate.S800
private val ContainerOnDark = Slate.S800
private val OnContainerOnDark = Slate.S200

/**
 * The ground a waveform is drawn on — the player's panel, and the rows that belong to it.
 *
 * **A tenth of a stop off the page, and no more.** The number is the rule; the rung is whichever one
 * hits it, and the two ramps do not agree on that because they are not the same length. Light's runs
 * from white down to slate-200 in four steps, so `surfaceContainerHigh` (slate-100) is where 1.10:1
 * lands. Dark's has the whole range from slate-950 upward, so the same ratio is at
 * `surfaceContainer` (slate-900) — 1.13:1.
 *
 * The floor is that the panel must stay *visible* as a panel, since the waveform is read as an area
 * with edges. A tenth of a stop is about where a large field still separates from its ground without
 * becoming an object on it; below that the edge starts to disappear on a phone at arm's length in
 * daylight. Above it — and this is the failure mode a monochrome app is prone to — the panel stops
 * bounding the waveform and starts competing with it, because a grey slab and a grey waveform have
 * only level to tell them apart.
 *
 * The waveform's own ink reads on this at 16:1 on light and 17:1 on dark, so the drawing is never
 * the thing in short supply here; the panel is.
 */
val ColorScheme.waveformPanel: Color get() =
    if (surface.luminance() < 0.5f) surfaceContainer else surfaceContainerHigh

/**
 * The dark scheme: **slate-950 page, slate-100 ink, and the ramp climbing back up the scale.**
 *
 * The page is the scale's own floor rather than a step above it, which is a deliberate choice about
 * where the room goes. A near-black page leaves the whole scale above it available for surfaces, and
 * this app needs that room — the library is rows on a page, the player is a panel on a page, and a
 * cover image is a bright rectangle on a page. Starting at slate-900 instead would compress all
 * three into the top half of the scale and leave the artwork with nowhere dark to sit against.
 *
 * slate-950 is very nearly black (its own luminance is 0.002) and reads as black beside a system
 * dialog, which is the trap the borrowed app fell into with a warm near-black and had to back out
 * of. A blue-grey this deep does not have that problem: at this level the tint is below the
 * threshold where a hue reads as a colour, so the page is black that happens to be *this* black, and
 * the slate becomes legible as a hue only further up the ramp where the surfaces are.
 */
private val ReelDarkColors = darkColorScheme(
    primary = AccentOnDark,
    onPrimary = OnAccentDark,
    primaryContainer = ContainerOnDark,
    onPrimaryContainer = OnContainerOnDark,
    secondary = AccentOnDark,
    onSecondary = OnAccentDark,
    // Material's own name for a filled selected surface — the drawer's picked item reads it. Set as
    // a token rather than at the call site, so the next Material component that quietly uses it
    // arrives in the right grey instead of an accent-coloured slab.
    secondaryContainer = ContainerOnDark,
    onSecondaryContainer = OnContainerOnDark,
    background = Slate.S950,
    onBackground = Slate.S100,
    surface = Slate.S950,
    onSurface = Slate.S100, // 18.4:1
    surfaceVariant = Slate.S800,
    onSurfaceVariant = Slate.S400, // 7.9:1 on the page
    surfaceContainerLowest = Slate.S950,
    surfaceContainerLow = Slate.S950,
    surfaceContainer = Slate.S900, // 1.13:1 — the waveform panel
    surfaceContainerHigh = Slate.DarkMid,
    surfaceContainerHighest = Slate.S800,
    outline = Slate.S600,
    outlineVariant = Slate.S700,
)

/**
 * The light scheme: **white page, slate-900 ink, and the ramp descending through the scale's top.**
 *
 * White rather than slate-50, and for the same reason the dark page is 950: the ends are where the
 * room is. A slate-50 page would put the first surface step at 100 and leave three rungs for
 * everything above it, in a palette that has only level to work with. It would also be a page that
 * is *faintly* blue — 1.05:1 off true white — which is the worst available amount: too faint to read
 * as a decision, strong enough to look unclean beside a white system UI. A hue that faint is either
 * invisible or it is a smudge.
 *
 * So white, and the slate arrives one step in. Body text at 17.9:1, secondary at 7.6:1.
 *
 * The ramp's own top, slate-200 at 1.23:1, is not the waveform panel — that is a rung up, at
 * slate-100. slate-200's job is `primaryContainer` and the odd component that reaches for the
 * highest step by itself; a quarter of a stop is right for a bar that means something and too much
 * for the largest area on the player screen.
 */
private val ReelLightColors = lightColorScheme(
    primary = AccentOnLight,
    onPrimary = OnAccentLight,
    primaryContainer = ContainerOnLight,
    onPrimaryContainer = OnContainerOnLight,
    secondary = AccentOnLight,
    onSecondary = OnAccentLight,
    secondaryContainer = ContainerOnLight,
    onSecondaryContainer = OnContainerOnLight,
    background = Color(0xFFFFFFFF),
    onBackground = Slate.S900,
    surface = Color(0xFFFFFFFF),
    onSurface = Slate.S900, // 17.9:1
    surfaceVariant = Slate.S200,
    onSurfaceVariant = Slate.S600, // 7.6:1 on the page
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Slate.LightNearWhite,
    surfaceContainer = Slate.S50,
    surfaceContainerHigh = Slate.S100, // 1.10:1 — the waveform panel
    surfaceContainerHighest = Slate.S200,
    outline = Slate.S400,
    outlineVariant = Slate.S300,
)

/** Controls use a gentle corner rather than the fully-rounded Material default, as in Title Track. */
val ControlShape = RoundedCornerShape(5.dp)

/**
 * The corner a cover image gets — larger than [ControlShape], because it is a picture rather than a
 * control and a 5dp radius on a 240dp square reads as a square with the corners chipped off.
 */
val CoverShape = RoundedCornerShape(10.dp)

/**
 * What the bottom of the screen is made of: the mini player's ground.
 *
 * Material's tonal elevation mixes `surfaceTint` — which is `primary` — over the page. In a
 * chromatic app that is a wash of the accent's hue and has to be argued for; here `primary` is
 * another slate, so 8% of it over a slate page is purely a *level* shift and the bar is simply a
 * lighter (or darker) step of the same scale. Which is exactly what a bar at the edge of a
 * monochrome screen should be, and it arrives without spending a ramp rung on it.
 *
 * One value, in one place, because the mini player is the only thing down there. It stays a constant
 * rather than a literal so that anything else that ever stacks against it — a tab bar, a now-playing
 * queue — lands on the same ground rather than a shade off it, which across the full width of a
 * phone shows up as a seam.
 */
val BottomBarElevation = 3.dp

/** Whether [mode] means dark right now — resolving SYSTEM against the OS setting. */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun DemoReelTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark(mode)) ReelDarkColors else ReelLightColors,
        content = content,
    )
}
