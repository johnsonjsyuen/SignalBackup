package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao
import com.johnsonyuen.signalbackup.data.local.entity.toEntity
import com.johnsonyuen.signalbackup.data.local.entity.toUploadRecord
import com.johnsonyuen.signalbackup.domain.model.NewUploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed upload history. */
@Singleton
class UploadHistoryRepositoryImpl @Inject constructor(
    private val dao: UploadHistoryDao,
) : UploadHistoryRepository {

    override suspend fun record(record: NewUploadRecord) {
        dao.insert(record.toEntity())
    }

    override fun getAll(): Flow<List<UploadRecord>> =
        dao.observeAll().map { rows -> rows.map { it.toUploadRecord() } }

    override fun getLatest(): Flow<UploadRecord?> = dao.observeLatest().map { it?.toUploadRecord() }

    override fun getLatestSuccess(): Flow<UploadRecord?> = dao.observeLatestSuccess().map { it?.toUploadRecord() }

    override suspend fun getSuccessfulDriveFileIds(): Set<String> = dao.getSuccessfulDriveFileIds().toSet()
}
