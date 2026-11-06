package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.igrvlhlb.secrt.lan.LanHost
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** A QR code, black on white whatever the theme (scanners expect that), with a quiet zone around it. */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.aspectRatio(1f).background(Color.White).padding(16.dp).semantics { contentDescription = "Pairing QR code" }) {
        val cell = size.minDimension / modules.size
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, dark ->
                // Slightly oversized cells so no hairline gaps show between neighbours.
                if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** This device's pairing code with its countdown; once expired, a button for a new one. */
@Composable
internal fun PairingCodeCard(invite: LanHost.Invite?, instructions: String, onNewCode: () -> Unit, onCancel: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(invite) {
        while (true) {
            now = Clock.System.now().toEpochMilliseconds()
            delay(1_000)
        }
    }
    val remaining = invite?.let { (it.expiresAt - now) / 1000 }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (invite != null && remaining != null && remaining > 0) {
            QrCode(invite.link, Modifier.widthIn(max = 280.dp).size(280.dp))
            Text(instructions, textAlign = TextAlign.Center)
            Text(
                "Expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}. It works once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(if (invite == null) "No pairing code is shown." else "The pairing code expired.", textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onNewCode) { Text("New code") }
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
