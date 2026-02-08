package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadHistoryDao {

    @Insert
    suspend fun insert(entity: UploadHistoryEntity)

    @Query("SELECT * FROM upload_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<UploadHistoryEntity>>

    @Query("SELECT * FROM upload_history ORDER BY timestamp DESC LIMIT 1")
    fun getLatest(): Flow<UploadHistoryEntity?>
}
