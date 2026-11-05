package io.github.igrvlhlb.secrt.ui.platform

import androidx.compose.runtime.Composable
import okio.Sink
import okio.Source

/** A file the user picked to read. It can be opened more than once (e.g. to retry a password). */
interface PickedDocument {
    val name: String

    fun openSource(): Source
}

/** A file the user chose to write. */
interface DocumentTarget {
    val name: String

    fun openSink(): Sink

    /** Removes the file, e.g. after a failed export. */
    fun delete()
}

/** Remembers a launcher for picking a file to read; [onResult] gets `null` if the user cancels. */
@Composable
expect fun rememberOpenDocument(onResult: (PickedDocument?) -> Unit): () -> Unit

/** Remembers a launcher for choosing where to save a new file, given a suggested name. */
@Composable
expect fun rememberCreateDocument(onResult: (DocumentTarget?) -> Unit): (suggestedName: String) -> Unit
