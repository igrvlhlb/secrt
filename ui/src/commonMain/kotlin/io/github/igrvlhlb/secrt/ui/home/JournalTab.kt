package io.github.igrvlhlb.secrt.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.igrvlhlb.secrt.model.EntrySummary
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.ui.components.ColorDot
import io.github.igrvlhlb.secrt.ui.components.EntryCard
import io.github.igrvlhlb.secrt.ui.components.monthYear
import io.github.igrvlhlb.secrt.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun JournalTab(
    store: DiaryStore,
    session: AppSession,
    onOpenEntry: (String) -> Unit,
    onNewEntry: (folderId: String?) -> Unit,
) {
    val vm = viewModel { JournalViewModel(store) }
    val query by vm.query.collectAsStateWithLifecycle()
    val labelId by vm.labelId.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    val scope = rememberCoroutineScope()

    TabScaffold(
        topBar = {
            if (searching) {
                SearchBar(query, onQueryChange = { vm.query.value = it }, onClose = { searching = false; vm.query.value = "" })
            } else {
                TopAppBar(
                    title = { Text(stringResource(Res.string.tab_journal)) },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Outlined.Search, stringResource(Res.string.journal_search)) }
                        IconButton(onClick = { scope.launch { session.lock() } }) { Icon(Icons.Outlined.Lock, stringResource(Res.string.journal_lock)) }
                    },
                )
            }
        },
        fab = { NewEntryFab { onNewEntry(null) } },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            if (labels.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(labels, key = { it.id }) { label ->
                        FilterChip(
                            selected = label.id == labelId,
                            onClick = { vm.labelId.value = if (label.id == labelId) null else label.id },
                            label = { Text(label.name) },
                            leadingIcon = { ColorDot(label.color) },
                        )
                    }
                }
            }
            EntryList(
                entries = entries,
                emptyText = when {
                    query.isNotBlank() || labelId != null -> stringResource(Res.string.journal_no_match)
                    else -> stringResource(Res.string.journal_empty)
                },
                onOpenEntry = onOpenEntry,
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.search_close)) } },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(Res.string.search_placeholder)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Close, stringResource(Res.string.search_clear)) }
        },
    )
}

/** Entries grouped under month headers. `null` [entries] shows a loading indicator. */
@Composable
fun EntryList(
    entries: List<EntrySummary>?,
    emptyText: String,
    onOpenEntry: (String) -> Unit,
    modifier: Modifier = Modifier,
    header: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    when {
        entries == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        entries.isEmpty() && header == null -> Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(emptyText, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header?.invoke(this)
            // Grouped by month, named in the composition (in the user's language).
            entries.groupBy { it.diaryDate.year to it.diaryDate.month }.forEach { (month, monthEntries) ->
                item(key = "month-${month.first}-${month.second}") {
                    Text(monthEntries.first().diaryDate.monthYear(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(monthEntries, key = { it.id }) { entry -> EntryCard(entry, onClick = { onOpenEntry(entry.id) }) }
            }
        }
    }
}
