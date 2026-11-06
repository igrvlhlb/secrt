package io.github.igrvlhlb.secrt.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.sqlite.mc.SQLiteMCSqlCipherConfig
import java.io.File

/**
 * Opens the database with sqlite-jdbc-crypt (SQLite3MultipleCiphers) in SQLCipher 4 mode, using
 * the vault's database key as a raw 256-bit key, like the Android factory does.
 *
 * The file isn't meant to be portable between devices (a restore rebuilds the database from the
 * revisions), but it is encrypted the same way: no plaintext, not even the SQLite header.
 */
class SqlCipherDriverFactory(private val databaseFile: File) : DriverFactory {

    override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, key: ByteArray): SqlDriver {
        databaseFile.parentFile?.mkdirs()
        // A raw key (SQLCipher's x'…' syntax) skips the password KDF: the key is already random.
        // It goes in the connection properties, not the URL, so it never shows up in error messages.
        // The driver opens a connection per thread, and each one is keyed from these properties.
        val properties = SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(key).build().toProperties()
        return JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}", properties, schema)
    }

    override fun delete() {
        for (suffix in listOf("", "-journal", "-wal", "-shm")) {
            File(databaseFile.path + suffix).delete()
        }
    }
}
