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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.igrvlhlb.secrt.backup.BackupProgress
import io.github.igrvlhlb.secrt.backup.BackupResult
import io.github.igrvlhlb.secrt.backup.BackupTargetConfig
import io.github.igrvlhlb.secrt.backup.drive.GoogleSignIn
import io.github.igrvlhlb.secrt.ui.AutoLock
import io.github.igrvlhlb.secrt.ui.LocalAutoLock
import io.github.igrvlhlb.secrt.ui.auth.PasswordField
import io.github.igrvlhlb.secrt.ui.components.readableWidth
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.UiText
import io.github.igrvlhlb.secrt.ui.text.plural
import io.github.igrvlhlb.secrt.ui.text.string
import io.github.igrvlhlb.secrt.ui.text.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

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
                    navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back)) } },
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
internal fun ServerForm(fields: ServerFields, onChange: (ServerFields) -> Unit, enabled: Boolean, error: UiText?, actions: @Composable () -> Unit) {
    Text(
        stringResource(Res.string.backup_server_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = fields.url,
        onValueChange = { onChange(fields.copy(url = it)) },
        label = { Text(stringResource(Res.string.backup_server_address)) },
        placeholder = { Text("https://cloud.example.com") },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = fields.username,
        onValueChange = { onChange(fields.copy(username = it)) },
        label = { Text(stringResource(Res.string.backup_username)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordField(fields.password, { onChange(fields.copy(password = it)) }, stringResource(Res.string.backup_server_password), imeAction = ImeAction.Done)
    if (fields.insecure) {
        Notice(stringResource(Res.string.backup_insecure_address))
    }
    error?.let { Text(it.string(), color = MaterialTheme.colorScheme.error) }
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
            Text(progress.label().string())
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

internal fun BackupProgress.label(): UiText {
    val (plain, counted) = when (stage) {
        BackupProgress.Stage.Checking -> return text(Res.string.backup_checking)
        BackupProgress.Stage.Downloading -> Res.string.backup_downloading to Res.string.backup_downloading_count
        BackupProgress.Stage.DownloadingAttachments -> Res.string.backup_downloading_attachments to Res.string.backup_downloading_attachments_count
        BackupProgress.Stage.UploadingAttachments -> Res.string.backup_uploading_attachments to Res.string.backup_uploading_attachments_count
        BackupProgress.Stage.Uploading -> Res.string.backup_uploading to Res.string.backup_uploading_count
    }
    return if (total > 0) text(counted, done + 1, total) else text(plain)
}

internal fun BackupResult.summary(): UiText {
    val parts = buildList {
        if (downloaded > 0) add(plural(Res.plurals.backup_downloaded, downloaded))
        if (uploaded > 0) add(plural(Res.plurals.backup_uploaded, uploaded))
    }
    val done = when (parts.size) {
        0 -> text(Res.string.backup_up_to_date)
        1 -> text(Res.string.backup_summary_one, parts[0])
        else -> text(Res.string.backup_summary_two, parts[0], parts[1])
    }
    if (missingAttachments == 0) return done
    return UiText.Joined(listOf(done, plural(Res.plurals.backup_attachments_missing, missingAttachments)))
}

@Composable
internal fun LabeledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Small explanatory text under a button. */
@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Signs in to Google Drive, where the app supports it (see `SecrtApp`'s `google`). */
val LocalGoogleSignIn = staticCompositionLocalOf<GoogleSignIn?> { null }

/**
 * [LocalGoogleSignIn], with auto-lock paused while it works: on Android, Google's account picker
 * and consent screen put secrt in the background, which must not lock the diary.
 */
@Composable
internal fun rememberGoogleSignIn(): GoogleSignIn? {
    val google = LocalGoogleSignIn.current ?: return null
    val autoLock = LocalAutoLock.current
    return remember(google, autoLock) { PausingAutoLock(google, autoLock) }
}

private class PausingAutoLock(private val google: GoogleSignIn, private val autoLock: AutoLock) : GoogleSignIn {
    override suspend fun signIn() = paused { google.signIn() }

    override suspend fun accessToken(account: BackupTargetConfig.GoogleDrive, stale: String?) = paused { google.accessToken(account, stale) }

    private suspend fun <T> paused(block: suspend () -> T): T {
        // AutoLock is driven from the main thread.
        withContext(Dispatchers.Main) { autoLock.pause() }
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { autoLock.resume() }
        }
    }
}
