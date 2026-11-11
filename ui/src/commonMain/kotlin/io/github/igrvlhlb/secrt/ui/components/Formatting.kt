package io.github.igrvlhlb.secrt.ui.components

import androidx.compose.runtime.Composable
import io.github.igrvlhlb.secrt.model.Folder
import io.github.igrvlhlb.secrt.ui.resources.Res
import io.github.igrvlhlb.secrt.ui.resources.format_date_long
import io.github.igrvlhlb.secrt.ui.resources.format_month_year
import io.github.igrvlhlb.secrt.ui.resources.format_weekday_short
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

/**
 * [dateTime] formatted with a `java.time` [pattern] (each language has its own, in the string
 * resources), with month and weekday names in the device's language.
 */
internal expect fun formatPattern(dateTime: LocalDateTime, pattern: String): String

/** Sentence case: "ter." becomes "Ter.", while "Tue" stays as is. */
private fun String.capitalized() = replaceFirstChar { it.uppercase() }

/** E.g. "Tue". */
@Composable
fun LocalDate.weekdayShort(): String =
    formatPattern(atTime(0, 0), stringResource(Res.string.format_weekday_short)).removeSuffix(".").capitalized()

/** E.g. "November 2026". */
@Composable
fun LocalDate.monthYear(): String = formatPattern(atTime(0, 0), stringResource(Res.string.format_month_year)).capitalized()

/** E.g. "Tue, 10 Nov 2026". */
@Composable
fun LocalDate.longFormat(): String = formatPattern(atTime(0, 0), stringResource(Res.string.format_date_long)).capitalized()

/**
 * E.g. "Sun, 8 Nov 2026, 10:05" with [pattern] `format_date_time`, in this device's time zone.
 * Not capitalized: it usually goes mid-sentence.
 */
fun formatDateTime(epochMillis: Long, pattern: String): String =
    formatPattern(Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault()), pattern)

/** Material date pickers work in UTC milliseconds. */
fun LocalDate.toPickerMillis(): Long = atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

fun localDateFromPickerMillis(millis: Long): LocalDate =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
}

private val markdownLink = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val markdownSyntax = Regex("""(^|\n)\s{0,3}(#{1,6}|>|[-*+]|\d+\.)\s+|[*_`~]+""")

/** Plain-text preview of a Markdown body. */
fun stripMarkdown(markdown: String): String =
    markdown.replace(markdownLink, "$1").replace(markdownSyntax, "$1").replace(Regex("\\s+"), " ").trim()

/** Colors offered for labels (ARGB). */
val LabelPalette: List<Long> = listOf(
    0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF7986CB,
    0xFF4FC3F7, 0xFF4DB6AC, 0xFF81C784, 0xFFFFD54F,
    0xFFFFB74D, 0xFFA1887F, 0xFF90A4AE,
)

/** Folders in display order (depth-first, by name) with their depth. */
fun flattenFolders(folders: List<Folder>): List<Pair<Folder, Int>> {
    val children = folders.groupBy { it.parentId }
    val result = mutableListOf<Pair<Folder, Int>>()
    fun visit(parentId: String?, depth: Int) {
        children[parentId].orEmpty().sortedBy { it.name.lowercase() }.forEach {
            result += it to depth
            visit(it.id, depth + 1)
        }
    }
    visit(null, 0)
    return result
}

fun descendantsOf(folderId: String, folders: List<Folder>): Set<String> {
    val children = folders.groupBy { it.parentId }
    val result = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(folderId))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        children[id].orEmpty().forEach { if (result.add(it.id)) queue += it.id }
    }
    return result
}

/** Folders from the root down to [folderId], inclusive. */
fun pathTo(folderId: String?, folders: List<Folder>): List<Folder> {
    val byId = folders.associateBy { it.id }
    val path = mutableListOf<Folder>()
    var current = folderId?.let(byId::get)
    while (current != null && current !in path) {
        path.add(0, current)
        current = current.parentId?.let(byId::get)
    }
    return path
}
