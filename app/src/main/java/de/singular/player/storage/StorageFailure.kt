// SPDX-License-Identifier: GPL-3.0-only

package de.singular.player.storage

import androidx.annotation.StringRes
import de.singular.player.R

/**
 * Why a storage operation could not be done, as something that can be said in the reader's language.
 *
 * The alternative is throwing `IllegalStateException("That file could not be read.")` and putting
 * `exception.message` on screen. That works exactly until the app speaks more than one language: the
 * sentence is fixed in English at the moment of failure, deep in a class that has no business
 * knowing what language anyone reads. Worse, it is decided *when the failure happens* rather than
 * when it is shown, so a message raised before a language change would still be sitting there in the
 * old one.
 *
 * So the store names the cause and the UI says it. The resource is resolved at the snackbar, in
 * whatever locale is in effect then — see [de.singular.player.Message].
 *
 * The enum constant's own name is what goes in the log (see [StorageException]): it is stable, it
 * does not move with a translation, and it is greppable back to this file.
 */
enum class StorageFailure(@StringRes val message: Int) {

    // Listing a folder
    NO_FOLDER_CHOSEN(R.string.error_no_folder_chosen),
    FOLDER_UNREACHABLE(R.string.error_folder_unreachable),
    FOLDER_UNREADABLE(R.string.error_folder_unreadable),

    // Reading
    CANNOT_READ_FILE(R.string.error_cannot_read_file),
    NO_DECODER(R.string.error_no_decoder),

    // Copying and moving
    CANNOT_WRITE_INTO_FOLDER(R.string.error_cannot_write_into_folder),
    COPY_NOT_WRITTEN(R.string.error_copy_not_written),
    ORIGINAL_NOT_REMOVED(R.string.error_original_not_removed),
    FOLDERS_CANNOT_MOVE(R.string.error_folders_cannot_move),

    // Tagging
    TAGS_UNSUPPORTED_FORMAT(R.string.error_tags_unsupported_format),
    TAGS_FILE_MALFORMED(R.string.error_tags_file_malformed),
    TAGS_NOT_WRITTEN(R.string.error_tags_not_written),
    CANNOT_WRITE_FILE(R.string.error_cannot_write_file),
}

/**
 * A storage operation that failed for a nameable reason.
 *
 * Still an [IllegalStateException] so nothing that catches broadly has to change, and its `message`
 * is the constant's name — which is what a stack trace wants. The words a person reads come from
 * [StorageFailure.message], never from here.
 */
class StorageException(val failure: StorageFailure) : IllegalStateException(failure.name)
