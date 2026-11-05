package io.github.igrvlhlb.secrt.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberOpenDocument(onResult: (PickedDocument?) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return { currentOnResult(chooseFile("Open a backup", FileDialog.LOAD, null)?.let(::FileDocument)) }
}

@Composable
actual fun rememberCreateDocument(onResult: (DocumentTarget?) -> Unit): (suggestedName: String) -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return { name -> currentOnResult(chooseFile("Save backup", FileDialog.SAVE, name)?.let(::FileDocument)) }
}

private fun chooseFile(title: String, mode: Int, suggestedName: String?): File? {
    val dialog = FileDialog(null as Frame?, title, mode).apply {
        file = suggestedName
        isVisible = true
    }
    return dialog.file?.let { File(dialog.directory, it) }
}

private class FileDocument(private val file: File) : PickedDocument, DocumentTarget {
    override val name: String = file.name

    override fun openSource(): Source = file.source()

    override fun openSink(): Sink = file.sink()

    override fun delete() {
        file.delete()
    }
}
