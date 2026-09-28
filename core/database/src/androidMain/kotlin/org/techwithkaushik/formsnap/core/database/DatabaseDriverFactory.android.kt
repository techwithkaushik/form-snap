package org.techwithkaushik.formsnap.core.database

import android.content.Context
import app.cash.sqldelight.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver

actual class DatabaseDriverFactory(
    private val context: Context,
) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = FormSnapDatabase.Schema,
            context = context,
            name = "formsnap-learning.db",
        )
}
