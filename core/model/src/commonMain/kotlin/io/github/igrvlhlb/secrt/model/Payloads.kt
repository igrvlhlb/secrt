package io.github.igrvlhlb.secrt.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EntryPayload(
    val title: String,
    val body: String,
    val diaryDate: LocalDate,
    val folderId: String? = null,
    val labelIds: Set<String> = emptySet(),
    val attachments: List<AttachmentRef> = emptyList(),
    /** Epoch millis of the entry's creation; kept across edits. */
    val createdAt: Long,
)

/** An encrypted, content-addressed file attached to an entry. */
@Serializable
data class AttachmentRef(
    val blobId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

@Serializable
data class FolderPayload(
    val name: String,
    val parentId: String? = null,
)

@Serializable
data class LabelPayload(
    val name: String,
    /** ARGB color. */
    val color: Long,
)

/** JSON settings shared by everything that persists or transfers payloads. */
val SecrtJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
