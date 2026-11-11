package io.github.igrvlhlb.secrt.ui.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.igrvlhlb.secrt.backup.BackupProgress
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.CloudBackup
import io.github.igrvlhlb.secrt.backup.drive.GoogleSignIn
import io.github.igrvlhlb.secrt.backup.webdav.WebDavTarget
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.ui.auth.PasswordField
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.UiText
import io.github.igrvlhlb.secrt.ui.text.string
import io.github.igrvlhlb.secrt.ui.text.text
import io.github.igrvlhlb.secrt.ui.text.toUiText
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * "Restore from cloud backup" on the welcome screen, and the flow it opens: reach the server,
 * pick the diary if there are several, then unlock it with its password or recovery key. On
 * success the session opens the restored diary and the welcome screen (with this) goes away.
 */
@Composable
fun CloudRestoreButton(session: AppSession) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.restore_cloud))
    }
    if (open) {
        val google = rememberGoogleSignIn()
        val vm = viewModel { CloudRestoreViewModel(session, google) }
        CloudRestoreScreen(vm, onClose = { vm.reset(); open = false })
    }
}

@Composable
private fun CloudRestoreScreen(vm: CloudRestoreViewModel, onClose: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    FullScreenDialog(stringResource(Res.string.restore_cloud), onClose = { if (state.progress == null) onClose() }) {
        when (val step = state.step) {
            Step.Server -> {
                var fields by rememberServerFields()
                if (vm.canUseGoogleDrive) {
                    PrimaryButton(stringResource(Res.string.restore_cloud_find_google), busy = state.busy && state.searchingGoogle, enabled = !state.busy, onClick = vm::findOnGoogleDrive)
                    Hint(stringResource(Res.string.restore_cloud_or_server))
                }
                ServerForm(fields, { fields = it }, enabled = !state.busy, error = state.error) {
                    PrimaryButton(stringResource(Res.string.restore_cloud_find), busy = state.busy && !state.searchingGoogle, enabled = fields.complete && !state.busy) { vm.findDiaries(fields) }
                }
            }
            is Step.ChooseDiary -> {
                Text(stringResource(Res.string.restore_cloud_choose))
                step.vaultIds.forEach { id ->
                    Card(Modifier.fillMaxWidth().clickable { vm.choose(id) }) {
                        ListItem(headlineContent = { Text(stringResource(Res.string.restore_cloud_diary, id.take(8))) }, supportingContent = { Text(id) })
                    }
                }
                TextButton(onClick = vm::back) { Text(stringResource(Res.string.action_back)) }
            }
            is Step.Unlock -> {
                var useRecoveryKey by remember { mutableStateOf(false) }
                var secret by remember { mutableStateOf("") }
                val restoring = state.progress != null
                fun submit() {
                    if (secret.isNotEmpty() && !restoring) vm.restore(if (useRecoveryKey) Credential.RecoveryKey(secret) else Credential.Password(secret))
                }
                Text(
                    stringResource(
                        if (useRecoveryKey) Res.string.restore_cloud_recovery_hint else Res.string.restore_cloud_password_hint,
                        step.config.description,
                    ),
                )
                if (useRecoveryKey) {
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text(stringResource(Res.string.recovery_key)) },
                        placeholder = { Text("XXXX-XXXX-…") },
                        isError = state.error != null,
                        singleLine = true,
                        enabled = !restoring,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    PasswordField(secret, { secret = it }, stringResource(Res.string.restore_cloud_diary_password), isError = state.error != null, imeAction = ImeAction.Done, onDone = ::submit)
                }
                state.error?.let { Text(it.string(), color = MaterialTheme.colorScheme.error) }
                PrimaryButton(stringResource(Res.string.action_restore), busy = restoring, enabled = secret.isNotEmpty(), onClick = ::submit)
                state.progress?.let { ProgressCard(it) }
                TextButton(enabled = !restoring, onClick = { useRecoveryKey = !useRecoveryKey; secret = ""; vm.clearError() }) {
                    Text(stringResource(if (useRecoveryKey) Res.string.credential_use_password else Res.string.credential_use_recovery_key))
                }
                TextButton(enabled = !restoring, onClick = vm::back) { Text(stringResource(Res.string.action_back)) }
            }
        }
    }
}

internal sealed interface Step {
    data object Server : Step

    data class ChooseDiary(val config: BackupTargetConfig, val vaultIds: List<String>) : Step

    data class Unlock(val config: BackupTargetConfig, val vaultId: String, val choices: List<String>) : Step
}

internal class CloudRestoreViewModel(session: AppSession, private val google: GoogleSignIn?) : ViewModel() {

    data class State(
        val step: Step = Step.Server,
        val busy: Boolean = false,
        /** The search under way is in Google Drive (not on a WebDAV server). */
        val searchingGoogle: Boolean = false,
        val error: UiText? = null,
        val progress: BackupProgress? = null,
    )

    private val backup = CloudBackup(session)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Forgets everything typed (the server password among it). */
    fun reset() = _state.update { State() }

    fun clearError() = _state.update { it.copy(error = null) }

    val canUseGoogleDrive: Boolean get() = google != null

    fun findDiaries(fields: ServerFields) = find(google = false) { WebDavTarget.connect(fields.url, fields.username.trim(), fields.password) }

    /** Lets the user pick a Google account, then looks for backups in its Drive. */
    fun findOnGoogleDrive() {
        val google = google ?: return
        find(google = true) { google.signIn() }
    }

    private fun find(google: Boolean, connect: suspend () -> BackupTargetConfig) {
        _state.update { it.copy(busy = true, searchingGoogle = google, error = null) }
        viewModelScope.launch {
            try {
                val config = connect()
                val ids = config.open(this@CloudRestoreViewModel.google).use { backup.findDiaries(it) }
                val step = when (ids.size) {
                    0 -> null
                    1 -> Step.Unlock(config, ids.single(), ids)
                    else -> Step.ChooseDiary(config, ids)
                }
                _state.update {
                    if (step == null) {
                        it.copy(
                            error = if (google) {
                                text(Res.string.restore_cloud_none_google, (config as BackupTargetConfig.GoogleDrive).account)
                            } else {
                                text(Res.string.restore_cloud_none)
                            },
                        )
                    } else {
                        it.copy(step = step)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUiText(Res.string.backup_connect_failed)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun choose(vaultId: String) = _state.update { state ->
        val step = state.step as? Step.ChooseDiary ?: return@update state
        state.copy(step = Step.Unlock(step.config, vaultId, step.vaultIds), error = null)
    }

    fun back() = _state.update { state ->
        when (val step = state.step) {
            is Step.Unlock -> state.copy(step = if (step.choices.size > 1) Step.ChooseDiary(step.config, step.choices) else Step.Server, error = null)
            else -> state.copy(step = Step.Server, error = null)
        }
    }

    fun restore(credential: Credential) {
        val step = _state.value.step as? Step.Unlock ?: return
        _state.update { it.copy(progress = BackupProgress(BackupProgress.Stage.Checking, 0, 0), error = null) }
        viewModelScope.launch {
            try {
                step.config.open(google).use { target ->
                    backup.restore(target, step.vaultId, credential, step.config) { p -> _state.update { it.copy(progress = p) } }
                }
                // The diary is open now and this screen is about to go away.
                reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidCredentialsException) {
                _state.update {
                    it.copy(error = text(if (credential is Credential.RecoveryKey) Res.string.credential_wrong_recovery_key else Res.string.credential_wrong_password))
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUiText(Res.string.restore_cloud_failed)) }
            } finally {
                _state.update { it.copy(progress = null) }
            }
        }
    }
}
