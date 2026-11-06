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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.igrvlhlb.secrt.lan.LanHost
import io.github.igrvlhlb.secrt.lan.LanSync
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
    val join = pending
    if (join != null) {
        CredentialScreen(
            title = "Open your diary",
            passwordHint = "${join.peerName} sent its diary. Enter the diary's password to open it on this computer.",
            recoveryHint = "${join.peerName} sent its diary. Enter the diary's recovery key to open it on this computer.",
            submitLabel = "Open",
            onSubmit = { lan.completeJoin(it) },
            onCancel = {
                lan.discardJoin()
                onCancel()
            },
        )
        return
    }

    val host = remember { lan.host(joining = true) }
    val status by host.status.collectAsStateWithLifecycle()
    val invite by host.invite.collectAsStateWithLifecycle()
    LaunchedEffect(host) {
        launch { host.run() }
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
                    instructions = "In secrt on your phone, open Settings → Devices → Pair with a computer, and scan this code. " +
                        "The phone sends its diary, still encrypted; then you type the diary's password here. " +
                        "Both must be on the same Wi-Fi.",
                    onNewCode = { scope.launch { host.showInvite() } },
                    onCancel = null,
                )
            }
            TextButton(onClick = onCancel) { Text("Back") }
        }
    }
}
