package org.techwithkaushik.formsnap.database

expect class DatabaseDriverFactory {
    fun createDriver(): app.cash.sqldelight.db.SqlDriver
}

class LearningDatabaseProvider(
    private val factory: DatabaseDriverFactory,
) {
    val database: LearningDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LearningDatabase(factory.createDriver())
    }
}
