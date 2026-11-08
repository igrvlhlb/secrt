package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.igrvlhlb.secrt.lan.LanQuestion
import io.github.igrvlhlb.secrt.ui.components.AdoptKeySlotsDialog

/**
 * Asks the user what a pairing or sync needs to know, while the other device waits. Dismissing it
 * counts as "no": nothing is sent without a clear yes.
 */
@Composable
internal fun LanQuestionDialog(question: LanQuestion, onAnswer: (Boolean) -> Unit) {
    when (question) {
        is LanQuestion.SendDiary -> SendDiaryDialog(question, onAnswer)
        is LanQuestion.AdoptKeySlots -> AdoptKeySlotsDialog(question.change, via = "\"${question.peerName}\"", onAnswer)
    }
}

@Composable
private fun SendDiaryDialog(question: LanQuestion.SendDiary, onAnswer: (Boolean) -> Unit) {
    val text = if (question.peerAsked) {
        "\"${question.peerName}\" wants a copy of your diary. Send it?\n\nIt goes encrypted, and opens there " +
            "only with this diary's password or recovery key. Only say yes if it's your own device, " +
            "the one that just scanned this code."
    } else {
        "Send a copy of your encrypted diary to \"${question.peerName}\"? Only do this with your own device.\n\n" +
            "It opens there only with this diary's password or recovery key."
    }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text("Send your diary?") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Don't send") } },
    )
}
