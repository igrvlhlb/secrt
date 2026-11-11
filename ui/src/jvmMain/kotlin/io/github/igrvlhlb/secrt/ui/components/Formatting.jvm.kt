package io.github.igrvlhlb.secrt.ui.components

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun formatPattern(dateTime: LocalDateTime, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(dateTime.toJavaLocalDateTime())
