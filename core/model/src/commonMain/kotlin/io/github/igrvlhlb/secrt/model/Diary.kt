package io.github.igrvlhlb.secrt.model

import kotlinx.datetime.LocalDate

/** Why an entry needs the user's attention after merging versions from several devices. */
enum class ConflictKind {
    /** The body was edited on two devices at once and the edits overlap. */
    CONCURRENT_EDIT,

    /** The entry was deleted on one device and edited on another; the edit was kept. */
    EDITED_AFTER_DELETE,
}

data class Entry(
    val id: String,
    val title: String,
    val body: String,
    val diaryDate: LocalDate,
    val folderId: String?,
    val labels: List<Label>,
    val attachments: List<AttachmentRef>,
    val createdAt: Long,
    val updatedAt: Long,
    val conflict: ConflictKind?,
)

data class EntrySummary(
    val id: String,
    val title: String,
    val preview: String,
    val diaryDate: LocalDate,
    val folderId: String?,
    val labels: List<Label>,
    val attachmentCount: Int,
    val updatedAt: Long,
    val hasConflict: Boolean,
)

data class Folder(
    val id: String,
    val name: String,
    /** Parent after resolving deleted parents and move cycles; `null` for the root. */
    val parentId: String?,
)

data class Label(
    val id: String,
    val name: String,
    val color: Long,
)
