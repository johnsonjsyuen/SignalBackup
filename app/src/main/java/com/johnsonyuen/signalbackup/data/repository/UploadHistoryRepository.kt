package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import kotlinx.coroutines.flow.Flow

interface UploadHistoryRepository {

    suspend fun insert(entity: UploadHistoryEntity)

    fun getAll(): Flow<List<UploadHistoryEntity>>

    fun getLatest(): Flow<UploadHistoryEntity?>
}
