package io.github.igrvlhlb.secrt.storage

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.db.SqlDriver
import io.github.igrvlhlb.secrt.model.AttachmentRef
import io.github.igrvlhlb.secrt.model.ConflictKind
import io.github.igrvlhlb.secrt.model.Entry
import io.github.igrvlhlb.secrt.model.EntryPayload
import io.github.igrvlhlb.secrt.model.EntrySummary
import io.github.igrvlhlb.secrt.model.Folder
import io.github.igrvlhlb.secrt.model.FolderPayload
import io.github.igrvlhlb.secrt.model.Hlc
import io.github.igrvlhlb.secrt.model.HlcClock
import io.github.igrvlhlb.secrt.model.Label
import io.github.igrvlhlb.secrt.model.LabelPayload
import io.github.igrvlhlb.secrt.model.RecordType
import io.github.igrvlhlb.secrt.model.Revision
import io.github.igrvlhlb.secrt.model.SecrtJson
import io.github.igrvlhlb.secrt.model.VersionVector
import io.github.igrvlhlb.secrt.model.newId
import io.github.igrvlhlb.secrt.storage.db.SecrtDatabase
import io.github.igrvlhlb.secrt.sync.EntryMerger
import io.github.igrvlhlb.secrt.sync.FolderMerger
import io.github.igrvlhlb.secrt.sync.FolderNode
import io.github.igrvlhlb.secrt.sync.FolderTree
import io.github.igrvlhlb.secrt.sync.LabelMerger
import io.github.igrvlhlb.secrt.sync.RecordResolver
import io.github.igrvlhlb.secrt.sync.RevisionGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import io.github.igrvlhlb.secrt.storage.db.Entry as EntryRow
import io.github.igrvlhlb.secrt.storage.db.Revision as RevisionRow

/**
 * The diary's data: every change is appended as a [Revision], and the tables the UI reads are
 * re-derived from the revisions of the records that changed. Remote revisions (from sync,
 * backups, imports) go through [applyRemote] and are merged the same way.
 */
class DiaryStore private constructor(
    private val driver: SqlDriver,
    private val db: SecrtDatabase,
    private val blobs: BlobStore,
    val deviceId: String,
    private val clock: HlcClock,
    private val wallClock: () -> Long,
    private val dispatcher: CoroutineDispatcher,
) {
    private val writeLock = Mutex()
    private var closed = false

    // ---------------------------------------------------------------- reads

    fun observeEntries(filter: EntryFilter = EntryFilter()): Flow<List<EntrySummary>> {
        val folder = filter.folder
        val summaries = db.entryQueries.summaries(
            allFolders = if (folder is FolderFilter.Any) 1 else 0,
            folderKey = (folder as? FolderFilter.In)?.folderId.orEmpty(),
            labelId = filter.labelId,
            pattern = filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let { "%${it.escapeLike()}%" },
        ).asFlow().mapToList(dispatcher)
        val labels = db.entryQueries.allEntryLabels().asFlow().mapToList(dispatcher).map { rows ->
            rows.groupBy({ it.entry_id }, { Label(it.id, it.name, it.color) })
        }
        return combine(summaries, labels) { rows, labelsByEntry ->
            rows.map { row ->
                EntrySummary(
                    id = row.id,
                    title = row.title,
                    preview = row.preview,
                    diaryDate = LocalDate.parse(row.diary_date),
                    folderId = row.effective_folder_id,
                    labels = labelsByEntry[row.id].orEmpty(),
                    attachmentCount = row.attachment_count.toInt(),
                    updatedAt = row.updated_at,
                    hasConflict = row.conflict != null,
                )
            }
        }.ignoreAfterClose()
    }

    fun observeFolders(): Flow<List<Folder>> =
        db.folderQueries.live().asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { Folder(it.id, it.name, it.effective_parent_id) } }
            .ignoreAfterClose()

    fun observeLabels(): Flow<List<Label>> =
        db.labelQueries.live().asFlow().mapToList(dispatcher)
            .map { rows -> rows.map { Label(it.id, it.name, it.color) } }
            .ignoreAfterClose()

    suspend fun getEntry(id: String): Entry? = read {
        db.entryQueries.byId(id).executeAsOneOrNull()?.toEntry()
    }

    /** The concurrent versions behind an entry's conflict, oldest first. */
    suspend fun conflictVersions(id: String): List<EntryPayload> = read {
        RevisionGraph(loadRevisions(RecordType.ENTRY, id)).heads
            .mapNotNull { it.payload }
            .map { SecrtJson.decodeFromString<EntryPayload>(it) }
    }

    suspend fun readAttachment(blobId: String): ByteArray = read { blobs.get(blobId) }

    // ---------------------------------------------------------------- entries

    /** Saves an entry, creating it if [id] is new. Also resolves any conflict on it. */
    suspend fun saveEntry(id: String, draft: EntryDraft) = write {
        val createdAt = db.entryQueries.byId(id).executeAsOneOrNull()?.created_at ?: wallClock()
        val payload = EntryPayload(
            title = draft.title,
            body = draft.body,
            diaryDate = draft.diaryDate,
            folderId = draft.folderId,
            labelIds = draft.labelIds,
            attachments = draft.attachments,
            createdAt = createdAt,
        )
        commit(RecordType.ENTRY, id, SecrtJson.encodeToString(payload))
    }

    suspend fun deleteEntry(id: String) = write { commit(RecordType.ENTRY, id, null) }

    /** Encrypts and stores a file; reference the result from an entry's attachments. */
    suspend fun addAttachment(bytes: ByteArray, fileName: String, mimeType: String): AttachmentRef = write {
        if (bytes.size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(MAX_ATTACHMENT_BYTES)
        AttachmentRef(blobs.put(bytes), fileName, mimeType, bytes.size.toLong())
    }

    // ---------------------------------------------------------------- folders

    suspend fun createFolder(name: String, parentId: String?): String = write {
        newId().also { commit(RecordType.FOLDER, it, SecrtJson.encodeToString(FolderPayload(name, parentId))) }
    }

    suspend fun renameFolder(id: String, name: String) = write {
        val row = db.folderQueries.byId(id).executeAsOne()
        commit(RecordType.FOLDER, id, SecrtJson.encodeToString(FolderPayload(name, row.parent_id)))
    }

    /** @throws IllegalArgumentException when moving a folder into itself or a descendant. */
    suspend fun moveFolder(id: String, newParentId: String?) = write {
        val parents = db.folderQueries.live().executeAsList().associate { it.id to it.effective_parent_id }
        var current = newParentId
        while (current != null) {
            require(current != id) { "A folder can't be moved into itself" }
            current = parents[current]
        }
        val row = db.folderQueries.byId(id).executeAsOne()
        commit(RecordType.FOLDER, id, SecrtJson.encodeToString(FolderPayload(row.name, newParentId)))
    }

    /** Deletes a folder; its entries and subfolders move up to its parent. */
    suspend fun deleteFolder(id: String) = write { commit(RecordType.FOLDER, id, null) }

    // ---------------------------------------------------------------- labels

    suspend fun createLabel(name: String, color: Long): String = write {
        newId().also { commit(RecordType.LABEL, it, SecrtJson.encodeToString(LabelPayload(name, color))) }
    }

    suspend fun updateLabel(id: String, name: String, color: Long) = write {
        commit(RecordType.LABEL, id, SecrtJson.encodeToString(LabelPayload(name, color)))
    }

    suspend fun deleteLabel(id: String) = write { commit(RecordType.LABEL, id, null) }

    // ---------------------------------------------------------------- sync

    suspend fun versionVector(): VersionVector = read {
        db.revisionQueries.versionVector().executeAsList().associate { it.device_id to it.MAX }
    }

    /**
     * Revisions the holder of [known] is missing. Anything returned is considered shared: this
     * device's revisions in it will never be amended in place again.
     */
    suspend fun revisionsSince(known: VersionVector): List<Revision> = write {
        val revisions = db.revisionQueries.devices().executeAsList().flatMap { device ->
            db.revisionQueries.since(device, known[device] ?: 0).executeAsList().map { it.toRevision() }
        }
        revisions.filter { it.deviceId == deviceId }.maxOfOrNull { it.seq }?.let { seq ->
            if (seq > sharedSeq()) db.metaQueries.put(META_SHARED_SEQ, seq.toString())
        }
        revisions
    }

    /**
     * Merges revisions received from another replica. Already-known revisions are ignored.
     * @return how many revisions were new.
     */
    suspend fun applyRemote(revisions: Collection<Revision>): Int = write {
        db.transactionWithResult {
            val touched = LinkedHashSet<Pair<RecordType, String>>()
            var added = 0
            for (revision in revisions) {
                if (db.revisionQueries.exists(revision.id).executeAsOne() > 0) continue
                insertRevision(revision)
                clock.observe(revision.hlc)
                touched += revision.recordType to revision.recordId
                added++
            }
            // Folders first, so entries are placed against the updated tree.
            touched.sortedBy { it.first != RecordType.FOLDER }.forEach { (type, id) -> materialize(type, id) }
            added
        }
    }

    suspend fun close() = writeLock.withLock {
        if (!closed) {
            closed = true
            driver.close()
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Records a new version of a record (or a deletion when [payload] is null).
     *
     * The new revision's parents are all current heads, so saving also resolves conflicts.
     * Consecutive saves within a few minutes amend this device's latest revision instead of
     * adding one, as long as that revision never left the device; this keeps history to about
     * one revision per editing session.
     */
    private fun commit(type: RecordType, recordId: String, payload: String?) = db.transaction {
        val heads = RevisionGraph(loadRevisions(type, recordId)).heads
        val single = heads.singleOrNull()
        if (single != null && single.payload == payload && single.deleted == (payload == null)) return@transaction
        if (heads.isEmpty() && payload == null) return@transaction

        val now = clock.now()
        val amendable = single?.takeIf {
            payload != null && !it.deleted && it.deviceId == deviceId &&
                it.seq > sharedSeq() && now.millis - it.hlc.millis < AMEND_WINDOW_MILLIS
        }
        if (amendable != null) {
            db.revisionQueries.amend(now.encode(), payload, amendable.id)
        } else {
            val seq = (db.revisionQueries.maxSeq(deviceId).executeAsOne().MAX ?: 0) + 1
            insertRevision(
                Revision(
                    id = Revision.idOf(deviceId, seq),
                    recordType = type,
                    recordId = recordId,
                    parents = heads.map { it.id },
                    deviceId = deviceId,
                    seq = seq,
                    hlc = now,
                    deleted = payload == null,
                    payload = payload,
                ),
            )
        }
        materialize(type, recordId)
    }

    /** Re-derives the visible state of one record from its revisions. */
    private fun materialize(type: RecordType, id: String) {
        val revisions = loadRevisions(type, id)
        when (type) {
            RecordType.ENTRY -> materializeEntry(id, revisions)
            RecordType.FOLDER -> {
                val r = RecordResolver.resolve(revisions, { SecrtJson.decodeFromString<FolderPayload>(it) }, FolderMerger)
                val last = r.lastKnownValue
                db.folderQueries.upsert(id, last?.parentId, null, last?.name.orEmpty(), r.deleted.toLong(), r.latest.hlc.encode())
                refreshFolderTree()
            }
            RecordType.LABEL -> {
                val r = RecordResolver.resolve(revisions, { SecrtJson.decodeFromString<LabelPayload>(it) }, LabelMerger)
                val last = r.lastKnownValue
                db.labelQueries.upsert(id, last?.name.orEmpty(), last?.color ?: 0, r.deleted.toLong())
            }
        }
    }

    private fun materializeEntry(id: String, revisions: List<Revision>) {
        val r = RecordResolver.resolve(revisions, { SecrtJson.decodeFromString<EntryPayload>(it) }, EntryMerger)
        db.entryQueries.deleteLabels(id)
        db.entryQueries.deleteAttachments(id)
        val value = r.value
        if (value == null) {
            db.entryQueries.delete(id)
            return
        }
        db.entryQueries.upsert(
            id = id,
            folder_id = value.folderId,
            effective_folder_id = folderTree().locate(value.folderId),
            title = value.title,
            body = value.body,
            diary_date = value.diaryDate.toString(),
            created_at = value.createdAt,
            updated_at = r.latest.hlc.millis,
            conflict = r.conflict?.name,
        )
        value.labelIds.forEach { db.entryQueries.insertLabel(id, it) }
        value.attachments.forEachIndexed { i, a ->
            db.entryQueries.insertAttachment(id, i.toLong(), a.blobId, a.fileName, a.mimeType, a.size)
        }
    }

    /** Recomputes where every folder and entry is shown after a folder changed. */
    private fun refreshFolderTree() {
        val tree = folderTree()
        tree.parents.forEach { (folderId, parentId) -> db.folderQueries.setEffectiveParent(parentId, folderId) }
        db.entryQueries.distinctFolderIds().executeAsList().forEach { folderId ->
            db.entryQueries.setEffectiveFolder(tree.locate(folderId), folderId)
        }
    }

    private fun folderTree() = FolderTree(
        db.folderQueries.all().executeAsList().map { FolderNode(it.id, it.parent_id, it.deleted != 0L, Hlc.decode(it.hlc)) },
    )

    private fun loadRevisions(type: RecordType, id: String): List<Revision> {
        val parents = db.revisionQueries.parentsForRecord(type.name, id).executeAsList()
            .groupBy({ it.rev_id }, { it.parent_rev_id })
        return db.revisionQueries.forRecord(type.name, id).executeAsList().map { it.toRevision(parents[it.rev_id].orEmpty()) }
    }

    private fun insertRevision(r: Revision) {
        db.revisionQueries.insert(r.id, r.recordType.name, r.recordId, r.deviceId, r.seq, r.hlc.encode(), r.deleted.toLong(), r.payload)
        r.parents.forEach { db.revisionQueries.insertParent(r.id, it) }
    }

    private fun sharedSeq(): Long = db.metaQueries.get(META_SHARED_SEQ).executeAsOneOrNull()?.toLong() ?: 0

    private fun RevisionRow.toRevision(parents: List<String> = db.revisionQueries.parentsOf(rev_id).executeAsList()) = Revision(
        id = rev_id,
        recordType = RecordType.valueOf(record_type),
        recordId = record_id,
        parents = parents.sorted(),
        deviceId = device_id,
        seq = seq,
        hlc = Hlc.decode(hlc),
        deleted = deleted != 0L,
        payload = payload,
    )

    private fun EntryRow.toEntry() = Entry(
        id = id,
        title = title,
        body = body,
        diaryDate = LocalDate.parse(diary_date),
        folderId = effective_folder_id,
        labels = db.entryQueries.labelsOf(id).executeAsList().map { Label(it.id, it.name, it.color) },
        attachments = db.entryQueries.attachmentsOf(id).executeAsList().map { AttachmentRef(it.blob_id, it.file_name, it.mime_type, it.size) },
        createdAt = created_at,
        updatedAt = updated_at,
        conflict = conflict?.let(ConflictKind::valueOf),
    )

    private suspend fun <T> read(block: () -> T): T = withContext(dispatcher) {
        if (closed) throw StoreClosedException()
        block()
    }

    private suspend fun <T> write(block: () -> T): T = withContext(dispatcher) {
        writeLock.withLock {
            if (closed) throw StoreClosedException()
            block()
        }
    }

    /** Queries still running when the diary locks fail; nobody is listening by then. */
    private fun <T> Flow<T>.ignoreAfterClose(): Flow<T> = catch { if (!closed) throw it }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
        private const val AMEND_WINDOW_MILLIS = 5 * 60 * 1000L
        private const val META_DEVICE_ID = "device_id"
        private const val META_SHARED_SEQ = "shared_seq"

        /** Opens the store over an already-open encrypted [driver]. */
        fun open(
            driver: SqlDriver,
            blobs: BlobStore,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            wallClock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        ): DiaryStore {
            val db = SecrtDatabase(driver)
            val deviceId = db.metaQueries.get(META_DEVICE_ID).executeAsOneOrNull()
                ?: newId().also { db.metaQueries.put(META_DEVICE_ID, it) }
            val lastHlc = db.revisionQueries.maxHlc().executeAsOne().MAX?.let(Hlc::decode)
            return DiaryStore(driver, db, blobs, deviceId, HlcClock(deviceId, wallClock, lastHlc), wallClock, dispatcher)
        }
    }
}

private fun Boolean.toLong(): Long = if (this) 1 else 0

private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
