package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.igrvlhlb.secrt.lan.LanQuestion
import io.github.igrvlhlb.secrt.ui.components.AdoptKeySlotsDialog
import io.github.igrvlhlb.secrt.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Asks the user what a pairing or sync needs to know, while the other device waits. Dismissing it
 * counts as "no": nothing is sent without a clear yes.
 */
@Composable
internal fun LanQuestionDialog(question: LanQuestion, onAnswer: (Boolean) -> Unit) {
    when (question) {
        is LanQuestion.SendDiary -> SendDiaryDialog(question, onAnswer)
        is LanQuestion.AdoptKeySlots -> AdoptKeySlotsDialog(question.change, via = stringResource(Res.string.key_slots_via_device, question.peerName), onAnswer)
    }
}

@Composable
private fun SendDiaryDialog(question: LanQuestion.SendDiary, onAnswer: (Boolean) -> Unit) {
    val text = stringResource(if (question.peerAsked) Res.string.send_diary_asked else Res.string.send_diary_offer, question.peerName)
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(stringResource(Res.string.send_diary_title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(stringResource(Res.string.send_diary_send)) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(stringResource(Res.string.send_diary_dont_send)) } },
    )
}
