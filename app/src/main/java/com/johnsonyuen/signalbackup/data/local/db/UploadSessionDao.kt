package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.johnsonyuen.signalbackup.data.local.entity.UploadSessionEntity

@Dao
abstract class UploadSessionDao {

    @Query("SELECT * FROM upload_session WHERE id = 1")
    abstract suspend fun get(): UploadSessionEntity?

    @Upsert
    abstract suspend fun upsert(entity: UploadSessionEntity)

    @Query("UPDATE upload_session SET bytesUploaded = :bytesUploaded WHERE id = 1")
    abstract suspend fun updateBytesUploaded(bytesUploaded: Long)

    @Query("UPDATE upload_session SET driveFileId = :driveFileId, remoteMd5 = :remoteMd5 WHERE id = 1")
    abstract suspend fun markCompleted(driveFileId: String, remoteMd5: String?)

    @Query("UPDATE upload_session SET failureCount = failureCount + 1 WHERE id = 1")
    abstract suspend fun bumpFailureCount()

    @Query("SELECT failureCount FROM upload_session WHERE id = 1")
    abstract suspend fun getFailureCount(): Int?

    /** Increments the failure count and returns the new value (0 when there is no session). */
    @Transaction
    open suspend fun incrementFailureCount(): Int {
        bumpFailureCount()
        return getFailureCount() ?: 0
    }

    @Query("DELETE FROM upload_session")
    abstract suspend fun clear()
}
