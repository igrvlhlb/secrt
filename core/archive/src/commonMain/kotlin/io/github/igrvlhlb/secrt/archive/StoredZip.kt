package io.github.igrvlhlb.secrt.archive

import okio.BufferedSink
import okio.BufferedSource
import okio.EOFException

/**
 * Writes a standard ZIP file whose entries are *stored* (not compressed): everything we put in an
 * archive is already encrypted, so compression would gain nothing. Any ZIP tool can list and
 * extract the result. Timestamps are fixed to 1980-01-01 so archives don't reveal edit times.
 */
internal class StoredZipWriter(private val sink: BufferedSink) {
    private class Written(val name: ByteArray, val crc: Int, val size: Int, val offset: Long)

    private val entries = mutableListOf<Written>()
    private var offset = 0L

    fun put(name: String, data: ByteArray) {
        val nameBytes = name.encodeToByteArray()
        val crc = Crc32.of(data)
        entries += Written(nameBytes, crc, data.size, offset)
        sink.writeIntLe(LOCAL_HEADER)
        writeCommonFields(nameBytes, crc, data.size)
        sink.write(nameBytes)
        sink.write(data)
        offset += 30 + nameBytes.size + data.size
    }

    fun finish() {
        val directoryStart = offset
        for (e in entries) {
            sink.writeIntLe(CENTRAL_HEADER)
            sink.writeShortLe(VERSION)
            writeCommonFields(e.name, e.crc, e.size)
            sink.writeShortLe(0) // comment length
            sink.writeShortLe(0) // disk number
            sink.writeShortLe(0) // internal attributes
            sink.writeIntLe(0) // external attributes
            sink.writeIntLe(e.offset.toInt())
            sink.write(e.name)
            offset += 46 + e.name.size
        }
        sink.writeIntLe(END_OF_DIRECTORY)
        sink.writeShortLe(0)
        sink.writeShortLe(0)
        sink.writeShortLe(entries.size)
        sink.writeShortLe(entries.size)
        sink.writeIntLe((offset - directoryStart).toInt())
        sink.writeIntLe(directoryStart.toInt())
        sink.writeShortLe(0)
        sink.flush()
    }

    /** Fields shared by local and central headers, from "version needed" to "extra length". */
    private fun writeCommonFields(name: ByteArray, crc: Int, size: Int) {
        sink.writeShortLe(VERSION)
        sink.writeShortLe(FLAG_UTF8)
        sink.writeShortLe(METHOD_STORED)
        sink.writeShortLe(0) // time 00:00
        sink.writeShortLe(DATE_1980_01_01)
        sink.writeIntLe(crc)
        sink.writeIntLe(size)
        sink.writeIntLe(size)
        sink.writeShortLe(name.size)
        sink.writeShortLe(0) // extra length
    }
}

/**
 * Reads a stored-only ZIP front to back, without random access, so it works on any stream (like
 * a document picked through Android's file picker). Only archives written by [StoredZipWriter],
 * or other tools using the same restrictions, are supported.
 */
internal class StoredZipReader(private val source: BufferedSource, private val maxEntryBytes: Long) {
    private var finished = false
    private var entriesRead = 0

    /**
     * @return the next entry, or `null` after the last one. Reaching the end also validates the
     *   central directory, so a truncated file is detected even if all its entries were intact.
     */
    fun next(): Pair<String, ByteArray>? {
        if (finished) return null
        try {
            when (val signature = source.readIntLe()) {
                CENTRAL_HEADER -> {
                    readCentralDirectory()
                    finished = true
                    return null
                }
                LOCAL_HEADER -> Unit
                else -> throw CorruptArchiveException("Unexpected ZIP record ${signature.toUInt().toString(16)}")
            }
            source.readShortLe() // version needed
            val flags = source.readShortLe().toInt()
            val method = source.readShortLe().toInt()
            source.skip(4) // time, date
            val crc = source.readIntLe()
            val compressedSize = source.readIntLe().toUInt().toLong()
            val size = source.readIntLe().toUInt().toLong()
            val nameLength = source.readShortLe().toUShort().toInt()
            val extraLength = source.readShortLe().toUShort().toInt()
            if (method != METHOD_STORED || flags and FLAG_DATA_DESCRIPTOR != 0 || compressedSize != size) {
                throw CorruptArchiveException("Unsupported ZIP entry (compressed or streamed)")
            }
            if (size > maxEntryBytes) throw CorruptArchiveException("ZIP entry too large")
            val name = source.readUtf8(nameLength.toLong())
            source.skip(extraLength.toLong())
            val data = source.readByteArray(size)
            if (Crc32.of(data) != crc) throw CorruptArchiveException("Checksum mismatch in $name")
            entriesRead++
            return name to data
        } catch (e: EOFException) {
            throw CorruptArchiveException("The file is incomplete", e)
        }
    }

    /** Skips the central directory (its first signature already read) and checks the end record. */
    private fun readCentralDirectory() {
        var listed = 0
        var signature = CENTRAL_HEADER
        while (signature == CENTRAL_HEADER) {
            source.skip(24) // versions, flags, method, time, date, crc, sizes
            val nameLength = source.readShortLe().toUShort().toLong()
            val extraLength = source.readShortLe().toUShort().toLong()
            val commentLength = source.readShortLe().toUShort().toLong()
            source.skip(12 + nameLength + extraLength + commentLength) // disk, attributes, offset, then variable fields
            listed++
            signature = source.readIntLe()
        }
        if (signature != END_OF_DIRECTORY) throw CorruptArchiveException("Missing end of ZIP directory")
        source.skip(6) // disk numbers, entries on this disk
        val total = source.readShortLe().toUShort().toInt()
        if (total != listed || total != entriesRead) throw CorruptArchiveException("ZIP directory doesn't match its entries")
    }
}

private const val LOCAL_HEADER = 0x04034b50
private const val CENTRAL_HEADER = 0x02014b50
private const val END_OF_DIRECTORY = 0x06054b50
private const val VERSION = 20
private const val METHOD_STORED = 0
private const val FLAG_DATA_DESCRIPTOR = 0x0008
private const val FLAG_UTF8 = 0x0800
private const val DATE_1980_01_01 = (0 shl 9) or (1 shl 5) or 1
