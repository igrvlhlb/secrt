package io.github.igrvlhlb.secrt.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NewLabel
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.igrvlhlb.secrt.model.Label
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.ui.components.ColorDot
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.ui.components.LabelEditDialog
import io.github.igrvlhlb.secrt.ui.components.LabelPalette
import kotlinx.coroutines.launch

@Composable
fun LabelsTab(store: DiaryStore) {
    val labels by remember(store) { store.observeLabels() }.collectAsStateWithLifecycle(emptyList())
    var editing by remember { mutableStateOf<Label?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Label?>(null) }
    val scope = rememberCoroutineScope()

    TabScaffold(
        topBar = { TopAppBar(title = { Text("Labels") }) },
        fab = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Outlined.NewLabel, "New label") } },
    ) { modifier ->
        if (labels.isEmpty()) {
            Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Labels tag entries across folders, like \"travel\" or \"family\".", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(labels, key = { it.id }) { label ->
                    ElevatedCard(onClick = { editing = label }) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { ColorDot(label.color, size = 20) },
                            headlineContent = { Text(label.name) },
                            trailingContent = { IconButton(onClick = { deleting = label }) { Icon(Icons.Outlined.Delete, "Delete label") } },
                        )
                    }
                }
            }
        }
    }

    if (creating) {
        LabelEditDialog("New label", "", LabelPalette[labels.size % LabelPalette.size], { name, color -> scope.launch { store.createLabel(name, color) } }, { creating = false })
    }
    editing?.let { label ->
        LabelEditDialog("Edit label", label.name, label.color, { name, color -> scope.launch { store.updateLabel(label.id, name, color) } }, { editing = null })
    }
    deleting?.let { label ->
        ConfirmDialog(
            title = "Delete \"${label.name}\"?",
            text = "It will be removed from every entry. The entries themselves are kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { scope.launch { store.deleteLabel(label.id) } },
            onDismiss = { deleting = null },
        )
    }
}
