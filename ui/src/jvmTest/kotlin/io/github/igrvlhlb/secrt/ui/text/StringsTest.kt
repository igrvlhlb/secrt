package io.github.igrvlhlb.secrt.ui.text

import io.github.igrvlhlb.secrt.archive.CorruptArchiveException
import io.github.igrvlhlb.secrt.archive.DifferentVaultException
import io.github.igrvlhlb.secrt.archive.NotAnArchiveException
import io.github.igrvlhlb.secrt.archive.UnsupportedArchiveException
import io.github.igrvlhlb.secrt.backup.BackupAuthException
import io.github.igrvlhlb.secrt.backup.BackupChangedException
import io.github.igrvlhlb.secrt.backup.BackupNetworkException
import io.github.igrvlhlb.secrt.backup.BackupServerException
import io.github.igrvlhlb.secrt.backup.InvalidServerAddressException
import io.github.igrvlhlb.secrt.backup.KeySlotsChangeDeclinedException
import io.github.igrvlhlb.secrt.backup.NoBackupFoundException
import io.github.igrvlhlb.secrt.backup.drive.GoogleAuthException
import io.github.igrvlhlb.secrt.backup.drive.GoogleDriveException
import io.github.igrvlhlb.secrt.crypto.DecryptionException
import io.github.igrvlhlb.secrt.lan.LanException
import io.github.igrvlhlb.secrt.lan.LanHost
import io.github.igrvlhlb.secrt.session.BiometricException
import io.github.igrvlhlb.secrt.storage.AttachmentTooLargeException
import io.github.igrvlhlb.secrt.storage.StoreClosedException
import io.github.igrvlhlb.secrt.ui.components.formatPattern
import io.github.igrvlhlb.secrt.ui.resources.Res
import io.github.igrvlhlb.secrt.ui.resources.backup_summary_two
import io.github.igrvlhlb.secrt.ui.resources.backup_uploaded
import io.github.igrvlhlb.secrt.ui.resources.credential_wrong_password
import io.github.igrvlhlb.secrt.ui.resources.format_date_long
import io.github.igrvlhlb.secrt.ui.resources.settings_imported
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.jetbrains.compose.resources.getString
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every text has a translation in every language, with the same placeholders; and the errors the
 * core modules throw get the same English text in the UI as their (English) messages.
 */
class StringsTest {

    private val defaultLocale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun everyTextIsTranslatedWithTheSamePlaceholders() {
        val base = File("src/commonMain/composeResources")
        val english = texts(File(base, "values/strings.xml"))
        val languages = base.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
        assertTrue(languages.isNotEmpty())
        for (dir in languages) assertSameTexts(english, texts(File(dir, "strings.xml")), dir.name)
    }

    @Test
    fun androidTextsAreTranslatedToo() {
        val res = File("../app/android/src/main/res")
        val english = texts(File(res, "values/strings.xml"))
        for (dir in res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }.orEmpty()) {
            assertSameTexts(english, texts(File(dir, "strings.xml")), "app/android ${dir.name}")
        }
    }

    @Test
    fun coreErrorsReadTheSameInEnglish() = runBlocking {
        Locale.setDefault(Locale.US)
        val errors: List<Exception> = listOf(
            InvalidCredentialsException(),
            StoreClosedException(),
            AttachmentTooLargeException(20L * 1024 * 1024),
            DecryptionException(),
            NotAnArchiveException(),
            CorruptArchiveException("missing vault.key"),
            UnsupportedArchiveException(9),
            DifferentVaultException(),
            BackupAuthException(),
            BackupNetworkException(IOException("timeout")),
            BackupNetworkException(IOException()),
            BackupServerException(BackupServerException.Problem.FileTooLarge, "a/b.seg"),
            BackupServerException(BackupServerException.Problem.NoWebDavFolder, "https://x/dav/"),
            BackupServerException(BackupServerException.Problem.NotAllowed, "a/", BackupServerException.Action.CreateFolder),
            BackupServerException(BackupServerException.Problem.OutOfSpace),
            BackupServerException(BackupServerException.Problem.Failed, "a/b", BackupServerException.Action.Upload, 500),
            InvalidServerAddressException(),
            BackupChangedException(),
            KeySlotsChangeDeclinedException(),
            NoBackupFoundException(),
            LanException.Unreachable("Phone"),
            LanException.ConnectionLost("Phone"),
            LanException.Refused(),
            LanException.WrongCode(),
            LanException.UnknownDevice("Phone"),
            LanException.UnknownDevice(null),
            LanException.DifferentDiary(),
            LanException.NoDiary(),
            LanException.Outdated("Phone"),
            LanException.Outdated(),
            LanException.Declined(LanException.Declined.Reason.NothingSent, "Phone"),
            LanException.Declined(LanException.Declined.Reason.KeepsPassword, "Phone"),
            LanException.DeclinedThere(LanException.DeclinedThere.Reason.SendDeclined, "Phone"),
            LanException.DeclinedThere(LanException.DeclinedThere.Reason.SendDeclined, null),
            LanException.DeclinedThere(LanException.DeclinedThere.Reason.NoAnswer, "Phone"),
            LanException.DeclinedThere(LanException.DeclinedThere.Reason.KeepsPassword, "Phone"),
            LanException.DeclinedThere(LanException.DeclinedThere.Reason.KeepsPassword, null),
            LanException.Locked(),
            LanException.Busy(),
            LanException.Protocol("bad signature"),
            LanException.InvalidCode(),
        ) + GoogleAuthException.Reason.entries.map { GoogleAuthException(it, "403") } +
            (GoogleAuthException.Reason.entries - GoogleAuthException.Reason.SignInRefused - GoogleAuthException.Reason.ServiceFailed)
                .map { GoogleAuthException(it) } +
            GoogleDriveException.Problem.entries.flatMap { problem ->
                GoogleDriveException.Action.entries.flatMap { action ->
                    listOf(
                        GoogleDriveException(problem, action, "secrt/log/a.seg", 500),
                        GoogleDriveException(problem, action, "secrt/log/a.seg", 403, detail = "The user has not granted access"),
                    )
                }
            } + BiometricException.KeyInvalidated.Reason.entries.map { BiometricException.KeyInvalidated(it) } +
            (BiometricException.Failed.Reason.entries - BiometricException.Failed.Reason.System).map { BiometricException.Failed(it) }
        for (error in errors) {
            val text = error.toUiText()
            assertTrue(text !is UiText.Raw, "${error::class.simpleName} has no text of its own")
            assertEquals(error.message, text.resolve(), "${error::class.simpleName}")
        }

        val hostFailures = LanHost.Event.Failed.Problem.entries.map { LanHost.Event.Failed(it, "Phone", LanException.Busy()) } +
            LanHost.Status.Failed.Problem.entries.map { LanHost.Status.Failed(it) }
        for (failure in hostFailures) {
            val (text, message) = when (failure) {
                is LanHost.Event.Failed -> failure.toUiText() to failure.message
                is LanHost.Status.Failed -> failure.toUiText() to failure.message
                else -> error("unexpected")
            }
            assertEquals(message, text.resolve())
        }
    }

    @Test
    fun unknownErrorsShowTheirOwnMessage() = runBlocking {
        Locale.setDefault(Locale.US)
        assertEquals("Disk full", IOException("Disk full").toUiText().resolve())
        assertEquals("Something went wrong", IOException().toUiText().resolve())
    }

    @Test
    fun portugueseTextsAndPlurals() = runBlocking {
        Locale.setDefault(Locale.forLanguageTag("pt-BR"))
        assertEquals("Senha incorreta", text(Res.string.credential_wrong_password).resolve())
        assertEquals("1 alteração importada", plural(Res.plurals.settings_imported, 1).resolve())
        assertEquals("3 alterações importadas", plural(Res.plurals.settings_imported, 3).resolve())
        assertEquals(
            "2 alterações baixadas, 1 alteração enviada.",
            text(Res.string.backup_summary_two, raw("2 alterações baixadas"), plural(Res.plurals.backup_uploaded, 1)).resolve(),
        )
        assertEquals("ter., 10 de nov. de 2026", formatPattern(LocalDateTime(2026, 11, 10, 0, 0), getString(Res.string.format_date_long)))
    }

    @Test
    fun englishDatesKeepTheirFormat() = runBlocking {
        Locale.setDefault(Locale.US)
        assertEquals("Tue, 10 Nov 2026", formatPattern(LocalDateTime(2026, 11, 10, 0, 0), getString(Res.string.format_date_long)))
    }

    /** A strings.xml as name → its texts (one, or one per plural form). */
    private fun texts(file: File): Map<String, Map<String, String>> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val result = mutableMapOf<String, Map<String, String>>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            assertTrue(name !in result, "$name appears twice in $file")
            result[name] = when (node.tagName) {
                "string" -> mapOf("" to node.textContent)
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    (0 until items.length).associate { j -> (items.item(j) as Element).let { it.getAttribute("quantity") to it.textContent } }
                }
                else -> continue
            }
        }
        return result
    }

    private fun assertSameTexts(english: Map<String, Map<String, String>>, translated: Map<String, Map<String, String>>, language: String) {
        assertEquals(emptySet(), english.keys - translated.keys, "missing in $language")
        assertEquals(emptySet(), translated.keys - english.keys, "only in $language")
        for ((name, forms) in english) {
            val expected = forms.values.flatMap(::placeholders).toSet()
            for ((quantity, value) in translated.getValue(name)) {
                assertEquals(expected, placeholders(value).toSet(), "placeholders of $name ($quantity) in $language")
            }
            if (forms.size > 1) assertTrue("other" in translated.getValue(name), "$name in $language has no \"other\" form")
        }
    }

    private fun placeholders(text: String) = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toList()
}
