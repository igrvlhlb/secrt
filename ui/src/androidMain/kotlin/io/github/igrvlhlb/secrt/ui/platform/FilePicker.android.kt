package io.github.igrvlhlb.secrt.ui.platform

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.igrvlhlb.secrt.storage.AttachmentTooLargeException
import io.github.igrvlhlb.secrt.storage.DiaryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberFilePicker(onResult: (Result<PickedFile>?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            currentOnResult(null)
        } else {
            scope.launch {
                currentOnResult(withContext(Dispatchers.IO) { runCatching { read(context, uri) } })
            }
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

private fun read(context: Context, uri: Uri): PickedFile {
    val resolver = context.contentResolver
    var name = "file"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            name = cursor.getString(0) ?: name
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    if (size > DiaryStore.MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException(DiaryStore.MAX_ATTACHMENT_BYTES)
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read $name")
    return PickedFile(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
}
