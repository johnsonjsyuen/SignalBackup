package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.domain.model.SyncedFile

/** Records of local items already mirrored to Drive (Room table `synced_file`). */
interface SyncedFileRepository {
    suspend fun get(relativePath: String): SyncedFile?
    suspend fun getAll(): List<SyncedFile>
    suspend fun upsert(file: SyncedFile)
    suspend fun delete(relativePath: String)
    /** Called when the Drive folder changes or the user signs out. */
    suspend fun clear()
}
