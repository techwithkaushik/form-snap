package org.techwithkaushik.formsnap.database

import android.content.Context
import app.cash.sqldelight.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver

class AndroidLearningDatabase(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    val driver: SqlDriver by lazy {
        AndroidSqliteDriver(
            schema = LearningDatabase.Schema,
            context = applicationContext,
            name = DATABASE_NAME,
        )
    }

    val instance: LearningDatabase by lazy {
        LearningDatabase(driver)
    }

    companion object {
        private const val DATABASE_NAME = "formsnap-learning.db"
    }
}
