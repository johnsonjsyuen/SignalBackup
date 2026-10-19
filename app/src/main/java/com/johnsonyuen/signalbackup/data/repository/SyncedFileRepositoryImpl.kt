package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.db.SyncedFileDao
import com.johnsonyuen.signalbackup.data.local.entity.toDomain
import com.johnsonyuen.signalbackup.data.local.entity.toEntity
import com.johnsonyuen.signalbackup.domain.model.SyncedFile
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed records of files already mirrored to Drive. */
@Singleton
class SyncedFileRepositoryImpl @Inject constructor(
    private val dao: SyncedFileDao,
) : SyncedFileRepository {

    override suspend fun get(relativePath: String): SyncedFile? = dao.get(relativePath)?.toDomain()

    override suspend fun getAll(): List<SyncedFile> = dao.getAll().map { it.toDomain() }

    override suspend fun upsert(file: SyncedFile) = dao.upsert(file.toEntity())

    override suspend fun delete(relativePath: String) = dao.delete(relativePath)

    override suspend fun clear() = dao.clear()
}
