package org.techwithkaushik.formsnap.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Holds the platform SQLDelight driver together with the generated database.
 *
 * This mirrors the PeopleInSpace pattern: the platform DI layer constructs the
 * driver and generated database together, while common code only depends on
 * this wrapper.
 */
class LearningDatabaseWrapper(
    val driver: SqlDriver,
    val instance: LearningDatabase,
)
