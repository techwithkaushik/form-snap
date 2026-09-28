package org.techwithkaushik.formsnap.core.database

import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.json.Json

expect class LearningMemoryManager(
    database: FormSnapDatabase,
    driver: SqlDriver,
    appVersion: String,
)
