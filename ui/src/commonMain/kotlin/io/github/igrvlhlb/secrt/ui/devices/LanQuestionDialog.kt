package io.github.igrvlhlb.secrt.ui.devices

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.igrvlhlb.secrt.lan.LanQuestion

/**
 * Asks the user what a pairing or sync needs to know, while the other device waits. Dismissing it
 * counts as "no": nothing is sent without a clear yes.
 */
@Composable
internal fun LanQuestionDialog(question: LanQuestion, onAnswer: (Boolean) -> Unit) {
    val (title, text, yes, no) = when (question) {
        is LanQuestion.SendDiary if question.peerAsked -> Wording(
            "Send your diary?",
            "\"${question.peerName}\" wants a copy of your diary. Send it?\n\nIt goes encrypted, and opens there " +
                "only with this diary's password or recovery key. Only say yes if it's your own device, " +
                "the one that just scanned this code.",
            "Send",
            "Don't send",
        )
        is LanQuestion.SendDiary -> Wording(
            "Send your diary?",
            "Send a copy of your encrypted diary to \"${question.peerName}\"? Only do this with your own device.\n\n" +
                "It opens there only with this diary's password or recovery key.",
            "Send",
            "Don't send",
        )
    }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(yes) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(no) } },
    )
}

private data class Wording(val title: String, val text: String, val yes: String, val no: String)
