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
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.UiText
import io.github.igrvlhlb.secrt.ui.text.string
import io.github.igrvlhlb.secrt.ui.text.text
import io.github.igrvlhlb.secrt.ui.text.toUiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

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
    var note by remember { mutableStateOf<UiText?>(null) }
    LaunchedEffect(host) {
        launch { host.run() }
        launch {
            host.events.collect { event ->
                when (event) {
                    is LanHost.Event.WaitingForAnswer -> note = text(Res.string.join_confirm_there, event.peerName)
                    is LanHost.Event.Failed -> note = event.toUiText()
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
            Text(stringResource(Res.string.setup_from_phone), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            when (val s = status) {
                LanHost.Status.Starting -> Text(stringResource(Res.string.devices_starting))
                is LanHost.Status.Failed -> Text(s.toUiText().string(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                is LanHost.Status.Listening -> PairingCodeCard(
                    invite = invite,
                    instructions = stringResource(Res.string.join_instructions),
                    onNewCode = {
                        note = null
                        scope.launch { host.showInvite() }
                    },
                    onCancel = null,
                )
            }
            note?.let { Text(it.string(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.action_back)) }
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
        title = stringResource(Res.string.open_received_title),
        passwordHint = stringResource(Res.string.open_received_password_hint, join.peerName),
        recoveryHint = stringResource(Res.string.open_received_recovery_hint, join.peerName),
        submitLabel = stringResource(Res.string.action_open),
        onSubmit = { lan.completeJoin(it) },
        onCancel = {
            lan.discardJoin()
            onCancel()
        },
    )
}
