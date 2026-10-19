package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession

/** Persists the single in-flight resumable upload (Room table `upload_session`, one row). */
interface UploadSessionRepository {
    suspend fun get(): ResumableUploadSession?
    suspend fun save(session: ResumableUploadSession)
    suspend fun updateBytesUploaded(bytesUploaded: Long)
    /** Called the moment Drive answers 200/201, before local verification. */
    suspend fun markCompleted(driveFileId: String, remoteMd5: String?)
    /** Increments and returns the new failure count. */
    suspend fun incrementFailureCount(): Int
    suspend fun clear()
}
