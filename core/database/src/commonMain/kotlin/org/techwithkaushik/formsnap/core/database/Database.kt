package org.techwithkaushik.formsnap.core.database

expect class DatabaseDriverFactory {
    fun createDriver(): app.cash.sqldelight.db.SqlDriver
}

class FormSnapDatabaseProvider(
    private val factory: DatabaseDriverFactory,
) {
    val database: FormSnapDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        FormSnapDatabase(factory.createDriver())
    }
}
