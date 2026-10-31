package io.github.igrvlhlb.secrt.storage

import io.github.igrvlhlb.secrt.model.AttachmentRef
import kotlinx.datetime.LocalDate

/** What the editor saves. */
data class EntryDraft(
    val title: String,
    val body: String,
    val diaryDate: LocalDate,
    val folderId: String?,
    val labelIds: Set<String>,
    val attachments: List<AttachmentRef>,
)

sealed interface FolderFilter {
    /** Entries from every folder. */
    data object Any : FolderFilter

    /** Entries directly in a folder; `null` id means the root. */
    data class In(val folderId: String?) : FolderFilter
}

data class EntryFilter(
    val folder: FolderFilter = FolderFilter.Any,
    val labelId: String? = null,
    val query: String? = null,
)

class AttachmentTooLargeException(val limitBytes: Long) :
    Exception("Attachments are limited to ${limitBytes / (1024 * 1024)} MB for now")

class StoreClosedException : IllegalStateException("The diary is locked")
