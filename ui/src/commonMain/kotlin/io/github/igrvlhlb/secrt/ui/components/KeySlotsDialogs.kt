package io.github.igrvlhlb.secrt.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.igrvlhlb.secrt.session.KeySlotsChange

/**
 * Asks whether to take on a newer password (or recovery key) set on another device, which a sync
 * through [via] (another device's name, or the cloud backup) needs. Dismissing it counts as "no",
 * which cancels the sync.
 */
@Composable
fun AdoptKeySlotsDialog(change: KeySlotsChange, via: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Use the newer password?") },
        text = {
            Text(
                "Your diary's password or recovery key was changed ${change.where()}. To sync with $via, this " +
                    "device takes that change on: from the next time you unlock it, it opens with the password " +
                    "set there, not the current one. Your diary stays open now, and fingerprint unlock keeps " +
                    "working.\n\nIf you don't recognize this change, don't sync.",
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Sync") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Don't sync") } },
    )
}

/** What to tell the user once [change] was taken on (see [io.github.igrvlhlb.secrt.session.AppSession.keySlotsAdopted]). */
fun KeySlotsChange.adoptedMessage(): String =
    "From your next unlock, this device opens with the password set ${where()}."

private fun KeySlotsChange.where(): String = "on ${changedOn?.let { "\"$it\"" } ?: "another device"} (${formatDateTime(changedAt)})"
