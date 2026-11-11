package io.github.igrvlhlb.secrt.ui.text

import androidx.compose.runtime.Composable
import io.github.igrvlhlb.secrt.ui.components.formatDateTime
import io.github.igrvlhlb.secrt.ui.resources.Res
import io.github.igrvlhlb.secrt.ui.resources.format_date_time
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text for the user, kept as a string resource and its arguments until it is shown, so it comes
 * out in the language of that moment. ViewModels and [toUiText] produce these; screens show them
 * with [string] (or [resolve], outside composition, e.g. for a snackbar).
 *
 * Arguments may be [UiText] themselves; they are resolved first.
 */
sealed interface UiText {
    data class Resource(val res: StringResource, val args: List<Any> = emptyList()) : UiText

    /** [count] picks the plural form; [args] default to the count itself. */
    data class Plural(val res: PluralStringResource, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Text that needs no translation: names, or a message already in the user's language. */
    data class Raw(val text: String) : UiText

    /** A moment in this device's time zone, e.g. "Sun, 8 Nov 2026, 10:05". */
    data class DateTime(val epochMillis: Long) : UiText

    /** [parts] one after the other, separated by spaces. */
    data class Joined(val parts: List<UiText>) : UiText
}

fun text(res: StringResource, vararg args: Any): UiText = UiText.Resource(res, args.toList())

fun plural(res: PluralStringResource, count: Int, vararg args: Any): UiText =
    UiText.Plural(res, count, if (args.isEmpty()) listOf(count) else args.toList())

fun raw(text: String): UiText = UiText.Raw(text)

operator fun UiText.plus(other: UiText): UiText = UiText.Joined(listOf(this, other))

@Composable
fun UiText.string(): String = when (this) {
    is UiText.Resource -> stringResource(res, *args.map { it.argString() }.toTypedArray())
    is UiText.Plural -> pluralStringResource(res, count, *args.map { it.argString() }.toTypedArray())
    is UiText.Raw -> text
    is UiText.DateTime -> formatDateTime(epochMillis, stringResource(Res.string.format_date_time))
    is UiText.Joined -> parts.map { it.string() }.joinToString(" ")
}

/** Like [string], outside composition. */
suspend fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> getString(res, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Plural -> getPluralString(res, count, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Raw -> text
    is UiText.DateTime -> formatDateTime(epochMillis, getString(Res.string.format_date_time))
    is UiText.Joined -> parts.map { it.resolve() }.joinToString(" ")
}

@Composable
private fun Any.argString(): Any = if (this is UiText) string() else this

private suspend fun Any.resolveArg(): Any = if (this is UiText) resolve() else this
