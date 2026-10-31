package io.github.igrvlhlb.secrt.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.ui.auth.NewPasswordScreen
import io.github.igrvlhlb.secrt.ui.auth.RecoveryKeyScreen
import io.github.igrvlhlb.secrt.ui.auth.SetupScreen
import io.github.igrvlhlb.secrt.ui.auth.UnlockScreen
import io.github.igrvlhlb.secrt.ui.editor.EditorScreen
import io.github.igrvlhlb.secrt.ui.editor.EditorViewModel
import io.github.igrvlhlb.secrt.ui.home.HomeScreen
import io.github.igrvlhlb.secrt.ui.theme.SecrtTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

/**
 * Root of the app on every platform.
 *
 * @param backgroundScope application-wide scope for work that must finish even if the UI goes
 *   away (saving the entry being edited).
 */
@Composable
fun SecrtApp(session: AppSession, backgroundScope: CoroutineScope) {
    SecrtTheme {
        Surface(Modifier.fillMaxSize()) {
            val state by session.state.collectAsStateWithLifecycle()
            val autoLock = rememberAutoLock()
            val unlockedScope = viewModel { UnlockedViewModelScope() }
            var creating by remember { mutableStateOf(false) }
            var pendingRecoveryKey by remember { mutableStateOf<String?>(null) }

            AutoLockEffect(session, autoLock)
            // Everything opened while unlocked (ViewModels holding diary content) goes away on lock.
            LaunchedEffect(state) { if (state !is SessionState.Unlocked) unlockedScope.clear() }

            when (val s = state) {
                SessionState.NoVault -> SetupScreen { password, withRecoveryKey ->
                    creating = true
                    try {
                        pendingRecoveryKey = session.create(password, withRecoveryKey)
                    } finally {
                        creating = false
                    }
                }
                SessionState.Locked -> UnlockScreen(
                    onUnlock = { session.unlock(it) },
                    onUnlockWithRecoveryKey = { session.unlockWithRecoveryKey(it) },
                )
                is SessionState.Unlocked -> when {
                    creating -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    pendingRecoveryKey != null -> RecoveryKeyScreen(pendingRecoveryKey!!) { pendingRecoveryKey = null }
                    s.viaRecoveryKey -> NewPasswordScreen { session.changePassword(currentPassword = null, newPassword = it) }
                    else -> CompositionLocalProvider(LocalViewModelStoreOwner provides unlockedScope) {
                        DiaryNavigation(s.store, session, autoLock, backgroundScope)
                    }
                }
            }
        }
    }
}

@Serializable
private data object HomeRoute

@Serializable
private data class EditorRoute(val entryId: String? = null, val folderId: String? = null)

@Composable
private fun DiaryNavigation(store: DiaryStore, session: AppSession, autoLock: AutoLock, backgroundScope: CoroutineScope) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                store = store,
                session = session,
                onOpenEntry = { nav.navigate(EditorRoute(entryId = it)) },
                onNewEntry = { nav.navigate(EditorRoute(folderId = it)) },
            )
        }
        composable<EditorRoute> { entry ->
            val route = entry.toRoute<EditorRoute>()
            val vm = viewModel { EditorViewModel(store, route.entryId, route.folderId, backgroundScope) }
            // Popping *to* home (not just once) keeps a double-tapped back from leaving a blank screen.
            EditorScreen(vm, store, autoLock, onClose = { nav.popBackStack(HomeRoute, inclusive = false) })
        }
    }
}

/** A ViewModel store that lives as long as the activity but is cleared whenever the diary locks. */
private class UnlockedViewModelScope : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun clear() = viewModelStore.clear()

    override fun onCleared() = viewModelStore.clear()
}
