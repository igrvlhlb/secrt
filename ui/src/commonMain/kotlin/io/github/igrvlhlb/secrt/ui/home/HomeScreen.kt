package io.github.igrvlhlb.secrt.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.storage.DiaryStore

enum class HomeTab(val title: String, val icon: ImageVector) {
    Journal("Journal", Icons.Outlined.Book),
    Folders("Folders", Icons.Outlined.Folder),
    Labels("Labels", Icons.AutoMirrored.Outlined.Label),
    Settings("Settings", Icons.Outlined.Settings),
}

/** Snackbar of the home screen, so any tab can report errors. */
val LocalSnackbar = compositionLocalOf { SnackbarHostState() }

@Composable
fun HomeScreen(
    store: DiaryStore,
    session: AppSession,
    onOpenEntry: (String) -> Unit,
    onNewEntry: (folderId: String?) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.Journal) }
    val snackbar = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    HomeTab.entries.forEach {
                        NavigationBarItem(
                            selected = tab == it,
                            onClick = { tab = it },
                            icon = { Icon(it.icon, contentDescription = null) },
                            label = { Text(it.title) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    HomeTab.Journal -> JournalTab(store, session, onOpenEntry, onNewEntry)
                    HomeTab.Folders -> FoldersTab(store, onOpenEntry, onNewEntry)
                    HomeTab.Labels -> LabelsTab(store)
                    HomeTab.Settings -> SettingsTab(session)
                }
            }
        }
    }
}

/** Scaffold for a tab: own top bar and FAB, while the outer scaffold handles the navigation bar. */
@Composable
fun TabScaffold(
    topBar: @Composable () -> Unit,
    fab: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        floatingActionButton = { fab?.invoke() },
        contentWindowInsets = WindowInsets(0),
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
fun NewEntryFab(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick) { Icon(Icons.Outlined.Book, contentDescription = "New entry") }
}
