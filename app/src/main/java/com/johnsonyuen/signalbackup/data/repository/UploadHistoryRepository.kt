/**
 * UploadHistoryRepository.kt - Repository interface for upload history records.
 *
 * This interface defines the contract for persisting and retrieving upload history.
 * It follows the Repository pattern to abstract the Room database behind a clean interface.
 *
 * The repository exposes domain model types ([UploadRecord]) rather than data layer
 * entities ([UploadHistoryEntity]), keeping the domain and UI layers clean of database concerns.
 * The entity-to-record mapping is handled in the implementation layer.
 *
 * Architecture context:
 * - Part of the **data layer** (data/repository package).
 * - Implemented by [UploadHistoryRepositoryImpl], which delegates to [UploadHistoryDao].
 * - Bound to its implementation by Hilt in [RepositoryModule] via @Binds.
 * - Consumed by [PerformUploadUseCase] (to record upload results) and
 *   [HistoryViewModel] (to display history in the UI).
 *
 * @see data.repository.UploadHistoryRepositoryImpl for the implementation
 * @see data.local.db.UploadHistoryDao for the underlying Room DAO
 * @see di.RepositoryModule for the Hilt binding
 */
package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import kotlinx.coroutines.flow.Flow

/**
 * Contract for upload history persistence operations.
 */
interface UploadHistoryRepository {

    /**
     * Inserts a new upload history record.
     * Accepts the data-layer entity because the use case constructs it with all fields.
     * @param entity The record to persist.
     */
    suspend fun insert(entity: UploadHistoryEntity)

    /**
     * Returns all upload history records as domain models, newest first, as a reactive Flow.
     * The entity-to-domain mapping is performed in the implementation.
     * @return A Flow that re-emits whenever the underlying data changes.
     */
    fun getAll(): Flow<List<UploadRecord>>

    /**
     * Returns the most recent upload history record as a domain model, or null if none exist.
     * @return A reactive Flow of the latest record.
     */
    fun getLatest(): Flow<UploadRecord?>
}
