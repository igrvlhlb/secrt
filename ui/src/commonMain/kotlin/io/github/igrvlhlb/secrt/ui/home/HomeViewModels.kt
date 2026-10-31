package io.github.igrvlhlb.secrt.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.igrvlhlb.secrt.model.EntrySummary
import io.github.igrvlhlb.secrt.model.Folder
import io.github.igrvlhlb.secrt.model.Label
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.EntryFilter
import io.github.igrvlhlb.secrt.storage.FolderFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

private val WhileVisible = SharingStarted.WhileSubscribed(5_000)

// Screen state lives in ViewModels rather than rememberSaveable on purpose: saved instance
// state can be written to disk by the OS, and search terms or folder names are diary content.

class JournalViewModel(store: DiaryStore) : ViewModel() {
    val query = MutableStateFlow("")
    val labelId = MutableStateFlow<String?>(null)

    val labels: StateFlow<List<Label>> = store.observeLabels().stateIn(viewModelScope, WhileVisible, emptyList())

    /** `null` while loading. */
    val entries: StateFlow<List<EntrySummary>?> =
        combine(query.debounce(200), labelId) { q, label -> EntryFilter(labelId = label, query = q) }
            .flatMapLatest { store.observeEntries(it) }
            .stateIn(viewModelScope, WhileVisible, null)
}

class FoldersViewModel(store: DiaryStore) : ViewModel() {
    val currentFolderId = MutableStateFlow<String?>(null)

    val folders: StateFlow<List<Folder>> = store.observeFolders().stateIn(viewModelScope, WhileVisible, emptyList())

    val entries: StateFlow<List<EntrySummary>?> = currentFolderId
        .flatMapLatest { store.observeEntries(EntryFilter(folder = FolderFilter.In(it))) }
        .stateIn(viewModelScope, WhileVisible, null)
}
