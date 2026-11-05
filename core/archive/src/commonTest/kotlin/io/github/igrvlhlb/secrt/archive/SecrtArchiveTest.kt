package io.github.igrvlhlb.secrt.archive

import io.github.igrvlhlb.secrt.crypto.Argon2Params
import io.github.igrvlhlb.secrt.crypto.Crypto
import io.github.igrvlhlb.secrt.crypto.SodiumCrypto
import io.github.igrvlhlb.secrt.model.AttachmentRef
import io.github.igrvlhlb.secrt.model.EntryPayload
import io.github.igrvlhlb.secrt.model.Hlc
import io.github.igrvlhlb.secrt.model.RecordType
import io.github.igrvlhlb.secrt.model.Revision
import io.github.igrvlhlb.secrt.model.SecrtJson
import io.github.igrvlhlb.secrt.vault.Credential
import io.github.igrvlhlb.secrt.vault.InvalidCredentialsException
import io.github.igrvlhlb.secrt.vault.VaultManager
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecrtArchiveTest {

    private class Fixture(val crypto: Crypto, val vault: VaultManager, val password: String = "pw") {
        val keys = vault.create(password, withRecoveryKey = false).first
        val blob = byteArrayOf(1, 2, 3)
        val revisions = listOf("A", "B").mapIndexed { i, device ->
            Revision(
                id = Revision.idOf(device, 1),
                recordType = RecordType.ENTRY,
                recordId = "e$i",
                parents = emptyList(),
                deviceId = device,
                seq = 1,
                hlc = Hlc(1_000L + i, 0, device),
                deleted = false,
                payload = SecrtJson.encodeToString(
                    EntryPayload("secret title $i", "body", LocalDate(2026, 11, 5), attachments = listOf(AttachmentRef("blob1", "a.png", "image/png", 3)), createdAt = 0),
                ),
            )
        }

        fun export(): ByteArray = Buffer().also { buffer ->
            SecrtArchive.write(buffer, crypto, keys, vault.headerJson(), revisions) { id -> assertEquals("blob1", id); blob }
        }.readByteArray()
    }

    private suspend fun fixture(): Fixture {
        val crypto = SodiumCrypto.create()
        return Fixture(crypto, VaultManager(FakeFileSystem(), "/vault".toPath(), crypto, Argon2Params.Insecure))
    }

    @Test
    fun roundTripWithPasswordFromTheArchive() = runTest {
        val f = fixture()
        val bytes = f.export()
        assertFalse(bytes.toByteString().indexOf("secret title".encodeToByteArray().toByteString()) >= 0, "titles must be encrypted")

        val blobs = mutableMapOf<String, ByteArray>()
        val revisions = SecrtArchive.read(
            Buffer().write(bytes),
            f.crypto,
            unlock = { _, header -> f.vault.unlockExternal(header, Credential.Password("pw")) },
            storeBlob = { id, data -> blobs[id] = data },
        )

        assertEquals(f.revisions.toSet(), revisions.toSet())
        assertContentEquals(f.blob, blobs["blob1"])
    }

    @Test
    fun unlockFailureStopsBeforeReadingData() = runTest {
        val f = fixture()
        var blobsSeen = 0
        assertFailsWith<InvalidCredentialsException> {
            SecrtArchive.read(Buffer().write(f.export()), f.crypto, { _, h -> f.vault.unlockExternal(h, Credential.Password("wrong")) }, { _, _ -> blobsSeen++ })
        }
        assertEquals(0, blobsSeen)
    }

    @Test
    fun randomFilesAreNotArchives() = runTest {
        val f = fixture()
        assertFailsWith<NotAnArchiveException> {
            SecrtArchive.read(Buffer().write(f.crypto.randomBytes(500)), f.crypto, { _, _ -> f.keys }, { _, _ -> })
        }
        assertFailsWith<NotAnArchiveException> {
            SecrtArchive.read(Buffer(), f.crypto, { _, _ -> f.keys }, { _, _ -> })
        }
    }

    @Test
    fun truncatedArchiveIsReportedAsCorrupt() = runTest {
        val f = fixture()
        val bytes = f.export()
        // Cut inside an entry, and inside the trailing ZIP directory (all entries intact).
        for (length in listOf(bytes.size / 2, bytes.size - 120)) {
            assertFailsWith<CorruptArchiveException> {
                SecrtArchive.read(Buffer().write(bytes.copyOf(length)), f.crypto, { _, _ -> f.keys }, { _, _ -> })
            }
        }
    }

    @Test
    fun tamperedSegmentIsRejected() = runTest {
        val f = fixture()
        val bytes = f.export()
        // Flip a byte inside the first segment's ciphertext and fix up its ZIP checksum, as an attacker would.
        val marker = "log/A.seg".encodeToByteArray().toByteString()
        val dataStart = bytes.toByteString().indexOf(marker) + marker.size + 40
        bytes[dataStart] = (bytes[dataStart] + 1).toByte()
        val headerStart = bytes.toByteString().indexOf(marker) - 30
        val size = Buffer().write(bytes, headerStart + 18, 4).readIntLe()
        val newCrc = Crc32.of(bytes.copyOfRange(headerStart + 30 + marker.size, headerStart + 30 + marker.size + size))
        Buffer().writeIntLe(newCrc).readByteArray().copyInto(bytes, headerStart + 14)

        val error = assertFailsWith<CorruptArchiveException> {
            SecrtArchive.read(Buffer().write(bytes), f.crypto, { _, _ -> f.keys }, { _, _ -> })
        }
        assertTrue("log/A.seg" in error.message!!)
    }
}
