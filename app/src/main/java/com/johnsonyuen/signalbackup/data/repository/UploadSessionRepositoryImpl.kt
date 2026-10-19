package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.db.UploadSessionDao
import com.johnsonyuen.signalbackup.data.local.entity.toDomain
import com.johnsonyuen.signalbackup.data.local.entity.toEntity
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed single resumable upload session. */
@Singleton
class UploadSessionRepositoryImpl @Inject constructor(
    private val dao: UploadSessionDao,
) : UploadSessionRepository {

    override suspend fun get(): ResumableUploadSession? = dao.get()?.toDomain()

    override suspend fun save(session: ResumableUploadSession) = dao.upsert(session.toEntity())

    override suspend fun updateBytesUploaded(bytesUploaded: Long) = dao.updateBytesUploaded(bytesUploaded)

    override suspend fun markCompleted(driveFileId: String, remoteMd5: String?) =
        dao.markCompleted(driveFileId, remoteMd5)

    override suspend fun incrementFailureCount(): Int = dao.incrementFailureCount()

    override suspend fun clear() = dao.clear()
}
