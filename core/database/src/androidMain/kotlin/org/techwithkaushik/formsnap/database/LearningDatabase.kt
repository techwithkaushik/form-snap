package org.techwithkaushik.formsnap.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        UserCorrectionLogEntity::class,
        TunedParameterEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LearningDatabase : RoomDatabase() {
    abstract fun learningDao(): LearningDao
}
