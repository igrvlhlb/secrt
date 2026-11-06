package io.github.igrvlhlb.secrt.session

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.storage.DriverFactory
import io.github.igrvlhlb.secrt.storage.EntryDraft
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for the Android Keystore + BiometricPrompt: [next] decides how the next prompt ends. */
private class FakeBiometricKeyStore : BiometricKeyStore {
    var stored: ByteArray? = null
    var next: (() -> Unit)? = null

    override fun availability() = BiometricAvailability.Available

    override fun hasKey() = stored != null

    override suspend fun store(dataKey: ByteArray) {
        prompt()
        stored = dataKey.copyOf()
    }

    override suspend fun retrieve(): ByteArray {
        prompt()
        return requireNotNull(stored).copyOf()
    }

    override fun delete() {
        stored = null
    }

    private fun prompt() = next?.also { next = null }?.invoke() ?: Unit
}

class BiometricUnlockTest {

    private val fileSystem = FakeFileSystem()
    private val biometrics = FakeBiometricKeyStore()
    private val dbFile: File = File.createTempFile("secrt-bio", ".db").apply { delete(); deleteOnExit() }

    private val driverFactory = object : DriverFactory {
        override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, key: ByteArray): SqlDriver =
            JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", schema = schema)

        override fun delete() {
            dbFile.delete()
        }
    }

    /** A new process on the same device. */
    private fun session() = AppSession(
        fileSystem = fileSystem,
        vaultDirectory = "/vault".toPath(),
        driverFactory = driverFactory,
        cryptoProvider = { SodiumCrypto.create() },
        ioDispatcher = Dispatchers.Default,
        argon2Params = Argon2Params.Insecure,
        biometrics = biometrics,
    )

    private val AppSession.unlocked get() = assertIs<SessionState.Unlocked>(state.value)

    @Test
    fun offByDefaultThenUnlocksWithTheFingerprint() = runTest {
        val session = session()
        session.create("pw", withRecoveryKey = false)
        assertFalse(session.biometricUnlockEnabled.value)
        session.unlocked.store.saveEntry("e1", EntryDraft("hello", "", LocalDate(2026, 1, 1), null, emptySet(), emptyList()))

        session.enableBiometricUnlock()
        assertTrue(session.biometricUnlockEnabled.value)
        session.lock()

        val restarted = session()
        assertTrue(restarted.biometricUnlockEnabled.value)
        restarted.unlockWithBiometrics()
        assertEquals("hello", restarted.unlocked.store.getEntry("e1")?.title)
        assertFalse(restarted.unlocked.viaRecoveryKey)

        // The data key doesn't change with the password, so the fingerprint keeps working.
        restarted.changePassword("pw", "new password")
        restarted.lock()
        restarted.unlockWithBiometrics()
        restarted.unlocked
    }

    @Test
    fun cancellingThePromptLeavesItOff() = runTest {
        val session = session()
        session.create("pw", withRecoveryKey = false)

        biometrics.next = { throw BiometricException.Cancelled() }
        assertFailsWith<BiometricException.Cancelled> { session.enableBiometricUnlock() }

        assertFalse(session.biometricUnlockEnabled.value)
        assertNull(biometrics.stored)
    }

    @Test
    fun invalidatedKeyTurnsTheFeatureOff() = runTest {
        val session = session()
        session.create("pw", withRecoveryKey = false)
        session.enableBiometricUnlock()
        session.lock()

        biometrics.next = { throw BiometricException.KeyInvalidated("new fingerprint") }
        assertFailsWith<BiometricException.KeyInvalidated> { session.unlockWithBiometrics() }

        assertFalse(session.biometricUnlockEnabled.value)
        assertEquals(SessionState.Locked, session.state.value)
        session.unlock("pw")
    }

    @Test
    fun keyOfAnotherDiaryIsRefusedAndForgotten() = runTest {
        val mine = session()
        mine.create("pw", withRecoveryKey = false)
        mine.lock()
        val other = VaultManager(fileSystem, "/other".toPath(), SodiumCrypto.create(), Argon2Params.Insecure)
        biometrics.stored = other.exportDataKey(other.create("pw", withRecoveryKey = false).first)

        assertFailsWith<BiometricException.KeyInvalidated> { mine.unlockWithBiometrics() }
        assertEquals(SessionState.Locked, mine.state.value)
        assertNull(biometrics.stored)
    }

    @Test
    fun newDiaryOrRestoreClearsLeftoverBiometricKeys() = runTest {
        val first = session()
        first.create("pw", withRecoveryKey = false)
        first.enableBiometricUnlock()
        val backup = Buffer().also { first.exportTo(it) }
        first.lock()

        // The app's data is wiped except for a stale wrapped key (or the vault is recreated).
        fileSystem.delete("/vault/vault.key".toPath())
        val fresh = session()
        assertTrue(biometrics.hasKey())
        fresh.create("pw2", withRecoveryKey = false)
        assertNull(biometrics.stored)
        assertFalse(fresh.biometricUnlockEnabled.value)
        fresh.enableBiometricUnlock()
        fresh.lock()

        fileSystem.delete("/vault/vault.key".toPath())
        val restoring = session()
        restoring.restore(backup, Credential.Password("pw"))
        assertNull(biometrics.stored)
        assertFalse(restoring.biometricUnlockEnabled.value)
    }

    @Test
    fun disablingDeletesTheKey() = runTest {
        val session = session()
        session.create("pw", withRecoveryKey = false)
        session.enableBiometricUnlock()

        session.disableBiometricUnlock()

        assertFalse(session.biometricUnlockEnabled.value)
        assertNull(biometrics.stored)
    }
}
