/**
 * UploadHistoryMapper.kt - Extension function to map data-layer entities to domain models.
 *
 * This file contains the mapping logic that converts [UploadHistoryEntity] (the Room database
 * representation) into [UploadRecord] (the domain model used by the UI and business logic).
 *
 * Why a separate mapper?
 * - **Separation of concerns**: The entity knows about Room annotations and database columns.
 *   The domain model knows about business types (Instant, UploadResultStatus enum). The mapper
 *   bridges these two worlds.
 * - **Single responsibility**: If the database schema changes (e.g., column renamed), only
 *   the entity and this mapper need to change -- the domain model stays stable.
 * - **Type safety**: The database stores `status` as a String, but the domain model uses
 *   a type-safe enum. This mapper handles the conversion with a safe fallback.
 *
 * Architecture context:
 * - Part of the **data layer** (data/local/entity package).
 * - Used by [UploadHistoryRepositoryImpl] to transform DAO query results.
 *
 * @see data.local.entity.UploadHistoryEntity for the source data type
 * @see domain.model.UploadRecord for the target domain type
 * @see data.repository.UploadHistoryRepositoryImpl for where this mapper is called
 */
package com.johnsonyuen.signalbackup.data.local.entity

import com.johnsonyuen.signalbackup.domain.model.UploadRecord
import com.johnsonyuen.signalbackup.domain.model.UploadResultStatus
import java.time.Instant

/**
 * Maps an [UploadHistoryEntity] (data layer) to an [UploadRecord] (domain layer).
 *
 * This keeps the Room entity details (column names, string-based status) from
 * leaking into the domain and UI layers.
 *
 * Key conversions:
 * - `timestamp` (Long millis) -> `Instant` (Java time API)
 * - `status` (String "SUCCESS"/"FAILED") -> `UploadResultStatus` (type-safe enum)
 *   with a fallback to FAILED for unrecognized values
 */
fun UploadHistoryEntity.toUploadRecord(): UploadRecord = UploadRecord(
    id = id,
    timestamp = Instant.ofEpochMilli(timestamp),
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    // Safely convert the string status to an enum, defaulting to FAILED
    // if the stored value is somehow invalid (forward-compatibility).
    status = try {
        UploadResultStatus.valueOf(status)
    } catch (_: IllegalArgumentException) {
        UploadResultStatus.FAILED
    },
    errorMessage = errorMessage,
    driveFolderId = driveFolderId,
    driveFileId = driveFileId
)
