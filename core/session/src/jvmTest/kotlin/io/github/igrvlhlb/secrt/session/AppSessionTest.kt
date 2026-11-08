package io.github.igrvlhlb.secrt.session

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.igrvlhlb.secrt.archive.DifferentVaultException
import io.github.igrvlhlb.secrt.archive.NotAnArchiveException
import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.storage.DiaryStore
import io.github.igrvlhlb.secrt.storage.DriverFactory
import io.github.igrvlhlb.secrt.storage.EntryDraft
import io.github.igrvlhlb.secrt.storage.StoreClosedException
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import io.github.igrvlhlb.secrt.vault.InvalidVaultHeaderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSessionTest {

    /** One device: its own file system and database file. */
    private class Device(val name: String? = null) {
        val fileSystem = FakeFileSystem()
        val dbFile: File = File.createTempFile("secrt-session", ".db").apply { delete(); deleteOnExit() }

        // Test-only: an unencrypted JDBC database. Encryption at rest is covered on-device by SQLCipher.
        private val driverFactory = object : DriverFactory {
            override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, key: ByteArray): SqlDriver =
                JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", schema = schema)

            override fun delete() {
                dbFile.delete()
            }
        }

        fun session() = AppSession(
            fileSystem = fileSystem,
            vaultDirectory = "/vault".toPath(),
            driverFactory = driverFactory,
            cryptoProvider = { SodiumCrypto.create() },
            ioDispatcher = Dispatchers.Default,
            argon2Params = Argon2Params.Insecure,
            deviceName = { name },
        )
    }

    private val device = Device()

    private fun draft(title: String) = EntryDraft(title, "body", LocalDate(2026, 1, 1), null, emptySet(), emptyList())

    private val AppSession.store: DiaryStore get() = assertIs<SessionState.Unlocked>(state.value).store

    private suspend fun AppSession.export(): Buffer = Buffer().also { exportTo(it) }

    private suspend fun DiaryStore.titles() = observeEntries().first().map { it.title }.toSet()

    @Test
    fun fullLifecycle() = runTest {
        val session = device.session()
        assertEquals(SessionState.NoVault, session.state.value)

        val recoveryKey = session.create("pw", withRecoveryKey = true)
        assertNotNull(recoveryKey)
        val store = session.store
        store.saveEntry("e1", draft("t"))

        session.lock()
        assertEquals(SessionState.Locked, session.state.value)
        assertFailsWith<StoreClosedException> { store.getEntry("e1") }

        // A fresh process sees the existing vault as locked.
        val restarted = device.session()
        assertEquals(SessionState.Locked, restarted.state.value)
        assertFailsWith<InvalidCredentialsException> { restarted.unlock("wrong") }
        restarted.unlock("pw")
        assertEquals("t", restarted.store.getEntry("e1")?.title)
    }

    @Test
    fun recoveryKeyUnlockAllowsPasswordResetWithoutTheOldPassword() = runTest {
        val session = device.session()
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
        val session = device.session()
        session.create("pw", withRecoveryKey = false)

        assertFailsWith<InvalidCredentialsException> { session.changePassword("nope", "new") }
        session.changePassword("pw", "new")
        session.lock()
        session.unlock("new")
    }

    @Test
    fun exportThenRestoreOnAnotherDevice() = runTest {
        val phone = device.session()
        phone.create("pw", withRecoveryKey = false)
        val photo = phone.store.addAttachment("pixels".encodeToByteArray(), "photo.png", "image/png")
        phone.store.saveEntry("e1", draft("Lisbon").copy(attachments = listOf(photo)))
        val folder = phone.store.createFolder("Trips", null)
        val backup = phone.export()

        val laptop = Device().session()
        laptop.restore(backup, Credential.Password("pw"))

        val restored = laptop.store
        assertEquals("Lisbon", restored.getEntry("e1")?.title)
        assertContentEquals("pixels".encodeToByteArray(), restored.readAttachment(photo.blobId))
        assertEquals(listOf(folder), restored.observeFolders().first().map { it.id })
        assertFalse(assertIs<SessionState.Unlocked>(laptop.state.value).viaRecoveryKey)
        // The restored copy is a new device of the same diary: it opens with the same password.
        laptop.lock()
        laptop.unlock("pw")
        assertTrue(laptop.store.deviceId != phone.store.deviceId)
    }

    @Test
    fun restoreWithRecoveryKeyAsksForANewPassword() = runTest {
        val phone = device.session()
        val recoveryKey = phone.create("pw", withRecoveryKey = true)!!
        phone.store.saveEntry("e1", draft("hello"))

        val laptop = Device().session()
        laptop.restore(phone.export(), Credential.RecoveryKey(recoveryKey))

        assertTrue(assertIs<SessionState.Unlocked>(laptop.state.value).viaRecoveryKey)
        assertEquals("hello", laptop.store.getEntry("e1")?.title)
    }

    @Test
    fun failedRestoreLeavesNothingBehind() = runTest {
        val phone = device.session()
        phone.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("e1", draft("hello"))
        val backup = phone.export().readByteArray()

        val other = Device()
        val laptop = other.session()
        assertFailsWith<InvalidCredentialsException> { laptop.restore(Buffer().write(backup), Credential.Password("wrong")) }
        assertFailsWith<NotAnArchiveException> { laptop.restore(Buffer().writeUtf8("not a backup"), Credential.Password("pw")) }

        assertEquals(SessionState.NoVault, laptop.state.value)
        assertEquals(SessionState.NoVault, other.session().state.value)
        assertFalse(other.dbFile.exists())

        // And a later attempt still works.
        laptop.restore(Buffer().write(backup), Credential.Password("pw"))
        assertEquals("hello", laptop.store.getEntry("e1")?.title)
    }

    @Test
    fun importMergesBackupsOfTheSameDiaryBothWays() = runTest {
        val phone = device.session()
        phone.create("pw", withRecoveryKey = false)
        phone.store.saveEntry("shared", draft("Shared"))
        val laptop = Device().session()
        laptop.restore(phone.export(), Credential.Password("pw"))

        phone.store.saveEntry("p", draft("Written on the phone"))
        laptop.store.saveEntry("l", draft("Written on the laptop"))

        assertEquals(1, laptop.importFrom(phone.export()))
        assertEquals(1, phone.importFrom(laptop.export()))
        val expected = setOf("Shared", "Written on the phone", "Written on the laptop")
        assertEquals(expected, phone.store.titles())
        assertEquals(expected, laptop.store.titles())

        // Importing the same backup again changes nothing.
        assertEquals(0, phone.importFrom(laptop.export()))
    }

    @Test
    fun importRejectsBackupsOfAnotherDiary() = runTest {
        val mine = device.session()
        mine.create("pw", withRecoveryKey = false)
        val theirs = Device().session()
        theirs.create("pw", withRecoveryKey = false)
        theirs.store.saveEntry("x", draft("not mine"))

        assertFailsWith<DifferentVaultException> { mine.importFrom(theirs.export()) }
        assertEquals(emptySet(), mine.store.titles())
    }

    /** The phone and a laptop restored from it: one diary, one data key, each with its own vault.key. */
    private suspend fun phoneAndLaptop(withRecoveryKey: Boolean = false): Pair<AppSession, AppSession> {
        val phone = Device("Phone").session()
        phone.create("pw", withRecoveryKey)
        phone.store.saveEntry("e1", draft("hello"))
        val laptop = Device("Laptop").session()
        laptop.restore(phone.export(), Credential.Password("pw"))
        return phone to laptop
    }

    @Test
    fun theNewestPasswordWinsAndTakesOverOnTheNextUnlock() = runTest {
        val (phone, laptop) = phoneAndLaptop()
        // Neither changed anything yet (and neither header is stamped): nothing to do.
        assertEquals(KeySlotsComparison.Same, phone.compareKeySlots(laptop.vaultKeyJson()))

        laptop.changePassword("pw", "new")
        assertEquals(KeySlotsComparison.NewerHere, laptop.compareKeySlots(phone.vaultKeyJson()))
        val change = assertIs<KeySlotsComparison.NewerThere>(phone.compareKeySlots(laptop.vaultKeyJson())).change
        assertEquals("Laptop", change.changedOn)
        assertTrue(change.changedAt > 0)

        assertTrue(phone.adoptKeySlots(change))
        assertEquals(change, phone.keySlotsAdopted.value)
        assertEquals(KeySlotsComparison.Same, phone.compareKeySlots(laptop.vaultKeyJson()))
        // Nothing scary: the diary stays open.
        assertEquals("hello", phone.store.getEntry("e1")?.title)
        phone.acknowledgeKeySlotsAdopted()
        assertNull(phone.keySlotsAdopted.value)

        phone.lock()
        assertFailsWith<InvalidCredentialsException> { phone.unlock("pw") }
        phone.unlock("new")
        assertEquals("hello", phone.store.getEntry("e1")?.title)

        // Later, the phone changes it again: now the laptop is behind.
        phone.changePassword("new", "newer")
        val back = assertIs<KeySlotsComparison.NewerThere>(laptop.compareKeySlots(phone.vaultKeyJson())).change
        assertEquals("Phone", back.changedOn)
        assertTrue(back.changedAt >= change.changedAt)
        assertTrue(laptop.adoptKeySlots(back))
        laptop.lock()
        laptop.unlock("newer")
    }

    @Test
    fun recoveryKeyChangesTravelTheSameWay() = runTest {
        val (phone, laptop) = phoneAndLaptop(withRecoveryKey = true)
        val newKey = phone.regenerateRecoveryKey()
        laptop.adoptKeySlots(assertIs<KeySlotsComparison.NewerThere>(laptop.compareKeySlots(phone.vaultKeyJson())).change)
        laptop.lock()
        laptop.unlockWithRecoveryKey(newKey)
        assertTrue(laptop.hasRecoveryKey())

        phone.removeRecoveryKey()
        laptop.adoptKeySlots(assertIs<KeySlotsComparison.NewerThere>(laptop.compareKeySlots(phone.vaultKeyJson())).change)
        assertFalse(laptop.hasRecoveryKey())
        laptop.lock()
        assertFailsWith<InvalidCredentialsException> { laptop.unlockWithRecoveryKey(newKey) }
        laptop.unlock("pw")
    }

    @Test
    fun aChangeIsNotAdoptedOverANewerOneMadeMeanwhile() = runTest {
        val (phone, laptop) = phoneAndLaptop()
        laptop.changePassword("pw", "from the laptop")
        val change = assertIs<KeySlotsComparison.NewerThere>(phone.compareKeySlots(laptop.vaultKeyJson())).change
        // While the user was deciding, they changed the password on the phone too (a moment later).
        Thread.sleep(5)
        phone.changePassword("pw", "from the phone")

        assertFalse(phone.adoptKeySlots(change))
        assertNull(phone.keySlotsAdopted.value)
        phone.lock()
        phone.unlock("from the phone")
        assertEquals(KeySlotsComparison.NewerHere, phone.compareKeySlots(laptop.vaultKeyJson()))
    }

    @Test
    fun keySlotsOfAnotherDiaryAreRefused() = runTest {
        val (phone, _) = phoneAndLaptop()
        val stranger = Device("Stranger").session()
        stranger.create("pw", withRecoveryKey = false)
        stranger.changePassword("pw", "theirs")

        assertFailsWith<InvalidVaultHeaderException> { phone.compareKeySlots(stranger.vaultKeyJson()) }
        assertFailsWith<InvalidVaultHeaderException> { phone.compareKeySlots("{}") }
        phone.lock()
        phone.unlock("pw")
    }
}
