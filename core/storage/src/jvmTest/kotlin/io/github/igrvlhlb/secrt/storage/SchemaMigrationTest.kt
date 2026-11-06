package io.github.igrvlhlb.secrt.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.model.Hlc
import io.github.igrvlhlb.secrt.storage.db.SecrtDatabase
import io.github.igrvlhlb.secrt.vault.VaultManager
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opens databases created by older app versions with the current schema, the way the app does it
 * on upgrade. The `.db` snapshots are the schema of each released version (see docs/architecture.md),
 * so these tests keep exercising old versions as the schema moves on. The Gradle migration check
 * compares table definitions; this runs the actual `Schema.migrate` code and reads the data back.
 */
class SchemaMigrationTest {

    private val snapshots: List<Pair<Long, File>> =
        File("src/commonMain/sqldelight/databases").listFiles { f -> f.extension == "db" }.orEmpty()
            .map { it.nameWithoutExtension.toLong() to it }
            .sortedBy { it.first }

    /** A copy of the snapshot of [version], marked the way a real database of that version is. */
    private fun databaseAt(version: Long): File {
        val snapshot = snapshots.first { it.first == version }.second
        val file = File.createTempFile("secrt-v$version", ".db").apply { deleteOnExit() }
        snapshot.copyTo(file, overwrite = true)
        // Snapshots are written without a user_version; drivers read it to decide what to migrate.
        withRawConnection(file) { it.execute(null, "PRAGMA user_version = $version", 0) }
        return file
    }

    private fun <T> withRawConnection(file: File, block: (SqlDriver) -> T): T =
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use(block)

    private fun SqlDriver.userVersion(): Long =
        executeQuery(null, "PRAGMA user_version", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

    /** Tables and indexes as SQLite sees them, minus formatting differences in the stored SQL. */
    private fun SqlDriver.schemaObjects(): Set<String> =
        executeQuery(
            null,
            "SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name",
            { c ->
                val out = mutableSetOf<String>()
                while (c.next().value) {
                    val sql = c.getString(2).orEmpty().lines().joinToString(" ") { it.substringBefore("--").trim() }
                    out += "${c.getString(0)} ${c.getString(1)}: ${sql.replace(Regex("\\s+"), " ")}"
                }
                QueryResult.Value(out)
            },
            0,
        ).value

    @Test
    fun snapshotOfEveryVersionUpToCurrentExists() {
        val versions = snapshots.map { it.first }
        assertEquals(1L, versions.firstOrNull(), "the version-1 baseline must be kept")
        assertEquals((1L..SecrtDatabase.Schema.version).toList(), versions.filter { it <= SecrtDatabase.Schema.version })
    }

    @Test
    fun everyOldVersionMigratesToTheCurrentSchema() {
        val fresh = File.createTempFile("secrt-fresh", ".db").apply { delete(); deleteOnExit() }
        val expected = JdbcSqliteDriver("jdbc:sqlite:${fresh.absolutePath}", schema = SecrtDatabase.Schema).use { it.schemaObjects() }

        for ((version, _) in snapshots) {
            val file = databaseAt(version)
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", schema = SecrtDatabase.Schema).use { driver ->
                assertEquals(SecrtDatabase.Schema.version, driver.userVersion(), "user_version after migrating from $version")
                assertEquals(expected, driver.schemaObjects(), "schema after migrating from $version")
            }
        }
    }

    @Test
    fun diaryWrittenByVersion1OpensAfterUpgrade() = runTest {
        val file = databaseAt(1)
        // Rows written with the version-1 columns, exactly as that version stores them. Never
        // update these statements when the schema changes: they stand for data already on phones.
        val hlc = Hlc(1_700_000_000_000, 0, "device-v1").encode()
        val payload = """{"title":"Old entry","body":"Written by v1","diaryDate":"2026-01-01","folderId":null,""" +
            """"labelIds":[],"attachments":[],"createdAt":1700000000000}"""
        withRawConnection(file) { db ->
            db.execute(null, "INSERT INTO meta(key, value) VALUES ('device_id', 'device-v1')", 0)
            db.execute(
                null,
                "INSERT INTO revision(rev_id, record_type, record_id, device_id, seq, hlc, deleted, payload) " +
                    "VALUES ('rev-1', 'ENTRY', 'entry-1', 'device-v1', 1, '$hlc', 0, '$payload')",
                0,
            )
            db.execute(
                null,
                "INSERT INTO entry(id, folder_id, effective_folder_id, title, body, diary_date, created_at, updated_at, conflict) " +
                    "VALUES ('entry-1', NULL, NULL, 'Old entry', 'Written by v1', '2026-01-01', 1700000000000, 1700000000000, NULL)",
                0,
            )
        }

        val crypto = SodiumCrypto.create()
        val fileSystem = FakeFileSystem()
        val (keys, _) = VaultManager(fileSystem, "/vault".toPath(), crypto, Argon2Params.Insecure).create("pw", withRecoveryKey = false)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", schema = SecrtDatabase.Schema)
        val store = DiaryStore.open(driver, BlobStore(fileSystem, "/vault/blobs".toPath(), crypto, keys))

        assertEquals("device-v1", store.deviceId)
        val entry = store.getEntry("entry-1")
        assertEquals("Old entry", entry?.title)
        assertEquals("Written by v1", entry?.body)
        assertEquals(listOf("rev-1"), store.revisionsSince(emptyMap()).map { it.id })

        // And the upgraded database takes new writes.
        store.saveEntry("entry-2", EntryDraft("New entry", "after upgrade", LocalDate(2026, 2, 1), null, emptySet(), emptyList()))
        assertTrue(store.getEntry("entry-2") != null)
        store.close()
    }
}
