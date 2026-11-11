package io.github.igrvlhlb.secrt.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.igrvlhlb.secrt.session.BiometricException
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.UiText
import io.github.igrvlhlb.secrt.ui.text.string
import io.github.igrvlhlb.secrt.ui.text.text
import io.github.igrvlhlb.secrt.ui.text.toUiText
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

const val MIN_PASSWORD_LENGTH = 8

/** Centered, scrollable column used by every screen shown before the diary is open. */
@Composable
private fun AuthLayout(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, stringResource(if (visible) Res.string.password_hide else Res.string.password_show))
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun BusyButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}

/** New password + confirmation, with validation. Shared by first setup and password resets. */
@Composable
private fun NewPasswordForm(buttonText: String, busy: Boolean, extra: @Composable () -> Unit = {}, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val tooShort = password.isNotEmpty() && password.length < MIN_PASSWORD_LENGTH
    val mismatch = confirmation.isNotEmpty() && confirmation != password
    val valid = password.length >= MIN_PASSWORD_LENGTH && password == confirmation

    PasswordField(password, { password = it }, stringResource(Res.string.password), isError = tooShort)
    if (tooShort) Text(stringResource(Res.string.password_too_short, MIN_PASSWORD_LENGTH), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    PasswordField(confirmation, { confirmation = it }, stringResource(Res.string.password_repeat), isError = mismatch, imeAction = ImeAction.Done, onDone = { if (valid) onSubmit(password) })
    if (mismatch) Text(stringResource(Res.string.password_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    extra()
    BusyButton(buttonText, busy, valid) { onSubmit(password) }
}

/**
 * @param moreRestoreOptions other ways to bring an existing diary, shown under the backup file one.
 * @param onSetUpFromDevice receiving the diary from another of the user's devices, labelled
 *   [setUpFromDeviceLabel].
 */
@Composable
fun SetupScreen(
    onCreate: suspend (password: String, withRecoveryKey: Boolean) -> Unit,
    onRestore: () -> Unit,
    moreRestoreOptions: @Composable () -> Unit = {},
    onSetUpFromDevice: (() -> Unit)? = null,
    setUpFromDeviceLabel: String = stringResource(Res.string.setup_from_another_device),
) {
    val scope = rememberCoroutineScope()
    var withRecoveryKey by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }

    AuthLayout(
        Icons.Outlined.Lock,
        stringResource(Res.string.setup_title),
        stringResource(Res.string.setup_subtitle),
    ) {
        NewPasswordForm(
            buttonText = stringResource(Res.string.setup_create),
            busy = busy,
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = withRecoveryKey, onCheckedChange = { withRecoveryKey = it })
                    Column {
                        Text(stringResource(Res.string.setup_recovery_key))
                        Text(
                            stringResource(Res.string.setup_recovery_key_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        ) { password ->
            busy = true
            error = null
            scope.launch {
                try {
                    onCreate(password, withRecoveryKey)
                } catch (e: Exception) {
                    error = e.toUiText(Res.string.setup_failed)
                } finally {
                    busy = false
                }
            }
        }
        error?.let { Text(it.string(), color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text(stringResource(Res.string.setup_have_diary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Outlined.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.setup_restore_file))
        }
        moreRestoreOptions()
        if (onSetUpFromDevice != null) {
            OutlinedButton(onClick = onSetUpFromDevice, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Outlined.Devices, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(setUpFromDeviceLabel)
            }
        }
    }
}

/**
 * @param onBiometricUnlock set when fingerprint unlock is on: it is offered right away (once the
 *   app is in front), and again from a button; the password form stays available as fallback.
 */
@Composable
fun UnlockScreen(onUnlock: suspend (Credential) -> Unit, onBiometricUnlock: (suspend () -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Prompt by itself once when the diary locks, and again each time the app comes back to the front.
    var autoPrompted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { autoPrompted = false }
    var prompting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<UiText?>(null) }

    fun promptForFingerprint() {
        val unlock = onBiometricUnlock ?: return
        if (prompting) return
        prompting = true
        notice = null
        scope.launch {
            try {
                unlock()
            } catch (e: BiometricException.Cancelled) {
                // The password form is right there.
            } catch (e: BiometricException) {
                notice = e.toUiText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.toUiText(Res.string.unlock_failed)
            } finally {
                prompting = false
            }
        }
    }

    LaunchedEffect(onBiometricUnlock != null, autoPrompted) {
        if (onBiometricUnlock == null || autoPrompted) return@LaunchedEffect
        // A prompt can't be shown over an app in the background (e.g. it just locked there): wait.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        autoPrompted = true
        promptForFingerprint()
    }

    CredentialScreen(
        title = stringResource(Res.string.unlock_title),
        passwordHint = stringResource(Res.string.unlock_password_hint),
        recoveryHint = stringResource(Res.string.unlock_recovery_hint),
        submitLabel = stringResource(Res.string.unlock_action),
        onSubmit = onUnlock,
        notice = notice?.string(),
        extraActions = {
            if (onBiometricUnlock != null) {
                OutlinedButton(onClick = ::promptForFingerprint, enabled = !prompting, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.unlock_fingerprint))
                }
            }
        },
    )
}

/** Restoring a backup on first run: it opens with the password or recovery key it was made with. */
@Composable
fun RestoreScreen(fileName: String, onRestore: suspend (Credential) -> Unit, onCancel: () -> Unit) {
    CredentialScreen(
        title = stringResource(Res.string.restore_title),
        passwordHint = stringResource(Res.string.restore_password_hint, fileName),
        recoveryHint = stringResource(Res.string.restore_recovery_hint, fileName),
        submitLabel = stringResource(Res.string.action_restore),
        onSubmit = onRestore,
        onCancel = onCancel,
    )
}

/**
 * Asks for the password, or the recovery key instead, and reports what went wrong.
 *
 * @param notice something the user should know before typing (e.g. why the fingerprint wasn't offered).
 * @param extraActions shown under the submit button.
 */
@Composable
internal fun CredentialScreen(
    title: String,
    passwordHint: String,
    recoveryHint: String,
    submitLabel: String,
    onSubmit: suspend (Credential) -> Unit,
    onCancel: (() -> Unit)? = null,
    notice: String? = null,
    extraActions: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var useRecoveryKey by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }

    fun submit() {
        if (secret.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onSubmit(if (useRecoveryKey) Credential.RecoveryKey(secret) else Credential.Password(secret))
            } catch (e: InvalidCredentialsException) {
                error = text(if (useRecoveryKey) Res.string.credential_wrong_recovery_key else Res.string.credential_wrong_password)
            } catch (e: Exception) {
                error = e.toUiText()
            } finally {
                busy = false
            }
        }
    }

    AuthLayout(if (useRecoveryKey) Icons.Outlined.Key else Icons.Outlined.Lock, title, if (useRecoveryKey) recoveryHint else passwordHint) {
        notice?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
            }
        }
        if (useRecoveryKey) {
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(Res.string.recovery_key)) },
                placeholder = { Text("XXXX-XXXX-…") },
                isError = error != null,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PasswordField(secret, { secret = it }, stringResource(Res.string.password), isError = error != null, imeAction = ImeAction.Done, onDone = ::submit)
        }
        error?.let { Text(it.string(), color = MaterialTheme.colorScheme.error) }
        BusyButton(submitLabel, busy, secret.isNotEmpty(), ::submit)
        extraActions()
        TextButton(onClick = { useRecoveryKey = !useRecoveryKey; secret = ""; error = null }) {
            Text(stringResource(if (useRecoveryKey) Res.string.credential_use_password else Res.string.credential_use_recovery_key))
        }
        if (onCancel != null) TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(Res.string.action_cancel)) }
    }
}

/** Shows a recovery key once, and only continues after the user confirms they saved it. */
@Composable
fun RecoveryKeyScreen(recoveryKey: String, onDone: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    AuthLayout(
        Icons.Outlined.Key,
        stringResource(Res.string.recovery_key_title),
        stringResource(Res.string.recovery_key_subtitle),
    ) {
        RecoveryKeyCard(recoveryKey)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text(stringResource(Res.string.recovery_key_written))
        }
        Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(stringResource(Res.string.action_continue)) }
    }
}

@Composable
fun RecoveryKeyCard(recoveryKey: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                recoveryKey.split('-').chunked(4).joinToString("\n") { it.joinToString("-") },
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
        }
    }
}

/** After unlocking with the recovery key, the user must choose a new password. */
@Composable
fun NewPasswordScreen(onSetPassword: suspend (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<UiText?>(null) }
    AuthLayout(Icons.Outlined.Lock, stringResource(Res.string.new_password_title), stringResource(Res.string.new_password_subtitle)) {
        NewPasswordForm(stringResource(Res.string.new_password_save), busy) { password ->
            busy = true
            scope.launch {
                try {
                    onSetPassword(password)
                } catch (e: Exception) {
                    error = e.toUiText(Res.string.change_password_failed)
                } finally {
                    busy = false
                }
            }
        }
        error?.let { Text(it.string(), color = MaterialTheme.colorScheme.error) }
    }
}
