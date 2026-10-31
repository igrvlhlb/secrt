package io.github.igrvlhlb.secrt.ui.platform

import androidx.compose.runtime.Composable

class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/**
 * Remembers a launcher for the platform's file picker. [onResult] receives `null` when the user
 * cancels, or a failure (e.g. the file is too large or unreadable).
 */
@Composable
expect fun rememberFilePicker(onResult: (Result<PickedFile>?) -> Unit): () -> Unit
