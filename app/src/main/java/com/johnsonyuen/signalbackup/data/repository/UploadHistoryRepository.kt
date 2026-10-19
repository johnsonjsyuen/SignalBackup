package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.domain.model.NewUploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import kotlinx.coroutines.flow.Flow

interface UploadHistoryRepository {
    suspend fun record(record: NewUploadRecord)
    fun getAll(): Flow<List<UploadRecord>>
    fun getLatest(): Flow<UploadRecord?>
    /** Most recent row with status SUCCESS. */
    fun getLatestSuccess(): Flow<UploadRecord?>
    /** All SUCCESS rows that carry a driveFileId (used to recognise files this app verified). */
    suspend fun getSuccessfulDriveFileIds(): Set<String>
}
