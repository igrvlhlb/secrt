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
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.ui.auth.NewPasswordScreen
import io.github.igrvlhlb.secrt.ui.auth.RecoveryKeyScreen
import io.github.igrvlhlb.secrt.ui.auth.RestoreScreen
import io.github.igrvlhlb.secrt.ui.auth.SetupScreen
import io.github.igrvlhlb.secrt.ui.auth.UnlockScreen
import io.github.igrvlhlb.secrt.ui.backup.CloudRestoreButton
import io.github.igrvlhlb.secrt.ui.devices.DevicesScreen
import io.github.igrvlhlb.secrt.ui.devices.JoinFromPhoneScreen
import io.github.igrvlhlb.secrt.ui.devices.LocalOpenDevices
import io.github.igrvlhlb.secrt.ui.devices.isComputer
import io.github.igrvlhlb.secrt.ui.editor.EditorScreen
import io.github.igrvlhlb.secrt.ui.editor.EditorViewModel
import io.github.igrvlhlb.secrt.ui.home.HomeScreen
import io.github.igrvlhlb.secrt.ui.platform.PickedDocument
import io.github.igrvlhlb.secrt.ui.platform.rememberOpenDocument
import io.github.igrvlhlb.secrt.ui.theme.SecrtTheme
import io.github.igrvlhlb.secrt.vault.Credential
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable

/**
 * Root of the app on every platform.
 *
 * @param backgroundScope application-wide scope for work that must finish even if the UI goes
 *   away (saving the entry being edited).
 * @param autoLock pass one that lives as long as [session] where the UI can be recreated while
 *   the diary stays open (Android activities); see [AutoLock].
 * @param lan pairing and sync with the user's other devices; `null` leaves it out.
 */
@Composable
fun SecrtApp(
    session: AppSession,
    backgroundScope: CoroutineScope,
    autoLock: AutoLock = remember(session) { AutoLock(session, backgroundScope) },
    lan: LanSync? = null,
) {
    SecrtTheme {
        Surface(Modifier.fillMaxSize()) {
            val state by session.state.collectAsStateWithLifecycle()
            val biometricUnlock by session.biometricUnlockEnabled.collectAsStateWithLifecycle()
            val unlockedScope = viewModel { UnlockedViewModelScope() }
            var creating by remember { mutableStateOf(false) }
            var pendingRecoveryKey by remember { mutableStateOf<String?>(null) }
            var backupToRestore by remember { mutableStateOf<PickedDocument?>(null) }
            var joiningFromPhone by remember { mutableStateOf(false) }
            val pickBackup = rememberOpenDocument { backupToRestore = it }

            AutoLockEffect(autoLock)
            // Everything opened while unlocked (ViewModels holding diary content) goes away on lock.
            LaunchedEffect(state) { if (state !is SessionState.Unlocked) unlockedScope.clear() else joiningFromPhone = false }

            when (val s = state) {
                SessionState.NoVault -> when (val backup = backupToRestore) {
                    null if joiningFromPhone && lan != null -> JoinFromPhoneScreen(lan, onCancel = { joiningFromPhone = false })
                    null -> SetupScreen(
                        onCreate = { password, withRecoveryKey ->
                            creating = true
                            try {
                                pendingRecoveryKey = session.create(password, withRecoveryKey)
                            } finally {
                                creating = false
                            }
                        },
                        onRestore = pickBackup,
                        moreRestoreOptions = { CloudRestoreButton(session) },
                        onSetUpFromPhone = if (lan != null && isComputer) ({ joiningFromPhone = true }) else null,
                    )
                    else -> RestoreScreen(
                        fileName = backup.name,
                        onRestore = { credential ->
                            session.restore(backup.openSource(), credential)
                            backupToRestore = null
                        },
                        onCancel = { backupToRestore = null },
                    )
                }
                SessionState.Locked -> UnlockScreen(
                    onUnlock = { credential ->
                        when (credential) {
                            is Credential.Password -> session.unlock(credential.password)
                            is Credential.RecoveryKey -> session.unlockWithRecoveryKey(credential.recoveryKey)
                        }
                    },
                    onBiometricUnlock = if (biometricUnlock) session::unlockWithBiometrics else null,
                )
                is SessionState.Unlocked -> when {
                    creating -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    pendingRecoveryKey != null -> RecoveryKeyScreen(pendingRecoveryKey!!) { pendingRecoveryKey = null }
                    s.viaRecoveryKey -> NewPasswordScreen { session.changePassword(currentPassword = null, newPassword = it) }
                    else -> CompositionLocalProvider(LocalViewModelStoreOwner provides unlockedScope, LocalAutoLock provides autoLock) {
                        DiaryNavigation(s.store, session, autoLock, backgroundScope, lan)
                    }
                }
            }
        }
    }
}

// Not private: on the JVM, navigation finds their serializers by reflection, which can't read a
// private class's members (Android's runtime doesn't check, so only the desktop app crashed).
@Serializable
internal data object HomeRoute

@Serializable
internal data class EditorRoute(val entryId: String? = null, val folderId: String? = null)

@Serializable
internal data object DevicesRoute

@Composable
private fun DiaryNavigation(store: DiaryStore, session: AppSession, autoLock: AutoLock, backgroundScope: CoroutineScope, lan: LanSync?) {
    val nav = rememberNavController()
    val openDevices: (() -> Unit)? = if (lan == null) null else ({ nav.navigate(DevicesRoute) { launchSingleTop = true } })
    if (lan != null) {
        // A pairing code handed in from outside (debug builds) is picked up by the Devices screen.
        val pendingLink by lan.pendingPairingLink.collectAsStateWithLifecycle()
        LaunchedEffect(pendingLink != null) { if (pendingLink != null) openDevices?.invoke() }
    }
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            CompositionLocalProvider(LocalOpenDevices provides openDevices) {
                HomeScreen(
                    store = store,
                    session = session,
                    onOpenEntry = { nav.navigate(EditorRoute(entryId = it)) },
                    onNewEntry = { nav.navigate(EditorRoute(folderId = it)) },
                )
            }
        }
        if (lan != null) {
            composable<DevicesRoute> { DevicesScreen(lan, store, onBack = { nav.popBackStack(HomeRoute, inclusive = false) }) }
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
