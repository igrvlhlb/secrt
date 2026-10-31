package io.github.igrvlhlb.secrt.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.storage.DriverFactory
import io.github.igrvlhlb.secrt.storage.EntryDraft
import io.github.igrvlhlb.secrt.storage.StoreClosedException
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppSessionTest {

    private val fileSystem = FakeFileSystem()
    private val dbFile = File.createTempFile("secrt-session", ".db").apply { delete(); deleteOnExit() }

    // Test-only: an unencrypted JDBC database. Encryption at rest is covered on-device by SQLCipher.
    private val driverFactory = DriverFactory { schema, _ ->
        JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", schema = schema)
    }

    private fun session() = AppSession(
        fileSystem = fileSystem,
        vaultDirectory = "/vault".toPath(),
        driverFactory = driverFactory,
        cryptoProvider = { SodiumCrypto.create() },
        ioDispatcher = Dispatchers.Default,
        argon2Params = Argon2Params.Insecure,
    )

    @Test
    fun fullLifecycle() = runTest {
        val session = session()
        assertEquals(SessionState.NoVault, session.state.value)

        val recoveryKey = session.create("pw", withRecoveryKey = true)
        assertNotNull(recoveryKey)
        val store = assertIs<SessionState.Unlocked>(session.state.value).store
        store.saveEntry("e1", EntryDraft("t", "b", LocalDate(2026, 1, 1), null, emptySet(), emptyList()))

        session.lock()
        assertEquals(SessionState.Locked, session.state.value)
        assertFailsWith<StoreClosedException> { store.getEntry("e1") }

        // A fresh process sees the existing vault as locked.
        val restarted = session()
        assertEquals(SessionState.Locked, restarted.state.value)
        assertFailsWith<InvalidCredentialsException> { restarted.unlock("wrong") }
        restarted.unlock("pw")
        assertEquals("t", assertIs<SessionState.Unlocked>(restarted.state.value).store.getEntry("e1")?.title)
    }

    @Test
    fun recoveryKeyUnlockAllowsPasswordResetWithoutTheOldPassword() = runTest {
        val session = session()
        val recoveryKey = session.create("forgotten", withRecoveryKey = true)!!
        session.lock()

        session.unlockWithRecoveryKey(recoveryKey)
        assertTrue(assertIs<SessionState.Unlocked>(session.state.value).viaRecoveryKey)
        session.changePassword(currentPassword = null, newPassword = "new")
        session.lock()

        session.unlock("new")
        assertIs<SessionState.Unlocked>(session.state.value)
    }

    @Test
    fun changingPasswordNormallyRequiresTheCurrentOne() = runTest {
        val session = session()
        session.create("pw", withRecoveryKey = false)

        assertFailsWith<InvalidCredentialsException> { session.changePassword("nope", "new") }
        session.changePassword("pw", "new")
        session.lock()
        session.unlock("new")
    }
}
