package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.igrvlhlb.secrt.lan.LanHost
import io.github.igrvlhlb.secrt.lan.LanSync
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.Peer
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.ui.components.readableWidth
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Paired devices and how to pair more. The screen being open is what makes this device listen for
 * the user's other devices (and show pairing codes); where it has a camera, it's also where other
 * devices' codes are scanned and syncs started.
 */
@Composable
fun DevicesScreen(lan: LanSync, session: AppSession, store: DiaryStore, onBack: () -> Unit) {
    val vm = viewModel { DevicesViewModel(lan, session, store, canScanCodes) }
    val peers by vm.peers.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var scanning by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Peer?>(null) }
    LaunchedEffect(vm) { vm.snackbar.collect { snackbar.showSnackbar(it) } }

    if (scanning) {
        ScanScreen(
            instructions = "Point the camera at the code on your other device (in secrt there: Settings → Devices → Show a code).",
            onScanned = { scanning = false; vm.pair(it) },
            onBack = { scanning = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().readableWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SyncSection(vm.host, canScan = canScanCodes, onShowCode = vm::showCode, onHideCode = vm::hideCode, onScan = { scanning = true })

            Text("Paired devices", style = MaterialTheme.typography.titleMedium)
            if (peers.isEmpty()) {
                Text(
                    if (canScanCodes) {
                        "None yet. Pair with secrt on your other devices to keep this diary in sync with them."
                    } else {
                        "None yet. Pair your phone with a code from above."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ElevatedCard {
                    peers.forEachIndexed { i, peer ->
                        if (i > 0) HorizontalDivider()
                        PeerRow(
                            peer = peer,
                            // Only the side that scanned knows where to find the other one.
                            canSync = peer.addresses.isNotEmpty(),
                            syncing = vm.syncing == peer.identityKey,
                            onSync = { vm.sync(peer) },
                            onForget = { forgetting = peer },
                        )
                    }
                }
            }
        }
    }

    // From a pairing started here, or from a device connecting to this one; one at a time either way.
    val hostQuestion by vm.host.question.collectAsStateWithLifecycle()
    vm.question?.let { question -> LanQuestionDialog(question, vm::answer) }
        ?: hostQuestion?.let { question -> LanQuestionDialog(question) { vm.host.answer(question, it) } }
    vm.progress?.takeIf { vm.question == null }?.let { label ->
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
    vm.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { vm.notice = null },
            title = { Text(notice.title) },
            text = { Text(notice.text) },
            confirmButton = { TextButton(onClick = { vm.notice = null }) { Text("OK") } },
        )
    }
    forgetting?.let { peer ->
        ConfirmDialog(
            title = "Forget ${peer.name}?",
            text = "It won't be able to sync with this device until you pair them again. Nothing is deleted from either diary.",
            confirmLabel = "Forget",
            destructive = true,
            onConfirm = { vm.forget(peer) },
            onDismiss = { forgetting = null },
        )
    }
}

/**
 * How this device pairs and syncs: it listens while the screen is open and shows codes for others
 * to scan; with [canScan], it also scans theirs.
 */
@Composable
private fun SyncSection(host: LanHost, canScan: Boolean, onShowCode: () -> Unit, onHideCode: () -> Unit, onScan: () -> Unit) {
    val status by host.status.collectAsStateWithLifecycle()
    val invite by host.invite.collectAsStateWithLifecycle()
    var codeShown by remember { mutableStateOf(false) }
    val listening = status is LanHost.Status.Listening
    ElevatedCard {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Wifi, contentDescription = null) },
            headlineContent = {
                Text(
                    when (status) {
                        LanHost.Status.Starting -> "Starting…"
                        is LanHost.Status.Listening -> "Ready to sync"
                        is LanHost.Status.Failed -> "Can't sync right now"
                    },
                )
            },
            supportingContent = {
                Text(
                    when (val s = status) {
                        LanHost.Status.Starting -> ""
                        is LanHost.Status.Listening if canScan ->
                            "Your diary syncs directly with secrt on your other devices, encrypted, without any server. " +
                                "To pair, scan the code another device shows, or show this one's code to a phone. While " +
                                "this screen is open, paired devices can also sync with this one. Both must be on the " +
                                "same Wi-Fi. This device: ${s.addresses.joinToString()}, port ${s.port}."
                        is LanHost.Status.Listening ->
                            "While this screen is open, your paired phone can sync with this computer: tap Sync now in " +
                                "secrt on the phone (Settings → Devices). Both must be on the same Wi-Fi. " +
                                "This computer: ${s.addresses.joinToString()}, port ${s.port}."
                        is LanHost.Status.Failed -> s.message
                    },
                )
            },
        )
        if (codeShown && listening) {
            HorizontalDivider()
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                PairingCodeCard(
                    invite = invite,
                    instructions = "In secrt on your phone, open Settings → Devices → Scan a code, and scan this one. A new " +
                        "phone can scan it from \"Set up from another device\" on its first screen to receive this diary, " +
                        "still encrypted, once you confirm here.",
                    onNewCode = onShowCode,
                    onCancel = { codeShown = false; onHideCode() },
                )
            }
        } else if (listening || canScan) {
            HorizontalDivider()
            FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canScan) {
                    Button(onClick = onScan) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan a code")
                    }
                }
                if (listening) {
                    val showCode = { codeShown = true; onShowCode() }
                    val content: @Composable () -> Unit = {
                        Icon(Icons.Outlined.QrCode2, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Show a code")
                    }
                    // Scanning is the usual way on a phone; on a computer, showing is the only one.
                    if (canScan) OutlinedButton(onClick = showCode) { content() } else Button(onClick = showCode) { content() }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, canSync: Boolean, syncing: Boolean, onSync: () -> Unit, onForget: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Outlined.Devices, contentDescription = null) },
        headlineContent = { Text(peer.name) },
        supportingContent = {
            val lastSync = peer.lastSyncAt?.let { "Last synced ${formatTime(it)}" } ?: "Not synced yet"
            Text(if (canSync) lastSync else "$lastSync. Syncs start on ${peer.name}.")
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canSync) {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(24.dp))
                    } else {
                        TextButton(onClick = onSync) {
                            Icon(Icons.Outlined.Sync, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Sync now")
                        }
                    }
                }
                IconButton(onClick = onForget, enabled = !syncing) { Icon(Icons.Outlined.Delete, contentDescription = "Forget ${peer.name}") }
            }
        },
    )
}

@Composable
internal fun ScanScreen(instructions: String, onScanned: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan the pairing code") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(instructions, modifier = Modifier.padding(16.dp))
            QrScanner(onScanned, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatTime(millis: Long): String {
    val elapsed = Clock.System.now().toEpochMilliseconds() - millis
    return when {
        elapsed < 60_000 -> "just now"
        elapsed < 3_600_000 -> "${elapsed / 60_000} min ago"
        else -> {
            val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
            "${time.date} ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
        }
    }
}
