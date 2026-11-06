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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
                Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide" else "Show")
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

    PasswordField(password, { password = it }, "Password", isError = tooShort)
    if (tooShort) Text("At least $MIN_PASSWORD_LENGTH characters", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    PasswordField(confirmation, { confirmation = it }, "Repeat password", isError = mismatch, imeAction = ImeAction.Done, onDone = { if (valid) onSubmit(password) })
    if (mismatch) Text("Passwords don't match", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
    setUpFromDeviceLabel: String = "Set up from another device",
) {
    val scope = rememberCoroutineScope()
    var withRecoveryKey by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthLayout(
        Icons.Outlined.Lock,
        "Welcome to secrt",
        "Your diary is encrypted and stays on this device. Choose a password to protect it. " +
            "We can't recover it for you, so make it memorable.",
    ) {
        NewPasswordForm(
            buttonText = "Create diary",
            busy = busy,
            extra = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = withRecoveryKey, onCheckedChange = { withRecoveryKey = it })
                    Column {
                        Text("Create a recovery key (recommended)")
                        Text(
                            "A code you write down to get back in if you forget the password.",
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
                    error = e.message ?: "Couldn't create the diary"
                } finally {
                    busy = false
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("Already have a diary?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Icon(Icons.Outlined.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Restore from a backup file")
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
    var notice by remember { mutableStateOf<String?>(null) }

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
                notice = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.message ?: "Couldn't open the diary"
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
        title = "Diary locked",
        passwordHint = "Enter your password to open your diary.",
        recoveryHint = "Enter the recovery key you saved when you created the diary.",
        submitLabel = "Unlock",
        onSubmit = onUnlock,
        notice = notice,
        extraActions = {
            if (onBiometricUnlock != null) {
                OutlinedButton(onClick = ::promptForFingerprint, enabled = !prompting, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock with fingerprint")
                }
            }
        },
    )
}

/** Restoring a backup on first run: it opens with the password or recovery key it was made with. */
@Composable
fun RestoreScreen(fileName: String, onRestore: suspend (Credential) -> Unit, onCancel: () -> Unit) {
    CredentialScreen(
        title = "Restore backup",
        passwordHint = "$fileName\n\nEnter the password of the diary this backup was made from.",
        recoveryHint = "$fileName\n\nEnter the recovery key of the diary this backup was made from.",
        submitLabel = "Restore",
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
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (secret.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onSubmit(if (useRecoveryKey) Credential.RecoveryKey(secret) else Credential.Password(secret))
            } catch (e: InvalidCredentialsException) {
                error = if (useRecoveryKey) "That recovery key doesn't match" else "Wrong password"
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong"
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
                label = { Text("Recovery key") },
                placeholder = { Text("XXXX-XXXX-…") },
                isError = error != null,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            PasswordField(secret, { secret = it }, "Password", isError = error != null, imeAction = ImeAction.Done, onDone = ::submit)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        BusyButton(submitLabel, busy, secret.isNotEmpty(), ::submit)
        extraActions()
        TextButton(onClick = { useRecoveryKey = !useRecoveryKey; secret = ""; error = null }) {
            Text(if (useRecoveryKey) "Use password instead" else "Forgot password? Use recovery key")
        }
        if (onCancel != null) TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") }
    }
}

/** Shows a recovery key once, and only continues after the user confirms they saved it. */
@Composable
fun RecoveryKeyScreen(recoveryKey: String, onDone: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    AuthLayout(
        Icons.Outlined.Key,
        "Your recovery key",
        "Write this down and keep it somewhere safe, away from your phone. It opens your diary if you " +
            "forget your password. It won't be shown again.",
    ) {
        RecoveryKeyCard(recoveryKey)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("I've written it down")
        }
        Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Continue") }
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
    var error by remember { mutableStateOf<String?>(null) }
    AuthLayout(Icons.Outlined.Lock, "Choose a new password", "You opened the diary with your recovery key. Pick a new password to keep using it.") {
        NewPasswordForm("Save password", busy) { password ->
            busy = true
            scope.launch {
                try {
                    onSetPassword(password)
                } catch (e: Exception) {
                    error = e.message ?: "Couldn't change the password"
                } finally {
                    busy = false
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
