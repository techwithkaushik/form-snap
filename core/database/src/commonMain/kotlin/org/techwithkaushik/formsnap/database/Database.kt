package org.techwithkaushik.formsnap.database

import app.cash.sqldelight.db.SqlDriver

/**
 * SQLDelight database wrapper shared by the application's repositories.
 *
 * The Android implementation supplies the concrete driver/database instance.
 */
class LearningDatabaseWrapper(
    val driver: SqlDriver,
    val instance: LearningDatabase,
)
