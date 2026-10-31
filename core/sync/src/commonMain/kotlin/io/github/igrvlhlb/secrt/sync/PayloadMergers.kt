package io.github.igrvlhlb.secrt.sync

import io.github.igrvlhlb.secrt.model.AttachmentRef
import io.github.igrvlhlb.secrt.model.EntryPayload
import io.github.igrvlhlb.secrt.model.FolderPayload
import io.github.igrvlhlb.secrt.model.LabelPayload

object EntryMerger : PayloadMerger<EntryPayload> {
    override fun merge(base: EntryPayload?, older: EntryPayload, newer: EntryPayload): Merged<EntryPayload> {
        val body = when {
            older.body == newer.body -> older.body
            base == null -> null
            else -> TextMerge.merge(base.body, older.body, newer.body)
        }
        val value = EntryPayload(
            title = field(base, older, newer) { it.title },
            body = body ?: newer.body,
            diaryDate = field(base, older, newer) { it.diaryDate },
            folderId = field(base, older, newer) { it.folderId },
            labelIds = mergeSets(base?.labelIds, older.labelIds, newer.labelIds),
            attachments = mergeAttachments(base?.attachments, older.attachments, newer.attachments),
            createdAt = minOf(older.createdAt, newer.createdAt),
        )
        return Merged(value, clean = body != null)
    }

    private fun mergeAttachments(base: List<AttachmentRef>?, older: List<AttachmentRef>, newer: List<AttachmentRef>): List<AttachmentRef> {
        val keep = mergeSets(base?.mapTo(HashSet()) { it.blobId }, older.mapTo(HashSet()) { it.blobId }, newer.mapTo(HashSet()) { it.blobId })
        return (older + newer).distinctBy { it.blobId }.filter { it.blobId in keep }
    }
}

object FolderMerger : PayloadMerger<FolderPayload> {
    override fun merge(base: FolderPayload?, older: FolderPayload, newer: FolderPayload) = Merged(
        FolderPayload(
            name = field(base, older, newer) { it.name },
            parentId = field(base, older, newer) { it.parentId },
        ),
    )
}

object LabelMerger : PayloadMerger<LabelPayload> {
    override fun merge(base: LabelPayload?, older: LabelPayload, newer: LabelPayload) = Merged(
        LabelPayload(
            name = field(base, older, newer) { it.name },
            color = field(base, older, newer) { it.color },
        ),
    )
}

/** Field-level three-way merge: take whichever side changed it; if both did, the newer one. */
internal inline fun <P : Any, T> field(base: P?, older: P, newer: P, get: (P) -> T): T {
    val o = get(older)
    val n = get(newer)
    return when {
        o == n -> o
        base != null && o == get(base) -> n
        base != null && n == get(base) -> o
        else -> n
    }
}

/** Three-way set merge: additions from either side are kept, removals from either side apply. */
internal fun <T> mergeSets(base: Set<T>?, older: Set<T>, newer: Set<T>): Set<T> {
    if (base == null) return older + newer
    return (older + newer + base).filterTo(LinkedHashSet()) { x ->
        if (x in base) x in older && x in newer else x in older || x in newer
    }
}
