package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadHistoryDao {

    @Insert
    suspend fun insert(entity: UploadHistoryEntity): Long

    @Query("SELECT * FROM upload_history ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<UploadHistoryEntity>>

    @Query("SELECT * FROM upload_history ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun observeLatest(): Flow<UploadHistoryEntity?>

    @Query("SELECT * FROM upload_history WHERE status = 'SUCCESS' ORDER BY timestamp DESC, id DESC LIMIT 1")
    fun observeLatestSuccess(): Flow<UploadHistoryEntity?>

    @Query("SELECT DISTINCT driveFileId FROM upload_history WHERE status = 'SUCCESS' AND driveFileId IS NOT NULL")
    suspend fun getSuccessfulDriveFileIds(): List<String>
}
