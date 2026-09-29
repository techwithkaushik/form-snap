package org.techwithkaushik.formsnap.database

import android.content.Context
import androidx.room.Room

object DatabaseProvider {
    @Volatile
    private var instance: LearningDatabase? = null

    fun get(context: Context): LearningDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LearningDatabase::class.java,
                LEARNING_DATABASE_NAME,
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
}