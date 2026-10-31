package io.github.igrvlhlb.secrt.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/**
 * Opens the platform's SQL driver over an encrypted database.
 * Android uses SQLCipher; other platforms will provide their own implementation.
 */
fun interface DriverFactory {
    fun create(schema: SqlSchema<QueryResult.Value<Unit>>, key: ByteArray): SqlDriver
}
