<img src="docs/icon.png" width="96" align="right" alt="">

# Demo Reel

An Android player and tag editor for your own demo mixes.

It reads **one folder** — the one you point it at — and nothing else. No `READ_MEDIA_AUDIO`, no
media-library access, no network, no analytics. The ringtones, the podcasts, the voice memos and
every track in every other app's download folder cannot appear here, because nothing but that one
folder is ever asked for.

Requires Android 8.0 (API 26) or newer.

---

## What it does

**Library.** The granted folder browsed as the tree it already is, sub-folders and all — so however
you organise it from a desktop is how it reads on the phone. Each row shows the mix's cover, its
tagged title (falling back to the filename), its artist, and what the file is. Long-press to start a
batch: move or delete several at once.

**Favourites.** A star on any mix, and a tab that gathers every starred mix from across the tree,
newest first, each row saying which folder it came from. Optionally they also float to the top of
their own folder.

**Player.** The cover, what the file says about itself, and a zoomable waveform. Tap to seek, drag
to scrub, pinch to zoom, and a strip along the bottom to scroll a long mix in one movement. The
transport is one button; hold it for repeat, double-tap it to start again from the top.

**Mini player.** The mix in hand, at the foot of every screen, so playback that outlives the screen
it was started from can still be stopped.

**Tag editor.** Title, artist, album, album artist, year, track, genre, comment and cover art,
written back into the file itself — **MP3** (ID3v2.4) and **FLAC** (Vorbis comment + `PICTURE`
block). The audio is copied byte for byte and never decoded, so re-tagging a lossy file costs no
generation of quality and a lossless one costs no encode. A trailing ID3v1 tag is removed on the way
past, since it can only contradict what was just written.

Every other format still plays, lists, favourites and moves; it just cannot be re-tagged, and the
menu item is absent rather than present and failing.

**Tag defaults.** An artist, an artwork and the rest, set once in Settings and stamped onto a mix, a
selection, or a whole untagged folder in one go. Applying asks one question — fill only what is
empty, or replace what is there — and an empty default is never written, so nothing is ever cleared.
There is no default title, because that is the one field that differs per file; *Title from filename*
stands in for it, and only ever fills a title that is missing.

This is the reason the app exists rather than being a player. A folder of bounces arrives with no
metadata at all, and tagging it one file at a time is what nobody does.

---

## Why a folder grant and not the media library

A player that asks for `READ_MEDIA_AUDIO` gets everything, and then spends its life filtering that
back down to the handful of files you meant. Every such filter is a guess, and the guess is wrong in
both directions — a demo mix that looks like a podcast gets hidden, a system notification sound that
lives in the wrong place gets shown.

The Storage Access Framework inverts it. You pick the folder once; the grant is persisted across
reboots and reinstalls; and the app's reach is exactly and permanently that subtree. There is no
filter to get wrong, and no permission dialog that asks for more than the app needs. See
`storage/MixStore.kt`.

The cost is that the grant is a *tree*, not a path — the app works in document ids rather than files,
and a provider that hands out opaque ids instead of path-shaped ones will lose a favourite when a
file is renamed from outside the app. That is a known limit, documented at `storage/Favourites.kt`.

## Why favourites live in the app and not in the files

The app can write tags, so marking a favourite *in* the file is technically available — and it is
still the wrong place. Every tag format in use puts its text block at the head of the file, so
setting one means rewriting the whole thing: tens of megabytes per tap, for a mark that says nothing
about what the mix is. A favourite has to toggle instantly under a thumb.

The trade is that a favourite is invisible from a desktop and does not survive a rename made outside
the app. Renames and moves made *inside* it are followed.

## Why there are two tag readers

`Tags.read` asks `MediaMetadataRetriever`. It handles every format the device knows, yields the
duration in the same pass, and is what fills a listing — thirty rows of title, artist and length.

`TagIo.read` parses the container itself, and is what the **editor** uses. The difference that forces
it: an editor must round-trip. Whatever it shows, it must be able to write back unchanged, and the
platform's reader is lossy in exactly the way that breaks — it has no notion of a comment, so a
comment already in a file would be invisible in the editor and gone after a save. A reader and a
writer that do not agree field for field is a data-loss bug waiting for someone to press Save.

So the listing reads fast and approximately; the editor reads exactly.

---

## Design

**The palette is Tailwind's slate, top to bottom** — surfaces, ink, and accent all from one neutral
scale. That is unusual: an app normally has neutral surfaces with a chromatic accent laid over them.

The reason is the artwork. A demo mix is a finished thing with a cover on it, and album art is
arbitrary colour — whatever the mix was given. A chromatic accent would be a second opinion beside
it on every screen where it appears. A monochrome shell has none, and the artwork ends up the only
colour in the app, which is what makes it read as the subject rather than as decoration.

The practical consequence is that **hue is never doing any work**, so nothing can be left to the
assumption that a colour will stand out because it is a different colour. Every step in
`ui/Theme.kt` carries its measured contrast ratio, and a few things are deliberately louder than
they would be in a chromatic app — the gap between the played and unplayed halves of a waveform, for
one, since the step in weight is the only difference available.

**Covers are decoded at the size they are drawn at.** Embedded artwork is commonly 600×600 or
larger; a folder of thirty mixes drawn as 44dp thumbnails would decode 40 MB of bitmaps to display
about a megabyte of pixels. `ui/CoverLoader.kt` samples down on the way in and keeps an LRU of the
results, keyed on the file's size and mtime so a re-tagged file re-reads by itself.

**Playback is checked by decoding, not by asking.** `MediaPlayer` will happily prepare and start a
file whose audio never reaches the speaker — ALAC on some Qualcomm devices does exactly this, with
the transport reporting itself playing the whole time. Silence that looks like playback is the worst
answer available, so a buffer is decoded before the press is honoured. See `audio/AudioDecoder.kt`,
which also explains why that one format is refused outright rather than tried.

**A re-tag never writes onto the original.** Tag blocks sit in front of the audio, so changing a
field moves every byte after it — there is no partial rewrite, only a whole one. The new file is
built in the cache directory and replaces the original once it is whole on disk, so a failed read, a
full volume or a killed process costs nothing. See `MixStore.writeTags`.

**User-facing text is never a string in Kotlin.** A Gradle task (`checkNoHardcodedUiStrings`) fails
the build on `Text("…")` or a literal `contentDescription`, because Android's own `HardcodedText`
lint only reads XML layouts and this app has none. Failures are named as enum constants and resolved
to words at the point they are shown, not at the point they happen — see `Message.kt`.

---

## Tests

The format code — `Id3`, `FlacTags`, `TagIo` — is pure Kotlin with no Android dependency, and is
covered by local JVM tests. That is deliberate: byte layout is either exactly right or it silently
produces a file that one player reads and another does not, and the failures are the kind nothing on
screen would show. The load-bearing case is that a rewritten file's audio is byte-for-byte identical
to what went in; the rest guard the mistakes that look fine and are not — a size field off by the
header's own length, a last-block marker left set in the middle of a FLAC's metadata, audio that
happens to end in the letters `TAG`.

```
./gradlew :app:test
```

## Building

```
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:check             # lint, unit tests, and the hardcoded-string guard
```

Needs a `local.properties` with `sdk.dir` pointing at an Android SDK.

## Licence

GPL-3.0-only. The icon is CC BY 4.0. The name is neither — see [COPYRIGHT](COPYRIGHT).
