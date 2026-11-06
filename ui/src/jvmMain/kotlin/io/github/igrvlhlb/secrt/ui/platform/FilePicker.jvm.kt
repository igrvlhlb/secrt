package io.github.igrvlhlb.secrt.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.igrvlhlb.secrt.storage.AttachmentTooLargeException
import io.github.igrvlhlb.secrt.storage.DiaryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File
import java.nio.file.Files

@Composable
actual fun rememberFilePicker(onResult: (Result<PickedFile>?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    return {
        val file = chooseFile("Attach a file", FileDialog.LOAD)
        if (file == null) {
            currentOnResult(null)
        } else {
            // Off the UI thread: the file can be up to 20 MB.
            scope.launch { currentOnResult(withContext(Dispatchers.IO) { runCatching { read(file) } }) }
        }
    }
}

private fun read(file: File): PickedFile {
    if (file.length() > DiaryStore.MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(DiaryStore.MAX_ATTACHMENT_BYTES)
    return PickedFile(file.name, Files.probeContentType(file.toPath()) ?: "application/octet-stream", file.readBytes())
}
