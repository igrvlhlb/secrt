package io.github.igrvlhlb.secrt.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.igrvlhlb.secrt.archive.SecrtArchive
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.AutoLockTimeout
import io.github.igrvlhlb.secrt.session.BiometricAvailability
import io.github.igrvlhlb.secrt.session.BiometricException
import io.github.igrvlhlb.secrt.ui.LocalAutoLock
import io.github.igrvlhlb.secrt.ui.auth.MIN_PASSWORD_LENGTH
import io.github.igrvlhlb.secrt.ui.auth.PasswordField
import io.github.igrvlhlb.secrt.ui.auth.RecoveryKeyCard
import io.github.igrvlhlb.secrt.ui.backup.CloudBackupRow
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.ui.components.today
import io.github.igrvlhlb.secrt.ui.devices.LocalOpenDevices
import io.github.igrvlhlb.secrt.ui.platform.rememberCreateDocument
import io.github.igrvlhlb.secrt.ui.platform.rememberOpenDocument
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.UiText
import io.github.igrvlhlb.secrt.ui.text.plural
import io.github.igrvlhlb.secrt.ui.text.resolve
import io.github.igrvlhlb.secrt.ui.text.text
import io.github.igrvlhlb.secrt.ui.text.toUiText
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SettingsTab(session: AppSession) {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var hasRecoveryKey by remember { mutableStateOf<Boolean?>(null) }
    var changingPassword by remember { mutableStateOf(false) }
    var confirmRegenerate by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var shownKey by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf<StringResource?>(null) }
    val autoLock = LocalAutoLock.current
    val settings by session.settings.collectAsStateWithLifecycle()
    val biometricEnabled by session.biometricUnlockEnabled.collectAsStateWithLifecycle()
    var biometricAvailability by remember { mutableStateOf(session.biometricAvailability()) }
    var biometricBusy by remember { mutableStateOf(false) }
    var choosingAutoLock by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasRecoveryKey = session.hasRecoveryKey() }
    // The user may have just enrolled a fingerprint in the system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { biometricAvailability = session.biometricAvailability() }

    fun setBiometricUnlock(on: Boolean) {
        biometricBusy = true
        scope.launch {
            val message = try {
                if (on) {
                    // The system prompt must not count as leaving the app.
                    autoLock.pause()
                    session.enableBiometricUnlock()
                    text(Res.string.settings_fingerprint_turned_on)
                } else {
                    session.disableBiometricUnlock()
                    null
                }
            } catch (e: BiometricException.Cancelled) {
                null
            } catch (e: BiometricException) {
                e.toUiText()
            } finally {
                if (on) autoLock.resume()
                biometricBusy = false
            }
            message?.let { snackbar.showSnackbar(it.resolve()) }
        }
    }

    /** Runs a backup operation with a blocking progress dialog, reporting failures in the snackbar. */
    fun runWithProgress(label: StringResource, action: suspend () -> UiText) {
        working = label
        scope.launch {
            val message = try {
                action()
            } catch (e: Exception) {
                e.toUiText()
            } finally {
                working = null
            }
            snackbar.showSnackbar(message.resolve())
        }
    }

    val createBackupFile = rememberCreateDocument { target ->
        autoLock.resume()
        if (target != null) {
            runWithProgress(Res.string.settings_exporting) {
                try {
                    session.exportTo(target.openSink())
                } catch (e: Exception) {
                    target.delete()
                    throw e
                }
                text(Res.string.settings_backup_saved, target.name)
            }
        }
    }
    val pickBackupFile = rememberOpenDocument { document ->
        autoLock.resume()
        if (document != null) {
            runWithProgress(Res.string.settings_importing) {
                when (val added = session.importFrom(document.openSource())) {
                    0 -> text(Res.string.settings_import_nothing, document.name)
                    else -> plural(Res.plurals.settings_imported, added)
                }
            }
        }
    }

    TabScaffold(topBar = { TopAppBar(title = { Text(stringResource(Res.string.tab_settings)) }) }) { modifier ->
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ElevatedCard {
                SettingRow(Icons.Outlined.Password, stringResource(Res.string.settings_change_password), stringResource(Res.string.settings_change_password_hint)) {
                    changingPassword = true
                }
                HorizontalDivider()
                SettingRow(
                    Icons.Outlined.Key,
                    stringResource(if (hasRecoveryKey == true) Res.string.settings_new_recovery_key else Res.string.settings_create_recovery_key),
                    when (hasRecoveryKey) {
                        true -> stringResource(Res.string.settings_recovery_key_present)
                        false -> stringResource(Res.string.settings_recovery_key_absent)
                        null -> ""
                    },
                ) { confirmRegenerate = true }
                if (hasRecoveryKey == true) {
                    HorizontalDivider()
                    SettingRow(Icons.Outlined.KeyOff, stringResource(Res.string.settings_remove_recovery_key), stringResource(Res.string.settings_remove_recovery_key_hint)) {
                        confirmRemove = true
                    }
                }
            }
            ElevatedCard {
                SettingRow(
                    Icons.Outlined.Upload,
                    stringResource(Res.string.settings_export),
                    stringResource(Res.string.settings_export_hint),
                ) {
                    autoLock.pause()
                    createBackupFile(SecrtArchive.fileName(today().toString()))
                }
                HorizontalDivider()
                SettingRow(
                    Icons.Outlined.Download,
                    stringResource(Res.string.settings_import),
                    stringResource(Res.string.settings_import_hint),
                ) {
                    autoLock.pause()
                    pickBackupFile()
                }
                HorizontalDivider()
                CloudBackupRow(session)
            }
            LocalOpenDevices.current?.let { openDevices ->
                ElevatedCard {
                    SettingRow(Icons.Outlined.Devices, stringResource(Res.string.settings_devices), stringResource(Res.string.settings_devices_hint)) { openDevices() }
                }
            }
            ElevatedCard {
                SettingRow(Icons.Outlined.Timer, stringResource(Res.string.settings_auto_lock), settings.autoLock.description()) { choosingAutoLock = true }
                if (biometricAvailability != BiometricAvailability.Unsupported || biometricEnabled) {
                    HorizontalDivider()
                    SwitchRow(
                        Icons.Outlined.Fingerprint,
                        stringResource(Res.string.settings_fingerprint),
                        when {
                            biometricEnabled -> stringResource(Res.string.settings_fingerprint_on_hint)
                            biometricAvailability == BiometricAvailability.NoneEnrolled -> stringResource(Res.string.settings_fingerprint_none_enrolled)
                            else -> stringResource(Res.string.settings_fingerprint_off_hint)
                        },
                        checked = biometricEnabled,
                        enabled = !biometricBusy && (biometricEnabled || biometricAvailability == BiometricAvailability.Available),
                        onCheckedChange = ::setBiometricUnlock,
                    )
                }
                HorizontalDivider()
                SettingRow(Icons.Outlined.Lock, stringResource(Res.string.settings_lock_now), stringResource(Res.string.settings_lock_now_hint)) {
                    scope.launch { session.lock() }
                }
            }
            ElevatedCard {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                    headlineContent = { Text(stringResource(Res.string.settings_about)) },
                    supportingContent = { Text(stringResource(Res.string.settings_about_text)) },
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
            onChange = { current, new ->
                session.changePassword(current, new)
                snackbar.showSnackbar(text(Res.string.settings_password_changed).resolve())
            },
            onDismiss = { changingPassword = false },
        )
    }
    if (confirmRegenerate) {
        ConfirmDialog(
            title = stringResource(Res.string.settings_create_recovery_key_title),
            text = stringResource(
                if (hasRecoveryKey == true) Res.string.settings_create_recovery_key_replaces else Res.string.settings_create_recovery_key_once,
            ),
            confirmLabel = stringResource(Res.string.action_create),
            onConfirm = { scope.launch { shownKey = session.regenerateRecoveryKey(); hasRecoveryKey = true } },
            onDismiss = { confirmRegenerate = false },
        )
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(Res.string.settings_remove_recovery_key_title),
            text = stringResource(Res.string.settings_remove_recovery_key_text),
            confirmLabel = stringResource(Res.string.action_remove),
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
                    Text(stringResource(label))
                }
            },
            confirmButton = {},
        )
    }
    shownKey?.let { key ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(Res.string.settings_new_recovery_key_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.settings_new_recovery_key_text))
                    RecoveryKeyCard(key)
                }
            },
            confirmButton = { TextButton(onClick = { shownKey = null }) { Text(stringResource(Res.string.recovery_key_written)) } },
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

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun AutoLockTimeout.label(): String = stringResource(
    when (this) {
        AutoLockTimeout.Immediately -> Res.string.auto_lock_immediately
        AutoLockTimeout.OneMinute -> Res.string.auto_lock_one_minute
        AutoLockTimeout.FiveMinutes -> Res.string.auto_lock_five_minutes
        AutoLockTimeout.FifteenMinutes -> Res.string.auto_lock_fifteen_minutes
        AutoLockTimeout.OneHour -> Res.string.auto_lock_one_hour
        AutoLockTimeout.OnAppRestart -> Res.string.auto_lock_on_restart
    },
)

@Composable
private fun AutoLockTimeout.description(): String = when (this) {
    AutoLockTimeout.Immediately -> stringResource(Res.string.auto_lock_immediately_hint)
    AutoLockTimeout.OnAppRestart -> stringResource(Res.string.auto_lock_on_restart_hint)
    else -> stringResource(Res.string.auto_lock_in_background, label())
}

@Composable
private fun AutoLockDialog(current: AutoLockTimeout, onSelect: (AutoLockTimeout) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.auto_lock_title)) },
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
                        Text(option.label())
                    }
                }
                Text(
                    stringResource(Res.string.auto_lock_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(onChange: suspend (current: String, new: String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    val valid = current.isNotEmpty() && new.length >= MIN_PASSWORD_LENGTH && new == repeat

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.settings_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(current, { current = it }, stringResource(Res.string.change_password_current), isError = error != null)
                PasswordField(new, { new = it }, stringResource(Res.string.change_password_new, MIN_PASSWORD_LENGTH))
                PasswordField(repeat, { repeat = it }, stringResource(Res.string.change_password_repeat), isError = repeat.isNotEmpty() && repeat != new, imeAction = ImeAction.Done)
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
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
                        error = Res.string.change_password_wrong
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(if (busy) Res.string.change_password_saving else Res.string.action_change)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) } },
    )
}
