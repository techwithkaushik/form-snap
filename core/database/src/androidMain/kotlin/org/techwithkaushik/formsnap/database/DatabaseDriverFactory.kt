package org.techwithkaushik.formsnap.database

import android.content.Context
import app.cash.sqldelight.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver

actual class DatabaseDriverFactory(
    private val context: Context,
) {
    actual fun createDriver(): SqlDriver =
        AndroidSqliteDriver(
            schema = LearningDatabase.Schema,
            context = context.applicationContext,
            name = DATABASE_NAME,
        )

    private companion object {
        const val DATABASE_NAME = "formsnap-learning.db"
    }
}
