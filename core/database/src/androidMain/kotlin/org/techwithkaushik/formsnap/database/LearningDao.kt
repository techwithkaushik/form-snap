package org.techwithkaushik.formsnap.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertCorrection(row: UserCorrectionLogEntity)

    @Query("SELECT * FROM UserCorrectionLog ORDER BY id ASC")
    fun selectAllLogs(): Flow<List<UserCorrectionLogEntity>>

    @Query("SELECT * FROM UserCorrectionLog ORDER BY id ASC")
    fun selectAllLogsSnapshot(): List<UserCorrectionLogEntity>

    @Query("SELECT * FROM TunedParameters ORDER BY parameterKey ASC")
    fun selectAllParameters(): List<TunedParameterEntity>

    @Query("SELECT * FROM TunedParameters WHERE parameterKey = :parameterKey LIMIT 1")
    fun getParameter(parameterKey: String): TunedParameterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun updateParameter(row: TunedParameterEntity)
}
