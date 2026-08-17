// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.storage

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/**
 * Cover art on and off the system clipboard.
 *
 * **The system clipboard, not a buffer of our own**, and that is the whole decision here. A private
 * "held image" would have been a dozen lines and no manifest entry, and it would have made "Copy
 * image" mean something narrower than the words say: copy that only pastes back into the app it
 * came from is not copy. Going through the real clipboard means the same two actions also carry a
 * cover *out* to a messaging app and bring one *in* from a gallery, a browser or a file manager —
 * which is where covers actually come from — and it costs one provider that serves one file.
 *
 * It also means the paste side is not ours to define. Anything that puts an image on the clipboard
 * is a source, and [read] does not care which app it was.
 *
 * ## Why a provider is needed at all
 *
 * A clipboard entry is a uri, not bytes. A uri into this app's cache is unreadable to every other
 * process, so the image is written into a cache sub-directory that [FileProvider] is configured to
 * serve and the clip points there. The provider is not exported and grants nothing on its own: the
 * *clipboard* issues a read permission scoped to that one uri, to whichever app pastes it, and
 * revokes it when the clip is replaced.
 */
object ImageClipboard {

    /**
     * Put [bytes] on the clipboard as an image, labelled [label] for whatever preview the system
     * shows. Returns false if the file could not be written or the clipboard refused it.
     *
     * Always the same filename, so a second copy replaces the first rather than filling the cache
     * with every cover ever copied. The consequence is that the clipboard only ever holds the most
     * recent copy — which is what a clipboard is.
     */
    fun copy(context: Context, bytes: ByteArray, label: String): Boolean = runCatching {
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        val file = File(dir, COVER_FILE)
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(context, authority(context), file)
        // newUri asks the resolver what the file is rather than being told, so the clip carries a
        // true mime type — which is what a pasting app filters on.
        val clip = ClipData.newUri(context.contentResolver, label, uri)
        clipboard(context)?.setPrimaryClip(clip) ?: return false
        true
    }.getOrDefault(false)

    /**
     * Whether the clipboard currently holds something that looks like an image.
     *
     * Reads only the *description*, never the content. That is deliberate and not merely tidy:
     * from Android 10 an app that is not in the foreground cannot read clipboard content at all,
     * while the description stays available — so a check written this way keeps working wherever it
     * is called from, and the app never touches what is on the clipboard until the user asks it to.
     */
    fun hasImage(context: Context): Boolean =
        clipboard(context)?.primaryClipDescription?.hasMimeType(IMAGE_MIME) == true

    /**
     * The image on the clipboard, or null if there is none or it cannot be read.
     *
     * Only ever called from a user action in the foreground — see [hasImage] for why that matters.
     * Bounded: the clipboard can point at a full-resolution photograph from a camera roll, and this
     * is read whole before anything downsamples it, so an absurd source is refused rather than
     * allowed to exhaust memory.
     */
    fun read(context: Context): ByteArray? = runCatching {
        val clip = clipboard(context)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val uri = clip.getItemAt(0).uri ?: return null
        // The description can lie or be absent; what the resolver says about the uri is the answer.
        val type = context.contentResolver.getType(uri)
        if (type?.startsWith("image/") != true) return null
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun clipboard(context: Context): ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)

    /** Matches the authority declared in the manifest, which is built from the application id. */
    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    private const val SHARED_DIR = "shared"
    private const val COVER_FILE = "cover.jpg"
    private const val IMAGE_MIME = "image/*"

    /**
     * As large a source image as this will take. Well past any real piece of artwork and past a
     * phone camera's output too; what it stops is a clipboard pointing at something that is not
     * artwork at all.
     */
    private const val MAX_BYTES = 64L * 1024 * 1024
}
