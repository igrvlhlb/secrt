package io.github.igrvlhlb.secrt.ui.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import okio.Sink
import okio.Source
import okio.sink
import okio.source

@Composable
actual fun rememberOpenDocument(onResult: (PickedDocument?) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        currentOnResult(uri?.let { AndroidDocument(resolver, it) })
    }
    // .secrt has no registered MIME type, so don't filter.
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
actual fun rememberCreateDocument(onResult: (DocumentTarget?) -> Unit): (suggestedName: String) -> Unit {
    val resolver = LocalContext.current.contentResolver
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        currentOnResult(uri?.let { AndroidDocument(resolver, it) })
    }
    return { name -> launcher.launch(name) }
}

private class AndroidDocument(private val resolver: ContentResolver, private val uri: Uri) : PickedDocument, DocumentTarget {
    override val name: String by lazy {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    }

    override fun openSource(): Source = requireNotNull(resolver.openInputStream(uri)) { "Couldn't open $name" }.source()

    // "wt" truncates, in case the user picked an existing file to overwrite.
    override fun openSink(): Sink = requireNotNull(resolver.openOutputStream(uri, "wt")) { "Couldn't write $name" }.sink()

    override fun delete() {
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
    }
}
