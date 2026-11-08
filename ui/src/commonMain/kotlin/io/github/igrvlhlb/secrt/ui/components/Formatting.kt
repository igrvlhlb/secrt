package io.github.igrvlhlb.secrt.ui.components

import io.github.igrvlhlb.secrt.model.Folder
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

fun today(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())

private fun String.titleCase() = lowercase().replaceFirstChar { it.uppercase() }

fun LocalDate.weekdayShort(): String = dayOfWeek.name.titleCase().take(3)

fun LocalDate.monthYear(): String = "${month.name.titleCase()} $year"

fun LocalDate.longFormat(): String = "${weekdayShort()}, $day ${month.name.titleCase().take(3)} $year"

/** E.g. "Sun, 8 Nov 2026, 10:05", in this device's time zone. */
fun formatDateTime(epochMillis: Long): String {
    val time = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${time.date.longFormat()}, ${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
}

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
