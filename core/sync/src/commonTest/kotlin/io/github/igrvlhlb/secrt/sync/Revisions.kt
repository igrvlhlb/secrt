package io.github.igrvlhlb.secrt.sync

import io.github.igrvlhlb.secrt.model.EntryPayload
import io.github.igrvlhlb.secrt.model.Hlc
import io.github.igrvlhlb.secrt.model.RecordType
import io.github.igrvlhlb.secrt.model.Revision
import io.github.igrvlhlb.secrt.model.SecrtJson
import kotlinx.datetime.LocalDate

/** Small DSL to build revision histories in tests. */
internal class History(private val recordId: String = "entry-1") {
    val revisions = mutableListOf<Revision>()
    private val seqs = mutableMapOf<String, Long>()

    fun rev(device: String, millis: Long, parents: List<Revision>, payload: EntryPayload?): Revision {
        val seq = (seqs[device] ?: 0) + 1
        seqs[device] = seq
        return Revision(
            id = Revision.idOf(device, seq),
            recordType = RecordType.ENTRY,
            recordId = recordId,
            parents = parents.map { it.id },
            deviceId = device,
            seq = seq,
            hlc = Hlc(millis, 0, device),
            deleted = payload == null,
            payload = payload?.let { SecrtJson.encodeToString(it) },
        ).also { revisions += it }
    }

    fun resolve() = RecordResolver.resolve(
        revisions,
        { SecrtJson.decodeFromString<EntryPayload>(it) },
        EntryMerger,
    )
}

internal fun entry(
    title: String = "Title",
    body: String = "line 1\nline 2\nline 3",
    labels: Set<String> = emptySet(),
    folderId: String? = null,
) = EntryPayload(
    title = title,
    body = body,
    diaryDate = LocalDate(2026, 10, 30),
    folderId = folderId,
    labelIds = labels,
    createdAt = 1_000,
)
