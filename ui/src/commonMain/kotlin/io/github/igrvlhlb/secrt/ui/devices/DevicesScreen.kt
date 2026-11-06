package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhoneAndroid
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
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.Peer
import io.github.igrvlhlb.secrt.ui.components.ConfirmDialog
import io.github.igrvlhlb.secrt.ui.components.readableWidth
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Paired devices and how to pair more. On a computer, the screen being open is what makes it
 * listen for the user's phone; on a phone, it's where pairing codes are scanned and syncs started.
 */
@Composable
fun DevicesScreen(lan: LanSync, store: DiaryStore, onBack: () -> Unit) {
    val vm = viewModel { DevicesViewModel(lan, store, isComputer) }
    val peers by vm.peers.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var scanning by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Peer?>(null) }
    LaunchedEffect(vm) { vm.snackbar.collect { snackbar.showSnackbar(it) } }

    if (scanning) {
        ScanScreen(onScanned = { scanning = false; vm.pair(it) }, onBack = { scanning = false })
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
            val host = vm.host
            if (host != null) ComputerSection(host, onShowCode = vm::showCode, onHideCode = vm::hideCode) else PhoneSection(onPair = { scanning = true })

            Text("Paired devices", style = MaterialTheme.typography.titleMedium)
            if (peers.isEmpty()) {
                Text(
                    if (host != null) "None yet. Pair your phone with the code above." else "None yet. Pair with secrt on your computer to sync this diary with it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ElevatedCard {
                    peers.forEachIndexed { i, peer ->
                        if (i > 0) HorizontalDivider()
                        PeerRow(
                            peer = peer,
                            canSync = host == null,
                            syncing = vm.syncing == peer.identityKey,
                            onSync = { vm.sync(peer) },
                            onForget = { forgetting = peer },
                        )
                    }
                }
            }
        }
    }

    vm.progress?.let { label ->
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

@Composable
private fun ComputerSection(host: LanHost, onShowCode: () -> Unit, onHideCode: () -> Unit) {
    val status by host.status.collectAsStateWithLifecycle()
    val invite by host.invite.collectAsStateWithLifecycle()
    var codeShown by remember { mutableStateOf(false) }
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
                        is LanHost.Status.Listening ->
                            "While this screen is open, your paired phone can sync with this computer: tap Sync now in " +
                                "secrt on the phone (Settings → Devices). Both must be on the same Wi-Fi. " +
                                "This computer: ${s.addresses.joinToString()}, port ${s.port}."
                        is LanHost.Status.Failed -> s.message
                    },
                )
            },
        )
        if (status is LanHost.Status.Listening) {
            HorizontalDivider()
            if (codeShown) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    PairingCodeCard(
                        invite = invite,
                        instructions = "In secrt on your phone, open Settings → Devices → Pair with a computer, and scan this code.",
                        onNewCode = onShowCode,
                        onCancel = { codeShown = false; onHideCode() },
                    )
                }
            } else {
                Box(Modifier.fillMaxWidth().padding(16.dp)) {
                    Button(onClick = { codeShown = true; onShowCode() }) {
                        Icon(Icons.Outlined.QrCode2, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pair a phone")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneSection(onPair: () -> Unit) {
    ElevatedCard {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(Icons.Outlined.Wifi, contentDescription = null) },
            headlineContent = { Text("Sync over Wi-Fi") },
            supportingContent = {
                Text(
                    "Your diary syncs directly with secrt on your computer, encrypted, without any server. " +
                        "To pair, open Settings → Devices on the computer (or \"Set up from your phone\" on a new one) " +
                        "and scan the code it shows.",
                )
            },
        )
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = onPair) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Pair with a computer")
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, canSync: Boolean, syncing: Boolean, onSync: () -> Unit, onForget: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(if (canSync) Icons.Outlined.Computer else Icons.Outlined.PhoneAndroid, contentDescription = null) },
        headlineContent = { Text(peer.name) },
        supportingContent = { Text(peer.lastSyncAt?.let { "Last synced ${formatTime(it)}" } ?: "Not synced yet") },
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
private fun ScanScreen(onScanned: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan the pairing code") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Point the camera at the code on your computer's screen (Settings → Devices → Pair a phone).",
                modifier = Modifier.padding(16.dp),
            )
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
