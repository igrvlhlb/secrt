package io.github.igrvlhlb.secrt.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

@Composable
actual fun rememberFilePicker(onResult: (Result<PickedFile>?) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return {
        val dialog = FileDialog(null as Frame?, "Attach a file", FileDialog.LOAD).apply { isVisible = true }
        val name = dialog.file
        currentOnResult(
            if (name == null) {
                null
            } else {
                runCatching {
                    val file = File(dialog.directory, name)
                    PickedFile(name, Files.probeContentType(file.toPath()) ?: "application/octet-stream", file.readBytes())
                }
            },
        )
    }
}
