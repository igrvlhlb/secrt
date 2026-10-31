package io.github.igrvlhlb.secrt.storage

import android.content.Context
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.igrvlhlb.secrt.crypto.toHex
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Opens the database with SQLCipher, using the vault's database key as a raw 256-bit key. */
class SqlCipherDriverFactory(
    private val context: Context,
    private val databaseName: String = "secrt.db",
) : DriverFactory {

    override fun create(schema: SqlSchema<QueryResult.Value<Unit>>, key: ByteArray): SqlDriver {
        loadLibrary
        // SQLCipher's raw-key syntax skips its own (slow) key derivation: the key is already random.
        val rawKey = "x'${key.toHex()}'".encodeToByteArray()
        return AndroidSqliteDriver(
            schema = schema,
            context = context,
            name = databaseName,
            factory = SupportOpenHelperFactory(rawKey),
        )
    }

    fun deleteDatabase() {
        context.deleteDatabase(databaseName)
    }

    private companion object {
        val loadLibrary by lazy { System.loadLibrary("sqlcipher") }
    }
}
