package io.github.igrvlhlb.secrt.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import io.github.igrvlhlb.secrt.archive.SecrtArchive
import io.github.igrvlhlb.secrt.ui.LocalAutoLock
import io.github.igrvlhlb.secrt.ui.components.today
import io.github.igrvlhlb.secrt.ui.platform.rememberCreateDocument
import io.github.igrvlhlb.secrt.ui.platform.rememberOpenDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.AutoLockTimeout
import io.github.igrvlhlb.secrt.ui.auth.MIN_PASSWORD_LENGTH
import io.github.igrvlhlb.secrt.ui.auth.PasswordField
import io.github.igrvlhlb.secrt.ui.auth.RecoveryKeyCard
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.launch

@Composable
fun SettingsTab(session: AppSession) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var hasRecoveryKey by remember { mutableStateOf<Boolean?>(null) }
    var changingPassword by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var shownKey by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    val autoLock = LocalAutoLock.current
    val settings by session.settings.collectAsStateWithLifecycle()
    var choosingAutoLock by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasRecoveryKey = session.hasRecoveryKey() }

    /** Runs a backup operation with a blocking progress dialog, reporting failures in the snackbar. */
    fun runWithProgress(label: String, action: suspend () -> String) {
        working = label
        scope.launch {
            val message = try {
                action()
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            } finally {
                working = null
            }
            snackbar.showSnackbar(message)
        }
    }

    val createBackupFile = rememberCreateDocument { target ->
        autoLock.resume()
        if (target != null) {
            runWithProgress("Exporting…") {
                try {
                    session.exportTo(target.openSink())
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                "Backup saved as ${target.name}"
            }
        }
    }
    val pickBackupFile = rememberOpenDocument { document ->
        autoLock.resume()
        if (document != null) {
            runWithProgress("Importing…") {
                when (val added = session.importFrom(document.openSource())) {
                    0 -> "Nothing new in ${document.name}"
                    1 -> "Imported 1 change"
                    else -> "Imported $added changes"
                }
            }
        }
    }

    TabScaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ElevatedCard {
                SettingRow(Icons.Outlined.Password, "Change password", "Your data stays encrypted with the same key") { changingPassword = true }
                HorizontalDivider()
                SettingRow(
                    Icons.Outlined.Key,
                    if (hasRecoveryKey == true) "Create a new recovery key" else "Create a recovery key",
                    when (hasRecoveryKey) {
                        true -> "You have one. A new key replaces it."
                        false -> "You don't have one. Without it, a forgotten password means losing the diary."
                        null -> ""
                    },
                ) { confirmRegenerate = true }
                if (hasRecoveryKey == true) {
                    HorizontalDivider()
                    SettingRow(Icons.Outlined.KeyOff, "Remove recovery key", "Only your password will open the diary") { confirmRemove = true }
                }
            }
            ElevatedCard {
                SettingRow(
                    Icons.Outlined.Upload,
                    "Export encrypted backup",
                    "Save a .secrt file of the whole diary. It opens only with your password or recovery key.",
                ) {
                    autoLock.pause()
                    createBackupFile(SecrtArchive.fileName(today().toString()))
                }
                HorizontalDivider()
                SettingRow(
                    Icons.Outlined.Download,
                    "Import a backup",
                    "Merge entries from a .secrt backup of this diary, e.g. one made on another device.",
                ) {
                    autoLock.pause()
                    pickBackupFile()
                }
            }
            ElevatedCard {
                SettingRow(Icons.Outlined.Timer, "Auto-lock", settings.autoLock.description) { choosingAutoLock = true }
                HorizontalDivider()
                SettingRow(Icons.Outlined.Lock, "Lock now", "Close the diary until you unlock it again") { scope.launch { session.lock() } }
            }
            ElevatedCard {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    headlineContent = { Text("About your data") },
                    supportingContent = {
                        Text(
                            "Entries, folders, labels and attachments are stored encrypted on this device only. " +
                                "Nothing is uploaded: backups are files you export and keep wherever you like. " +
                                "Syncing with other devices is coming later.",
                        )
                    },
                )
            }
        }
    }

    if (choosingAutoLock) {
        AutoLockDialog(
            current = settings.autoLock,
            onSelect = { option ->
                choosingAutoLock = false
                scope.launch { session.updateSettings { it.copy(autoLock = option) } }
            },
            onDismiss = { choosingAutoLock = false },
        )
    }
    if (changingPassword) {
        ChangePasswordDialog(
            onChange = { current, new -> session.changePassword(current, new); snackbar.showSnackbar("Password changed") },
            onDismiss = { changingPassword = false },
        )
    }
    if (confirmRegenerate) {
        ConfirmDialog(
            title = "Create a recovery key?",
            text = if (hasRecoveryKey == true) "Your current recovery key will stop working." else "You'll see it once. Write it down somewhere safe.",
            confirmLabel = "Create",
            onConfirm = { scope.launch { shownKey = session.regenerateRecoveryKey(); hasRecoveryKey = true } },
            onDismiss = { confirmRegenerate = false },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove recovery key?",
            text = "If you forget your password, there will be no way to open your diary.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { scope.launch { session.removeRecoveryKey(); hasRecoveryKey = false } },
            onDismiss = { confirmRemove = false },
        )
    }
    working?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(label)
                }
            },
            confirmButton = {},
        )
    }
    shownKey?.let { key ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Your new recovery key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Write it down and keep it away from your phone. It won't be shown again.")
                    RecoveryKeyCard(key)
                }
            },
            confirmButton = { TextButton(onClick = { shownKey = null }) { Text("I've written it down") } },
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { if (subtitle.isNotEmpty()) Text(subtitle) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private val AutoLockTimeout.label: String
    get() = when (this) {
        AutoLockTimeout.Immediately -> "Immediately"
        AutoLockTimeout.OneMinute -> "After 1 minute"
        AutoLockTimeout.FiveMinutes -> "After 5 minutes"
        AutoLockTimeout.FifteenMinutes -> "After 15 minutes"
        AutoLockTimeout.OneHour -> "After 1 hour"
        AutoLockTimeout.OnAppRestart -> "When the app restarts"
    }

private val AutoLockTimeout.description: String
    get() = when (this) {
        AutoLockTimeout.Immediately -> "As soon as you leave the app"
        AutoLockTimeout.OnAppRestart -> "Only when the app is closed or the phone restarts"
        else -> "$label in the background"
    }

@Composable
private fun AutoLockDialog(current: AutoLockTimeout, onSelect: (AutoLockTimeout) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lock the diary") },
        text = {
            Column(Modifier.selectableGroup()) {
                AutoLockTimeout.entries.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .selectable(selected = option == current, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(vertical = 12.dp),
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(option.label)
                    }
                }
                Text(
                    "Time the app can spend in the background before it asks for your password again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChangePasswordDialog(onChange: suspend (current: String, new: String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = current.isNotEmpty() && new.length >= MIN_PASSWORD_LENGTH && new == repeat

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(current, { current = it }, "Current password", isError = error != null)
                PasswordField(new, { new = it }, "New password (min. $MIN_PASSWORD_LENGTH)")
                PasswordField(repeat, { repeat = it }, "Repeat new password", isError = repeat.isNotEmpty() && repeat != new, imeAction = ImeAction.Done)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        onChange(current, new)
                        onDismiss()
                    } catch (e: InvalidCredentialsException) {
                        error = "Current password is wrong"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Saving…" else "Change") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
