package io.github.igrvlhlb.secrt.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import io.github.igrvlhlb.secrt.archive.SecrtArchive
import io.github.igrvlhlb.secrt.ui.resources.Res
import io.github.igrvlhlb.secrt.ui.resources.settings_open_backup_title
import io.github.igrvlhlb.secrt.ui.resources.settings_save_backup_title
import okio.Sink
import okio.Source
import okio.sink
import okio.source
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File

@Composable
actual fun rememberOpenDocument(onResult: (PickedDocument?) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val title = stringResource(Res.string.settings_open_backup_title)
    return {
        val file = chooseFile(title, FileDialog.LOAD, extension = SecrtArchive.EXTENSION)
        currentOnResult(file?.let(::FileDocument))
    }
}

@Composable
actual fun rememberCreateDocument(onResult: (DocumentTarget?) -> Unit): (suggestedName: String) -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    val title = stringResource(Res.string.settings_save_backup_title)
    return { name -> currentOnResult(chooseFile(title, FileDialog.SAVE, suggestedName = name)?.let(::FileDocument)) }
}

/**
 * Shows the native file dialog (on macOS, the system's open/save panel) and blocks until the user
 * closes it. It's modal, so the Compose window can't be used meanwhile, but it keeps repainting.
 *
 * @param extension only offer files with this extension (without the dot) when opening. Windows
 *   ignores the filter, so there it's merely a hint.
 */
internal fun chooseFile(title: String, mode: Int, suggestedName: String? = null, extension: String? = null): File? {
    // Owned by the app's window, so it opens in front of it rather than wherever the OS likes.
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
        ?: Frame.getFrames().firstOrNull { it.isVisible }
    val dialog = FileDialog(owner, title, mode).apply {
        file = suggestedName
        if (extension != null) setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
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
