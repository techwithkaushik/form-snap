package org.techwithkaushik.formsnap.database

import android.content.Context
import app.cash.sqldelight.android.AndroidSqliteDriver

actual class DatabaseDriverFactory(
    private val context: Context,
) {
    fun createDatabase(): LearningDatabaseWrapper {
        val driver =
            AndroidSqliteDriver(
                schema = LearningDatabase.Schema,
                context = context.applicationContext,
                name = DATABASE_NAME,
            )

        return LearningDatabaseWrapper(
            driver = driver,
            instance = LearningDatabase(driver),
        )
    }

    private companion object {
        const val DATABASE_NAME = "formsnap-learning.db"
    }
}
