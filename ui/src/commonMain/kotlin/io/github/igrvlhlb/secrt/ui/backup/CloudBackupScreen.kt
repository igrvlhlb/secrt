package io.github.igrvlhlb.secrt.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.igrvlhlb.secrt.backup.BackupProgress
import io.github.igrvlhlb.secrt.backup.BackupResult
import io.github.igrvlhlb.secrt.backup.BackupTarget
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.CloudBackup
import io.github.igrvlhlb.secrt.backup.KeySlotsChangeDeclinedException
import io.github.igrvlhlb.secrt.backup.drive.GoogleSignIn
import io.github.igrvlhlb.secrt.backup.webdav.WebDavTarget
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.KeySlotsChange
import io.github.igrvlhlb.secrt.ui.components.AdoptKeySlotsDialog
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.ui.components.adoptedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The "Cloud backup" row of Settings, and the screen it opens. Its ViewModel lives with the open
 * diary, so a backup keeps going if the screen is closed or the phone rotated.
 */
@Composable
fun CloudBackupRow(session: AppSession) {
    val google = rememberGoogleSignIn()
    val vm = viewModel { CloudBackupViewModel(session, google) }
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val config = state.config
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
        headlineContent = { Text("Cloud backup") },
        supportingContent = {
            Text(
                when {
                    state.progress != null -> state.progress!!.label()
                    config == null -> "Keep an encrypted copy in your Google Drive or on your own server, and sync devices through it"
                    else -> "${config.description} · " + (state.lastBackupAt?.let { "last backup ${formatTime(it)}" } ?: "not backed up yet")
                },
            )
        },
        modifier = Modifier.clickable { open = true },
    )
    if (open) CloudBackupScreen(vm, onClose = { open = false })
    // Here rather than on the screen: a sync goes on (and may ask) after the screen is closed.
    state.keySlotsQuestion?.let { change -> AdoptKeySlotsDialog(change, via = "your cloud backup", onAnswer = vm::answerKeySlots) }
}

@Composable
private fun CloudBackupScreen(vm: CloudBackupViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var webDav by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    val config = state.config

    FullScreenDialog("Cloud backup", onClose) {
        when {
            state.loading -> CircularProgressIndicator()
            config == null && !webDav -> {
                Text("Where should the backup go?")
                if (vm.canUseGoogleDrive) {
                    PrimaryButton("Google Drive", busy = state.connecting, enabled = true, onClick = vm::connectGoogle)
                    Hint("A hidden folder in your Google Drive that only secrt can open. It counts towards your Google storage.")
                }
                SecondaryButton("Your own server (WebDAV)", enabled = !state.connecting) { vm.clearMessages(); webDav = true }
                Hint("Nextcloud, a NAS, or any WebDAV server.")
                state.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            config == null || editing -> {
                val existing = config as? BackupTargetConfig.WebDav
                var fields by rememberServerFields(existing?.let { ServerFields(it.url, it.username, it.password) } ?: ServerFields())
                ServerForm(fields, { fields = it }, enabled = !state.connecting, error = state.formError) {
                    PrimaryButton("Connect", busy = state.connecting, enabled = fields.complete) {
                        vm.connect(fields, save = true) { editing = false }
                    }
                    SecondaryButton("Test connection", enabled = fields.complete && !state.connecting) { vm.connect(fields, save = false) {} }
                    if (editing) {
                        TextButton(onClick = { editing = false; vm.clearMessages() }) { Text("Cancel") }
                    } else {
                        TextButton(onClick = { webDav = false; vm.clearMessages() }) { Text("Back") }
                    }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
            else -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (config) {
                            is BackupTargetConfig.GoogleDrive -> LabeledValue("Google Drive", config.account)
                            is BackupTargetConfig.WebDav -> LabeledValue("Server", config.description)
                        }
                        LabeledValue("Last backup", state.lastBackupAt?.let(::formatTime) ?: "never, from this device")
                    }
                }
                if (config is BackupTargetConfig.WebDav && !config.isEncryptedTransport) {
                    Notice("This server is reached without https: its password travels unprotected.")
                }
                val busy = state.progress != null
                PrimaryButton("Back up now", busy = busy, enabled = true, onClick = vm::backUp)
                Hint("Uploads the changes made on this device.")
                SecondaryButton("Sync now", enabled = !busy, onClick = vm::sync)
                Hint("Downloads what your other devices backed up, merges it, then uploads this device's changes.")
                state.progress?.let { ProgressCard(it) }
                state.message?.let { Text(it, color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                when (config) {
                    is BackupTargetConfig.GoogleDrive ->
                        TextButton(enabled = !busy && !state.connecting, onClick = vm::connectGoogle) { Text("Use another Google account") }
                    is BackupTargetConfig.WebDav ->
                        TextButton(enabled = !busy, onClick = { vm.clearMessages(); editing = true }) { Text("Change server or password") }
                }
                TextButton(enabled = !busy, onClick = { confirmDisconnect = true }) { Text("Disconnect") }
            }
        }
        Hint(
            "Everything is encrypted on this device before it is uploaded: the cloud only stores files it " +
                "can't read. Restoring on a new device needs your diary password or recovery key.",
        )
    }

    if (confirmDisconnect) {
        ConfirmDialog(
            title = "Disconnect cloud backup?",
            text = if (config is BackupTargetConfig.GoogleDrive) {
                "This device stops using ${config.account}. The backup stays in its Google Drive; you can delete it there " +
                    "under Settings › Manage apps."
            } else {
                "This device forgets the server and its password. The backup stays on the server."
            },
            confirmLabel = "Disconnect",
            destructive = true,
            onConfirm = vm::disconnect,
            onDismiss = { confirmDisconnect = false },
        )
    }
}

internal class CloudBackupViewModel(private val session: AppSession, private val google: GoogleSignIn?) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val config: BackupTargetConfig? = null,
        val lastBackupAt: Long? = null,
        /** Set while backing up or syncing. */
        val progress: BackupProgress? = null,
        val connecting: Boolean = false,
        val formError: String? = null,
        val message: String? = null,
        val messageIsError: Boolean = false,
        /** A sync waits for the user: take on the backup's newer password? See [answerKeySlots]. */
        val keySlotsQuestion: KeySlotsChange? = null,
    )

    private var keySlotsAnswer: CompletableDeferred<Boolean>? = null

    private val backup = CloudBackup(session)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val config = runCatching { backup.savedTarget() }.getOrNull()
            val last = config?.let { c -> runCatching { c.open(google).use { backup.lastBackupAt(it) } }.getOrNull() }
            _state.update { it.copy(loading = false, config = config, lastBackupAt = last) }
        }
    }

    val canUseGoogleDrive: Boolean get() = google != null

    /** Lets the user pick a Google account and grant access, checks Drive answers, and backs up there from now on. */
    fun connectGoogle() {
        val google = google ?: return
        _state.update { it.copy(connecting = true, formError = null, message = null) }
        viewModelScope.launch {
            try {
                val config = google.signIn()
                val last = config.open(google).use { target ->
                    target.list("")
                    backup.lastBackupAt(target)
                }
                backup.saveTarget(config)
                _state.update { it.copy(config = config, lastBackupAt = last, message = "Connected to ${config.description}", messageIsError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e.message ?: "Couldn't connect to Google Drive"
                // Shown where the user is: the choice of provider, or the connected screen.
                _state.update { if (it.config == null) it.copy(formError = error) else it.copy(message = error, messageIsError = true) }
            } finally {
                _state.update { it.copy(connecting = false) }
            }
        }
    }

    fun clearMessages() = _state.update { it.copy(formError = null, message = null) }

    /** Checks the server; with [save], also makes it the backup target and calls [onSaved]. */
    fun connect(fields: ServerFields, save: Boolean, onSaved: () -> Unit) {
        _state.update { it.copy(connecting = true, formError = null, message = null) }
        viewModelScope.launch {
            try {
                val config = WebDavTarget.connect(fields.url, fields.username.trim(), fields.password)
                if (save) {
                    backup.saveTarget(config)
                    val last = config.open(google).use { backup.lastBackupAt(it) }
                    _state.update { it.copy(config = config, lastBackupAt = last, message = "Connected to ${config.description}") }
                    onSaved()
                } else {
                    _state.update { it.copy(message = "The connection works: secrt can write to ${config.url}") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(formError = e.message ?: "Couldn't connect") }
            } finally {
                _state.update { it.copy(connecting = false) }
            }
        }
    }

    fun backUp() = run { target -> backup.push(target, ::onProgress) }

    fun sync() = run { target -> backup.sync(target, ::onProgress, ::confirmKeySlots) }

    fun answerKeySlots(accept: Boolean) {
        keySlotsAnswer?.complete(accept)
    }

    private suspend fun confirmKeySlots(change: KeySlotsChange): Boolean {
        val answer = CompletableDeferred<Boolean>()
        keySlotsAnswer = answer
        _state.update { it.copy(keySlotsQuestion = change) }
        try {
            return answer.await()
        } finally {
            keySlotsAnswer = null
            _state.update { it.copy(keySlotsQuestion = null) }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            backup.saveTarget(null)
            _state.update { State(loading = false, message = "Disconnected. The backup is still on the server.") }
        }
    }

    private fun onProgress(progress: BackupProgress) = _state.update { it.copy(progress = progress) }

    private fun run(action: suspend (BackupTarget) -> BackupResult) {
        val config = _state.value.config ?: return
        if (_state.value.progress != null) return
        _state.update { it.copy(progress = BackupProgress(BackupProgress.Stage.Checking, 0, 0), message = null) }
        viewModelScope.launch {
            try {
                val (result, last) = config.open(google).use { target -> action(target) to backup.lastBackupAt(target) }
                val adopted = session.keySlotsAdopted.value?.also { session.acknowledgeKeySlotsAdopted() }
                val message = result.summary() + (adopted?.let { " " + it.adoptedMessage() } ?: "")
                _state.update { it.copy(lastBackupAt = last, message = message, messageIsError = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: KeySlotsChangeDeclinedException) {
                // The user's own "no": nothing went wrong.
                _state.update { it.copy(message = e.message, messageIsError = false) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "The backup failed", messageIsError = true) }
            } finally {
                _state.update { it.copy(progress = null) }
            }
        }
    }
}
