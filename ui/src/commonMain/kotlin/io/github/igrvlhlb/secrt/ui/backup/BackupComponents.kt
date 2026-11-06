package io.github.igrvlhlb.secrt.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.igrvlhlb.secrt.backup.BackupProgress
import io.github.igrvlhlb.secrt.backup.BackupResult
import io.github.igrvlhlb.secrt.ui.auth.PasswordField
import io.github.igrvlhlb.secrt.ui.components.longFormat
import io.github.igrvlhlb.secrt.ui.components.readableWidth
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * A screen over the current one. The backup screens are dialogs rather than navigation routes so
 * they stay self-contained: the settings tab and the welcome screen each only add one entry point.
 */
@Composable
internal fun FullScreenDialog(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.readableWidth().fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { content() }
            }
        }
    }
}

/** What the user typed to reach a WebDAV server. Not saved until it connects. */
internal data class ServerFields(val url: String = "", val username: String = "", val password: String = "") {
    val complete: Boolean get() = url.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    val insecure: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)
}

/**
 * Server address, username and password, with a note on what each means. [actions] go below
 * (connect, test…).
 */
@Composable
internal fun ServerForm(fields: ServerFields, onChange: (ServerFields) -> Unit, enabled: Boolean, error: String?, actions: @Composable () -> Unit) {
    Text(
        "A WebDAV folder on your own server: Nextcloud, a NAS, or any WebDAV host. For Nextcloud, the " +
            "server address is enough; use an app password (Settings › Security).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = fields.url,
        onValueChange = { onChange(fields.copy(url = it)) },
        label = { Text("Server address") },
        placeholder = { Text("https://cloud.example.com") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = fields.username,
        onValueChange = { onChange(fields.copy(username = it)) },
        label = { Text("Username") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(fields.password, { onChange(fields.copy(password = it)) }, "Password or app password", imeAction = ImeAction.Done)
    if (fields.insecure) {
        Notice(
            "This address doesn't use https: the server password travels unprotected. The diary itself " +
                "stays encrypted, but only use http on a network you trust.",
        )
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    actions()
}

@Composable
internal fun Notice(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun ProgressCard(progress: BackupProgress) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(progress.label())
            if (progress.total > 0) {
                LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(text)
    }
}

@Composable
internal fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(text) }
}

/** Remembers [ServerFields] across the form's recompositions (not saved state: it holds a password). */
@Composable
internal fun rememberServerFields(initial: ServerFields = ServerFields()) = remember { mutableStateOf(initial) }

internal fun BackupProgress.label(): String {
    val count = if (total > 0) " (${done + 1} of $total)" else ""
    return when (stage) {
        BackupProgress.Stage.Checking -> "Checking the backup…"
        BackupProgress.Stage.Downloading -> "Downloading changes$count…"
        BackupProgress.Stage.DownloadingAttachments -> "Downloading attachments$count…"
        BackupProgress.Stage.UploadingAttachments -> "Uploading attachments$count…"
        BackupProgress.Stage.Uploading -> "Uploading changes$count…"
    }
}

internal fun BackupResult.summary(): String {
    val parts = buildList {
        if (downloaded > 0) add("${changes(downloaded)} downloaded")
        if (uploaded > 0) add("${changes(uploaded)} uploaded")
    }
    val missing = if (missingAttachments > 0) " ${changes(missingAttachments, "attachment")} missing on the server." else ""
    return (if (parts.isEmpty()) "Everything was already up to date." else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ".") + missing
}

private fun changes(n: Int, noun: String = "change") = if (n == 1) "1 $noun" else "$n ${noun}s"

internal fun formatTime(epochMillis: Long): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.date.longFormat()}, ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

@Composable
internal fun LabeledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
