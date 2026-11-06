package io.github.igrvlhlb.secrt.session

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsStoreTest {

    private val fileSystem = FakeFileSystem()
    private val file = "/app/vault/settings.json".toPath()

    @Test
    fun defaultsUntilChangedThenPersisted() {
        val store = AppSettingsStore(fileSystem, file)
        assertEquals(AutoLockTimeout.OneMinute, store.settings.value.autoLock)

        store.update { it.copy(autoLock = AutoLockTimeout.FifteenMinutes) }

        assertEquals(AutoLockTimeout.FifteenMinutes, store.settings.value.autoLock)
        assertEquals(AutoLockTimeout.FifteenMinutes, AppSettingsStore(fileSystem, file).settings.value.autoLock)
        assertEquals(listOf(file), fileSystem.list(file.parent!!))
    }

    @Test
    fun unreadableFileMeansDefaults() {
        fileSystem.createDirectories(file.parent!!)
        fileSystem.write(file) { writeUtf8("{ garbage") }

        assertEquals(AppSettings(), AppSettingsStore(fileSystem, file).settings.value)
    }

    @Test
    fun valuesFromANewerVersionFallBackToDefaults() {
        fileSystem.createDirectories(file.parent!!)
        fileSystem.write(file) { writeUtf8("""{"autoLock":"30s","somethingNew":true}""") }

        assertEquals(AutoLockTimeout.OneMinute, AppSettingsStore(fileSystem, file).settings.value.autoLock)
    }

    @Test
    fun storedNamesAreStable() {
        val store = AppSettingsStore(fileSystem, file)
        store.update { it.copy(autoLock = AutoLockTimeout.OnAppRestart) }

        assertEquals("""{"autoLock":"restart"}""", fileSystem.read(file) { readUtf8() })
    }
}
