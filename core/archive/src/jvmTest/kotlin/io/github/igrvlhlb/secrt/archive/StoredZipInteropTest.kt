package io.github.igrvlhlb.secrt.archive

import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Checks our minimal ZIP code against the JDK's implementation. */
class StoredZipInteropTest {

    @Test
    fun crcMatchesKnownValue() {
        assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
    }

    @Test
    fun jdkReadsWhatWeWrite() {
        val buffer = Buffer()
        StoredZipWriter(buffer).apply {
            put("manifest.json", "{}".encodeToByteArray())
            put("blobs/é", ByteArray(70_000) { it.toByte() })
            finish()
        }

        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(buffer.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("manifest.json", "blobs/é"), entries.keys.toList())
        assertContentEquals(ByteArray(70_000) { it.toByte() }, entries["blobs/é"])
    }

    @Test
    fun weReadStoredZipsFromTheJdk() {
        val data = "hello".encodeToByteArray()
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.setMethod(ZipOutputStream.STORED)
                zip.putNextEntry(ZipEntry("a.txt").apply {
                    size = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                })
                zip.write(data)
                zip.closeEntry()
            }
        }.toByteArray()

        val reader = StoredZipReader(Buffer().write(bytes), maxEntryBytes = 1024)
        val (name, content) = reader.next()!!
        assertEquals("a.txt", name)
        assertContentEquals(data, content)
        assertNull(reader.next())
    }

    @Test
    fun compressedEntriesAreRejected() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("a.txt"))
                zip.write("hello".encodeToByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        assertFailsWith<CorruptArchiveException> { StoredZipReader(Buffer().write(bytes), 1024).next() }
    }
}
