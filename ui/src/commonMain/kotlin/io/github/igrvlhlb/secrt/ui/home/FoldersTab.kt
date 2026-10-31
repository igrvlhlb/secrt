package io.github.igrvlhlb.secrt.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.igrvlhlb.secrt.model.Folder
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.ui.components.FolderPickerDialog
import io.github.igrvlhlb.secrt.ui.components.TextInputDialog
import io.github.igrvlhlb.secrt.ui.components.descendantsOf
import io.github.igrvlhlb.secrt.ui.components.pathTo
import kotlinx.coroutines.launch

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: Folder) : FolderDialog
    data class Move(val folder: Folder) : FolderDialog
    data class Delete(val folder: Folder) : FolderDialog
}

@Composable
fun FoldersTab(store: DiaryStore, onOpenEntry: (String) -> Unit, onNewEntry: (folderId: String?) -> Unit) {
    val vm = viewModel { FoldersViewModel(store) }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val requestedId by vm.currentFolderId.collectAsStateWithLifecycle()
    // If the open folder disappeared (e.g. deleted), fall back to the root.
    val current = folders.firstOrNull { it.id == requestedId }
    val path = pathTo(current?.id, folders)
    val subfolders = folders.filter { it.parentId == current?.id }
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    fun run(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Something went wrong")
            }
        }
    }

    TabScaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.joinToString(" / ") { it.name }.ifEmpty { "Folders" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (current != null) {
                        IconButton(onClick = { vm.currentFolderId.value = current.parentId }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                    }
                },
                actions = { IconButton(onClick = { dialog = FolderDialog.Create }) { Icon(Icons.Outlined.CreateNewFolder, "New folder") } },
            )
        },
        fab = { NewEntryFab { onNewEntry(current?.id) } },
    ) { modifier ->
        EntryList(
            entries = entries,
            emptyText = "",
            onOpenEntry = onOpenEntry,
            modifier = modifier,
            header = {
                items(subfolders, key = { "folder-${it.id}" }) { folder ->
                    FolderRow(
                        folder,
                        onOpen = { vm.currentFolderId.value = folder.id },
                        onRename = { dialog = FolderDialog.Rename(folder) },
                        onMove = { dialog = FolderDialog.Move(folder) },
                        onDelete = { dialog = FolderDialog.Delete(folder) },
                    )
                }
                if (subfolders.isEmpty() && entries?.isEmpty() == true) {
                    item {
                        Text(
                            if (current == null) "Organize entries in folders. Create one with the button above." else "This folder is empty.",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
        )
    }

    when (val d = dialog) {
        null -> Unit
        FolderDialog.Create -> TextInputDialog("New folder", "", "Create", { name -> run { store.createFolder(name, current?.id) } }, { dialog = null })
        is FolderDialog.Rename -> TextInputDialog("Rename folder", d.folder.name, "Rename", { name -> run { store.renameFolder(d.folder.id, name) } }, { dialog = null })
        is FolderDialog.Move -> FolderPickerDialog(
            title = "Move \"${d.folder.name}\" to",
            folders = folders,
            selected = d.folder.parentId,
            excluded = descendantsOf(d.folder.id, folders) + d.folder.id,
            onPick = { target -> run { store.moveFolder(d.folder.id, target) } },
            onDismiss = { dialog = null },
        )
        is FolderDialog.Delete -> ConfirmDialog(
            title = "Delete \"${d.folder.name}\"?",
            text = "Its entries and subfolders will move to ${folders.firstOrNull { it.id == d.folder.parentId }?.name?.let { "\"$it\"" } ?: "the top level"}.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { run { store.deleteFolder(d.folder.id) } },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun FolderRow(folder: Folder, onOpen: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
            headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Folder options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Move") }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                    }
                }
            },
        )
    }
}
