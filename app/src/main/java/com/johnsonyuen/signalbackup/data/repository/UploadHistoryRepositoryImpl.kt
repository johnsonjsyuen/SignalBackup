/**
 * UploadHistoryRepositoryImpl.kt - Implementation of [UploadHistoryRepository] backed by Room.
 *
 * This class implements the [UploadHistoryRepository] interface by delegating database
 * operations to the Room [UploadHistoryDao] and mapping results from data-layer entities
 * to domain-layer models using the [toUploadRecord] extension function.
 *
 * Architecture context:
 * - Part of the **data layer** (data/repository package).
 * - Uses [@Inject] constructor for Hilt dependency injection.
 * - Bound to [UploadHistoryRepository] interface via @Binds in [RepositoryModule].
 * - The mapping from [UploadHistoryEntity] to [UploadRecord] is done via the
 *   [toUploadRecord] extension function defined in UploadHistoryMapper.kt.
 *
 * @see data.repository.UploadHistoryRepository for the interface contract
 * @see data.local.db.UploadHistoryDao for the underlying Room DAO
 * @see data.local.entity.UploadHistoryMapper for the entity-to-domain mapping
 * @see di.RepositoryModule for the Hilt binding
 */
package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.db.UploadHistoryDao
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.data.local.entity.toUploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Concrete implementation of [UploadHistoryRepository] that delegates to the Room DAO
 * and maps entities to domain models.
 *
 * @param dao The Room Data Access Object for upload_history table operations.
 */
class UploadHistoryRepositoryImpl @Inject constructor(
    private val dao: UploadHistoryDao,
) : UploadHistoryRepository {

    /** Delegates insertion to Room DAO. Runs on Room's internal background thread. */
    override suspend fun insert(entity: UploadHistoryEntity) {
        dao.insert(entity)
    }

    /**
     * Returns all records mapped to domain models.
     * The Flow.map operator transforms each emitted list of entities into a list of
     * [UploadRecord] domain objects, keeping Room details out of the UI layer.
     */
    override fun getAll(): Flow<List<UploadRecord>> {
        return dao.getAll().map { entities ->
            entities.map { it.toUploadRecord() }
        }
    }

    /**
     * Returns the latest record mapped to a domain model, or null.
     * Uses the nullable-safe [?.toUploadRecord()] call to handle the empty table case.
     */
    override fun getLatest(): Flow<UploadRecord?> {
        return dao.getLatest().map { it?.toUploadRecord() }
    }
}
