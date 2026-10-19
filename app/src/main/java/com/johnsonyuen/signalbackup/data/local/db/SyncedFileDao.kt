package com.johnsonyuen.signalbackup.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.johnsonyuen.signalbackup.data.local.entity.SyncedFileEntity

@Dao
interface SyncedFileDao {

    @Query("SELECT * FROM synced_file WHERE relativePath = :relativePath")
    suspend fun get(relativePath: String): SyncedFileEntity?

    @Query("SELECT * FROM synced_file ORDER BY relativePath")
    suspend fun getAll(): List<SyncedFileEntity>

    @Upsert
    suspend fun upsert(entity: SyncedFileEntity)

    @Query("DELETE FROM synced_file WHERE relativePath = :relativePath")
    suspend fun delete(relativePath: String)

    @Query("DELETE FROM synced_file")
    suspend fun clear()
}
