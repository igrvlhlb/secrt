package io.github.igrvlhlb.secrt.storage

import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.storage.db.SecrtDatabase
import io.github.igrvlhlb.secrt.vault.VaultKeys
import io.github.igrvlhlb.secrt.vault.VaultManager
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlCipherDriverFactoryTest {

    private val dir: File = Files.createTempDirectory("secrt-db").toFile()
    private val factory = SqlCipherDriverFactory(File(dir, "secrt.db"))
    private val fileSystem = FakeFileSystem()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private suspend fun vaultKeys(): VaultKeys =
        VaultManager(fileSystem, "/vault".toPath(), SodiumCrypto.create(), Argon2Params.Insecure).create("pw", withRecoveryKey = false).first

    private suspend fun open(key: ByteArray, keys: VaultKeys): DiaryStore {
        val blobs = BlobStore(fileSystem, "/vault/blobs".toPath(), SodiumCrypto.create(), keys)
        return DiaryStore.open(factory.create(SecrtDatabase.Schema, key), blobs)
    }

    private fun draft(title: String, body: String) = EntryDraft(title, body, LocalDate(2026, 11, 6), null, emptySet(), emptyList())

    @Test
    fun dataRoundTripsAndTheFileIsEncrypted() = runTest {
        val keys = vaultKeys()
        val store = open(keys.databaseKey, keys)
        store.saveEntry("e1", draft("Lisbon", "The tram up to the castle"))
        store.close()

        val bytes = File(dir, "secrt.db").readBytes()
        assertTrue(bytes.isNotEmpty())
        assertFalse(bytes.decodeToString(endIndex = 16).startsWith("SQLite format 3"), "SQLite header in clear")
        val text = bytes.decodeToString()
        for (plaintext in listOf("Lisbon", "tram up to the castle", "CREATE TABLE", "revision")) {
            assertFalse(plaintext in text, "\"$plaintext\" found in the database file")
        }

        val reopened = open(keys.databaseKey, keys)
        assertEquals("The tram up to the castle", reopened.getEntry("e1")?.body)
        reopened.close()
    }

    @Test
    fun aWrongKeyCannotOpenTheDatabase() = runTest {
        val keys = vaultKeys()
        open(keys.databaseKey, keys).close()

        assertFailsWith<Exception> { factory.create(SecrtDatabase.Schema, Random.nextBytes(32)) }
    }

    @Test
    fun deleteRemovesTheFile() = runTest {
        val keys = vaultKeys()
        open(keys.databaseKey, keys).close()
        assertTrue(File(dir, "secrt.db").exists())

        factory.delete()
        assertEquals(emptyList(), dir.list()!!.toList())
    }
}
