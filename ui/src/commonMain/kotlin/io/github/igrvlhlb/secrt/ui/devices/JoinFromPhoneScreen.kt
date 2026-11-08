package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.igrvlhlb.secrt.lan.LanHost
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.lan.PendingJoin
import io.github.igrvlhlb.secrt.ui.auth.CredentialScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * First run on a computer: shows a pairing code for the phone to scan; the phone sends its diary,
 * and the user opens it here with the diary's password. Listening stops when this screen goes away.
 */
@Composable
fun JoinFromPhoneScreen(lan: LanSync, onCancel: () -> Unit) {
    val pending by lan.pendingJoin.collectAsStateWithLifecycle()
    pending?.let { join ->
        OpenReceivedDiaryScreen(lan, join, onCancel)
        return
    }

    val host = remember { lan.host(joining = true) }
    val status by host.status.collectAsStateWithLifecycle()
    val invite by host.invite.collectAsStateWithLifecycle()
    // What the phone that scanned the code is doing: asking its user, or giving up.
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(host) {
        launch { host.run() }
        launch {
            host.events.collect { event ->
                when (event) {
                    is LanHost.Event.WaitingForAnswer -> note = "Confirm on ${event.peerName} to send the diary here."
                    is LanHost.Event.Failed -> note = event.message
                    else -> Unit
                }
            }
        }
        host.status.first { it !is LanHost.Status.Starting }
        host.showInvite()
    }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Set up from your phone", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            when (val s = status) {
                LanHost.Status.Starting -> Text("Starting…")
                is LanHost.Status.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                is LanHost.Status.Listening -> PairingCodeCard(
                    invite = invite,
                    instructions = "In secrt on your phone, open Settings → Devices → Scan a code, and scan this one. " +
                        "The phone sends its diary, still encrypted; then you type the diary's password here. " +
                        "Both must be on the same Wi-Fi.",
                    onNewCode = {
                        note = null
                        scope.launch { host.showInvite() }
                    },
                    onCancel = null,
                )
            }
            note?.let { Text(it, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
}

/**
 * The last step of setting up a device from another one, whichever way the diary came: it opens
 * with the diary's own password (or recovery key), as a backup file would.
 */
@Composable
internal fun OpenReceivedDiaryScreen(lan: LanSync, join: PendingJoin, onCancel: () -> Unit) {
    CredentialScreen(
        title = "Open your diary",
        passwordHint = "${join.peerName} sent its diary. Enter the diary's password to open it here.",
        recoveryHint = "${join.peerName} sent its diary. Enter the diary's recovery key to open it here.",
        submitLabel = "Open",
        onSubmit = { lan.completeJoin(it) },
        onCancel = {
            lan.discardJoin()
            onCancel()
        },
    )
}
