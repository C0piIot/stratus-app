package dev.stratus.core.sql

import androidx.sqlite.SQLiteStatement

/**
 * Closes the statement whatever happens.
 *
 * Here rather than in each store because androidx.sqlite's statement is not
 * `AutoCloseable`, so the stdlib's `use` does not apply -- and closing it is
 * something SQLite needs and nothing enforces, which is the worst combination
 * to leave to four separate copies.
 */
internal inline fun <T> SQLiteStatement.use(block: (SQLiteStatement) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
