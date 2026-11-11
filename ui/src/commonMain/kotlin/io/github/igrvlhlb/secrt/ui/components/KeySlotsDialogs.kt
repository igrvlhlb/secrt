package io.github.igrvlhlb.secrt.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.igrvlhlb.secrt.session.KeySlotsChange
import io.github.igrvlhlb.secrt.ui.resources.*
import io.github.igrvlhlb.secrt.ui.text.UiText
import io.github.igrvlhlb.secrt.ui.text.string
import io.github.igrvlhlb.secrt.ui.text.text
import org.jetbrains.compose.resources.stringResource

/**
 * Asks whether to take on a newer password (or recovery key) set on another device, which a sync
 * through [via] (another device's name, or the cloud backup) needs. Dismissing it counts as "no",
 * which cancels the sync.
 */
@Composable
fun AdoptKeySlotsDialog(change: KeySlotsChange, via: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(Res.string.key_slots_title)) },
        text = { Text(stringResource(Res.string.key_slots_text, change.where().string(), via)) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(Res.string.key_slots_sync)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(Res.string.key_slots_dont_sync)) } },
    )
}

/** What to tell the user once [change] was taken on (see [io.github.igrvlhlb.secrt.session.AppSession.keySlotsAdopted]). */
fun KeySlotsChange.adoptedMessage(): UiText = text(Res.string.key_slots_adopted, where())

private fun KeySlotsChange.where(): UiText {
    val time = UiText.DateTime(changedAt)
    return changedOn?.let { text(Res.string.key_slots_on_device, it, time) } ?: text(Res.string.key_slots_on_another_device, time)
}
