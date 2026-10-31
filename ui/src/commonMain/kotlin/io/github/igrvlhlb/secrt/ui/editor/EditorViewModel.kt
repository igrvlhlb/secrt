package io.github.igrvlhlb.secrt.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.igrvlhlb.secrt.model.AttachmentRef
import io.github.igrvlhlb.secrt.model.ConflictKind
import io.github.igrvlhlb.secrt.model.Entry
import io.github.igrvlhlb.secrt.model.newId
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.EntryDraft
import io.github.igrvlhlb.secrt.ui.components.today
import io.github.igrvlhlb.secrt.ui.platform.PickedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.decodeToImageBitmap

data class EditorState(
    val title: String,
    val body: String,
    val diaryDate: LocalDate,
    val folderId: String?,
    val labelIds: Set<String>,
    val attachments: List<AttachmentRef>,
    val conflict: ConflictKind?,
) {
    val isBlank: Boolean get() = title.isBlank() && body.isBlank() && attachments.isEmpty()

    fun toDraft() = EntryDraft(title, body, diaryDate, folderId, labelIds, attachments)
}

/**
 * Holds the entry being edited and saves it automatically: shortly after the user stops typing,
 * when the app goes to the background, and when the editor closes.
 *
 * @param backgroundScope outlives this ViewModel, so the final save on close isn't cancelled.
 */
class EditorViewModel(
    private val store: DiaryStore,
    entryId: String?,
    initialFolderId: String?,
    private val backgroundScope: CoroutineScope,
) : ViewModel() {

    val id: String = entryId ?: newId()
    private var persisted = entryId != null
    private var dirty = false
    private var deleted = false
    private var pendingSave: Job? = null
    private val saveLock = Mutex()

    /** `null` while loading. */
    var state by mutableStateOf<EditorState?>(null)
        private set
    var notFound by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    /** Decoded image attachments, by blob id. */
    val images = mutableStateMapOf<String, ImageBitmap>()

    init {
        viewModelScope.launch {
            val entry = entryId?.let { store.getEntry(it) }
            if (entryId != null && entry == null) {
                notFound = true
                return@launch
            }
            state = entry?.toState() ?: EditorState("", "", today(), initialFolderId, emptySet(), emptyList(), null)
        }
    }

    fun update(transform: (EditorState) -> EditorState) {
        val current = state ?: return
        state = transform(current)
        dirty = true
        pendingSave?.cancel()
        pendingSave = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            save()
        }
    }

    /** Saves now even without changes, which also resolves a conflict in favor of what's shown. */
    fun keepThisVersion() {
        dirty = true
        viewModelScope.launch { save() }
    }

    /** Saves pending changes in a scope that survives the editor closing. */
    fun flush() {
        pendingSave?.cancel()
        backgroundScope.launch { save() }
    }

    fun addAttachment(file: PickedFile) {
        viewModelScope.launch {
            try {
                val ref = store.addAttachment(file.bytes, file.name, file.mimeType)
                update { it.copy(attachments = (it.attachments + ref).distinctBy { a -> a.blobId }) }
            } catch (e: Exception) {
                message = e.message ?: "Couldn't attach ${file.name}"
            }
        }
    }

    fun removeAttachment(blobId: String) = update { it.copy(attachments = it.attachments.filterNot { a -> a.blobId == blobId }) }

    fun loadImage(blobId: String) {
        if (blobId in images) return
        viewModelScope.launch {
            try {
                val bytes = store.readAttachment(blobId)
                images[blobId] = withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
            } catch (e: Exception) {
                message = "Couldn't open an attachment"
            }
        }
    }

    suspend fun delete() {
        deleted = true
        pendingSave?.cancel()
        saveLock.withLock { if (persisted) store.deleteEntry(id) }
    }

    override fun onCleared() = flush()

    private suspend fun save() = saveLock.withLock {
        val current = state ?: return@withLock
        if (!dirty || deleted) return@withLock
        // Don't create an entry the user opened and left empty.
        if (!persisted && current.isBlank) return@withLock
        dirty = false
        try {
            store.saveEntry(id, current.toDraft())
            persisted = true
            if (current.conflict != null && state === current) state = current.copy(conflict = null)
        } catch (e: Exception) {
            dirty = true
            message = e.message ?: "Couldn't save"
        }
    }

    private fun Entry.toState() = EditorState(title, body, diaryDate, folderId, labels.map { it.id }.toSet(), attachments, conflict)

    private companion object {
        const val AUTOSAVE_DELAY_MILLIS = 1_000L
    }
}
