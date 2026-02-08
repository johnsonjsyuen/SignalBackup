package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class UploadHistoryRepositoryImpl @Inject constructor(
    private val dao: UploadHistoryDao,
) : UploadHistoryRepository {

    override suspend fun insert(entity: UploadHistoryEntity) {
        dao.insert(entity)
    }

    override fun getAll(): Flow<List<UploadHistoryEntity>> {
        return dao.getAll()
    }

    override fun getLatest(): Flow<UploadHistoryEntity?> {
        return dao.getLatest()
    }
}
