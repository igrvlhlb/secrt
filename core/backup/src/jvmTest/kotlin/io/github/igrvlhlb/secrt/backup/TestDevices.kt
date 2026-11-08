package io.github.igrvlhlb.secrt.backup

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.session.AppSession
import io.github.igrvlhlb.secrt.session.SessionState
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.DriverFactory
import io.github.igrvlhlb.secrt.storage.EntryDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import kotlin.test.assertIs

/** One device: its own file system and database file (as in AppSessionTest). */
class Device(val name: String? = null) {
    val fileSystem = FakeFileSystem()
    val dbFile: File = File.createTempFile("secrt-backup", ".db").apply { delete(); deleteOnExit() }

    // Test-only: an unencrypted JDBC database. Encryption at rest is covered by the storage tests.
    private val driverFactory = object : DriverFactory {
        override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, key: ByteArray): SqlDriver =
            JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", schema = schema)

        override fun delete() {
            dbFile.delete()
        }
    }

    val session = AppSession(
        fileSystem = fileSystem,
        vaultDirectory = "/vault".toPath(),
        driverFactory = driverFactory,
        cryptoProvider = { SodiumCrypto.create() },
        ioDispatcher = Dispatchers.Default,
        argon2Params = Argon2Params.Insecure,
        deviceName = { name },
    )

    val backup = CloudBackup(session)

    val store: DiaryStore get() = assertIs<SessionState.Unlocked>(session.state.value).store

    /** Anything restore or create may have left on disk. */
    val leftovers: List<String>
        get() = fileSystem.listRecursively("/".toPath()).filter { fileSystem.metadata(it).isRegularFile }.map { it.toString() }.toList() +
            listOfNotNull(dbFile.takeIf { it.exists() }?.name)
}

fun draft(title: String, body: String = "body") = EntryDraft(title, body, LocalDate(2026, 1, 1), null, emptySet(), emptyList())

suspend fun DiaryStore.titles() = observeEntries().first().map { it.title }.toSet()

/** A [BackupTarget] in memory, standing in for a server. */
class MemoryTarget(override val id: String = "memory") : BackupTarget {
    val files = sortedMapOf<String, ByteArray>()
    var puts = 0

    override suspend fun list(directory: String): List<String> =
        files.keys.filter { it.startsWith(directory) }
            .map { it.removePrefix(directory) }
            .map { if ('/' in it) it.substringBefore('/') + "/" else it }
            .distinct()

    override suspend fun get(path: String): ByteArray? = files[path]?.copyOf()

    override suspend fun put(path: String, bytes: ByteArray) {
        puts++
        files[path] = bytes.copyOf()
    }

    override suspend fun delete(path: String) {
        files.remove(path)
    }
}
