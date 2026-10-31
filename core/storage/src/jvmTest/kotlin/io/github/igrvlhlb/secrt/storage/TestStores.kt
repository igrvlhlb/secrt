package io.github.igrvlhlb.secrt.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.storage.db.SecrtDatabase
import io.github.igrvlhlb.secrt.vault.VaultManager
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File

/** A replica backed by a temporary SQLite file, with a wall clock the test controls. */
internal class TestReplica private constructor(val store: DiaryStore, val fileSystem: FakeFileSystem, val clock: TestClock) {
    companion object {
        suspend fun create(): TestReplica {
            val crypto = SodiumCrypto.create()
            val fileSystem = FakeFileSystem()
            val (keys, _) = VaultManager(fileSystem, "/vault".toPath(), crypto, Argon2Params.Insecure)
                .create("pw", withRecoveryKey = false)
            val file = File.createTempFile("secrt-test", ".db").apply { delete(); deleteOnExit() }
            val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", schema = SecrtDatabase.Schema)
            val clock = TestClock()
            val blobs = BlobStore(fileSystem, "/vault/blobs".toPath(), crypto, keys)
            return TestReplica(DiaryStore.open(driver, blobs, wallClock = clock::now), fileSystem, clock)
        }
    }
}

internal class TestClock(var millis: Long = 1_700_000_000_000) {
    fun now() = millis
    fun advanceMinutes(minutes: Int) {
        millis += minutes * 60_000L
    }
}

/** Two-way sync, as the LAN/backup transports will do it. */
internal suspend fun sync(a: DiaryStore, b: DiaryStore) {
    b.applyRemote(a.revisionsSince(b.versionVector()))
    a.applyRemote(b.revisionsSince(a.versionVector()))
}
