package org.techwithkaushik.formsnap.core.database

import app.cash.sqldelight.db.SqlDriver

expect class LearningMemoryManager(
    database: FormSnapDatabase,
    driver: SqlDriver,
    appVersion: String,
)
