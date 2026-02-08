/**
 * PerformUploadUseCase.kt - Core business logic for uploading a backup file to Google Drive.
 *
 * This use case orchestrates the entire upload flow with **resumable upload** support:
 * 1. Check for a saved resumable session from a previous interrupted upload.
 * 2. If a valid session exists, verify it with Google and resume from the last confirmed byte.
 * 3. If no session exists (or the saved one expired), find the latest .backup file and
 *    initiate a new resumable upload session.
 * 4. Upload the file in chunks, saving progress to DataStore after each chunk.
 * 5. On success, clear the saved session and record the result in upload history.
 * 6. On failure, leave the session saved so it can be resumed on the next attempt.
 *
 * The resumable upload protocol allows uploads to survive app kills and WorkManager retries.
 * Google Drive keeps resumable session URIs valid for approximately one week, so even if
 * the device is offline for days, the upload can resume where it left off.
 *
 * Error handling:
 * - [UserRecoverableAuthIOException]: Caught and returned as [UploadStatus.NeedsConsent].
 * - Transient errors during chunk upload: The session remains saved; WorkManager will
 *   retry and the next attempt will resume from the last confirmed byte.
 * - Session expired (404): The saved session is cleared and a fresh upload starts.
 *
 * Threading: Runs on [Dispatchers.IO] for file I/O, network I/O, and database I/O.
 *
 * @see domain.model.UploadStatus for the possible return states
 * @see domain.model.ResumableUploadSession for the persisted session state
 * @see data.remote.GoogleDriveService for the resumable upload protocol implementation
 * @see worker.UploadWorker for the background execution context
 */
package com.johnsonyuen.signalbackup.domain.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.johnsonyuen.signalbackup.data.local.entity.UploadHistoryEntity
import com.johnsonyuen.signalbackup.data.remote.GoogleDriveService
import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.UploadHistoryRepository
import com.johnsonyuen.signalbackup.data.repository.UploadProgressListener
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import com.johnsonyuen.signalbackup.domain.model.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Use case that finds the latest .backup file in the configured local folder
 * and uploads it to the configured Google Drive folder, with support for
 * resuming interrupted uploads across app restarts.
 *
 * @param settingsRepository For reading settings and persisting resumable session state.
 * @param driveRepository For uploading the file to Google Drive.
 * @param uploadHistoryRepository For recording the upload result in the local database.
 */
class PerformUploadUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val driveRepository: DriveRepository,
    private val uploadHistoryRepository: UploadHistoryRepository,
) {
    /**
     * Performs the upload operation, resuming from a saved session if one exists.
     *
     * @param context Android Context needed for SAF (DocumentFile.fromTreeUri) and
     *        ContentResolver (opening file InputStreams). Uses ApplicationContext to
     *        avoid Activity lifecycle issues.
     * @param progressListener Optional callback invoked as chunks are uploaded to Drive.
     *        Receives bytes uploaded so far and total file size in bytes.
     * @return The resulting [UploadStatus] indicating success, failure, or consent needed.
     */
    suspend operator fun invoke(
        context: Context,
        progressListener: UploadProgressListener? = null,
    ): UploadStatus = withContext(Dispatchers.IO) {
        Log.d(TAG, "invoke() called, progressListener=${if (progressListener != null) "SET" else "NULL"}")
        Log.d(TAG, "Starting upload (checking for resumable session)")

        // Step 1: Read the configured folders from settings.
        val localFolderUri = settingsRepository.localFolderUri.first()
        val driveFolderId = settingsRepository.driveFolderId.first()

        if (localFolderUri == null || driveFolderId == null) {
            return@withContext UploadStatus.Failed(
                "Configuration incomplete — set local folder and Drive folder"
            )
        }

        try {
            // Step 2: Check for a saved resumable session from a previous interrupted upload.
            val savedSession = settingsRepository.getResumableSession()
            val resumeResult = savedSession?.let { session ->
                tryResumeUpload(context, session, driveFolderId, progressListener)
            }

            when (resumeResult) {
                // Resume succeeded -- return the success status.
                is ResumeAttemptResult.Completed -> {
                    return@withContext resumeResult.status
                }
                // Resume is not possible (expired, different file, etc.) -- start fresh.
                is ResumeAttemptResult.SessionInvalid -> {
                    Log.d(TAG, "Saved session invalid: ${resumeResult.reason}. Starting fresh.")
                    settingsRepository.clearResumableSession()
                }
                // No saved session -- start fresh.
                null -> {
                    Log.d(TAG, "No saved session found. Starting fresh upload.")
                }
            }

            // Step 3: Find the latest .backup file via SAF.
            val uri = Uri.parse(localFolderUri)
            val folder = DocumentFile.fromTreeUri(context, uri)
                ?: return@withContext UploadStatus.Failed("Cannot access local folder")

            val latestBackup = folder.listFiles()
                .filter { it.isFile && it.name?.endsWith(".backup") == true }
                .maxByOrNull { it.lastModified() }
                ?: return@withContext UploadStatus.Failed("No backup file found")

            val fileName = latestBackup.name ?: "unknown.backup"
            val fileSize = latestBackup.length()

            // Layer 3: Deduplication check — if a file with the same name AND size already
            // exists in the target Drive folder, skip the upload to avoid creating duplicates.
            // Name-only matching is insufficient because a different file could have the same name.
            val existingFile = driveRepository.findFileByName(driveFolderId, fileName)
            if (existingFile != null && existingFile.sizeBytes == fileSize) {
                Log.d(TAG, "File '$fileName' (size=$fileSize) already exists in Drive folder (id=${existingFile.id}), skipping upload")
                uploadHistoryRepository.insert(
                    UploadHistoryEntity(
                        timestamp = System.currentTimeMillis(),
                        fileName = fileName,
                        fileSizeBytes = fileSize,
                        status = UploadHistoryEntity.STATUS_SUCCESS,
                        errorMessage = null,
                        driveFolderId = driveFolderId,
                        driveFileId = existingFile.id,
                    )
                )
                return@withContext UploadStatus.Success(fileName = fileName, fileSizeBytes = fileSize)
            }

            // Step 4: Initiate a new resumable upload session with Google Drive.
            val sessionUri = driveRepository.initiateResumableUpload(
                folderId = driveFolderId,
                fileName = fileName,
                mimeType = MIME_TYPE,
                totalBytes = fileSize,
            )

            // Save the session immediately so it can be resumed if we are killed.
            val session = ResumableUploadSession(
                sessionUri = sessionUri,
                localFileUri = latestBackup.uri.toString(),
                fileName = fileName,
                bytesUploaded = 0L,
                totalBytes = fileSize,
                driveFolderId = driveFolderId,
                createdAtMillis = System.currentTimeMillis(),
            )
            settingsRepository.saveResumableSession(session)

            // Step 5: Upload the file in chunks.
            // uploadChunked manages its own stream lifecycle (open, skip, retry on EOF).
            val uploadResult = uploadChunked(
                context = context,
                fileUri = latestBackup.uri,
                sessionUri = sessionUri,
                offset = 0L,
                totalBytes = fileSize,
                progressListener = progressListener,
            )

            // Step 6: Verify upload integrity via MD5 checksum.
            verifyChecksum(context, latestBackup.uri, uploadResult.md5Checksum)

            // Step 7: Upload complete -- persist file ID first, then clear session.
            // Persisting the file ID before clearing shrinks the crash window:
            // if we crash between these two calls, the session will have the file ID
            // and Layer 2's early check in tryResumeUpload will recover it.
            settingsRepository.updateResumableDriveFileId(uploadResult.driveFileId)
            settingsRepository.clearResumableSession()

            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    status = UploadHistoryEntity.STATUS_SUCCESS,
                    errorMessage = null,
                    driveFolderId = driveFolderId,
                    driveFileId = uploadResult.driveFileId,
                )
            )

            UploadStatus.Success(fileName = fileName, fileSizeBytes = fileSize)

        } catch (e: UserRecoverableAuthIOException) {
            Log.w(TAG, "Drive consent needed", e)
            UploadStatus.NeedsConsent(e.intent)
        } catch (e: Exception) {
            // Transient error -- leave the session saved so it can be resumed.
            // The saved session in DataStore persists the progress, and the next
            // WorkManager retry will pick up where we left off.
            Log.e(TAG, "Upload failed (session preserved for retry)", e)
            val errorMsg = "${e.javaClass.simpleName}: ${e.message ?: "no details"}"

            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = "unknown",
                    fileSizeBytes = 0,
                    status = UploadHistoryEntity.STATUS_FAILED,
                    errorMessage = errorMsg,
                    driveFolderId = driveFolderId,
                    driveFileId = null,
                )
            )
            UploadStatus.Failed(errorMsg)
        }
    }

    /**
     * Attempts to resume an upload from a saved session.
     *
     * Validates that the session matches the current configuration and that the same
     * local file still exists. Queries Google Drive for the actual bytes received to
     * get the authoritative resume offset.
     *
     * @param context Android Context for SAF file access.
     * @param session The saved resumable upload session.
     * @param currentDriveFolderId The currently configured Drive folder ID.
     * @param progressListener Optional progress callback.
     * @return [ResumeAttemptResult.Completed] if the resume succeeded,
     *         [ResumeAttemptResult.SessionInvalid] if the session cannot be resumed.
     */
    private suspend fun tryResumeUpload(
        context: Context,
        session: ResumableUploadSession,
        currentDriveFolderId: String,
        progressListener: UploadProgressListener?,
    ): ResumeAttemptResult {
        // Layer 2: If the upload already completed but the session wasn't cleared (crash
        // between uploadChunked returning and clearResumableSession), recover immediately.
        if (session.driveFileId != null) {
            Log.d(TAG, "Session has driveFileId=${session.driveFileId} — upload already completed, recovering")
            settingsRepository.clearResumableSession()
            uploadHistoryRepository.insert(
                UploadHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    fileName = session.fileName,
                    fileSizeBytes = session.totalBytes,
                    status = UploadHistoryEntity.STATUS_SUCCESS,
                    errorMessage = null,
                    driveFolderId = session.driveFolderId,
                    driveFileId = session.driveFileId,
                )
            )
            return ResumeAttemptResult.Completed(
                UploadStatus.Success(
                    fileName = session.fileName,
                    fileSizeBytes = session.totalBytes,
                )
            )
        }

        // Check if the session is too old (Google keeps session URIs for ~1 week).
        if (session.isExpired()) {
            return ResumeAttemptResult.SessionInvalid("Session expired (older than 6 days)")
        }

        // Check if the Drive folder has changed since the session was created.
        if (session.driveFolderId != currentDriveFolderId) {
            return ResumeAttemptResult.SessionInvalid(
                "Drive folder changed (was ${session.driveFolderId}, now $currentDriveFolderId)"
            )
        }

        // Verify the local file still exists and matches the session.
        val localFileUri = Uri.parse(session.localFileUri)
        val localFile = try {
            DocumentFile.fromSingleUri(context, localFileUri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access saved file URI: ${session.localFileUri}", e)
            null
        }

        if (localFile == null || !localFile.exists()) {
            return ResumeAttemptResult.SessionInvalid("Local file no longer exists")
        }

        // Verify the file size matches (the file may have been replaced with a newer backup).
        val currentFileSize = localFile.length()
        if (currentFileSize != session.totalBytes) {
            return ResumeAttemptResult.SessionInvalid(
                "File size changed (was ${session.totalBytes}, now $currentFileSize)"
            )
        }

        // Query Google Drive for the actual progress of this session.
        val progressResult = driveRepository.querySessionProgress(
            sessionUri = session.sessionUri,
            totalBytes = session.totalBytes,
        )

        val confirmedBytes = when (progressResult) {
            is GoogleDriveService.SessionProgressResult.Expired -> {
                return ResumeAttemptResult.SessionInvalid("Server rejected session URI (likely expired)")
            }
            is GoogleDriveService.SessionProgressResult.AlreadyComplete -> {
                // The upload was already completed — we now have the file ID from the response.
                Log.d(TAG, "Session query shows upload already complete, file ID: ${progressResult.driveFileId}")
                settingsRepository.clearResumableSession()
                uploadHistoryRepository.insert(
                    UploadHistoryEntity(
                        timestamp = System.currentTimeMillis(),
                        fileName = session.fileName,
                        fileSizeBytes = session.totalBytes,
                        status = UploadHistoryEntity.STATUS_SUCCESS,
                        errorMessage = null,
                        driveFolderId = session.driveFolderId,
                        driveFileId = progressResult.driveFileId,
                    )
                )
                return ResumeAttemptResult.Completed(
                    UploadStatus.Success(
                        fileName = session.fileName,
                        fileSizeBytes = session.totalBytes,
                    )
                )
            }
            is GoogleDriveService.SessionProgressResult.InProgress -> {
                progressResult.confirmedBytes
            }
        }

        // Resume from the confirmed byte offset.
        Log.d(TAG, "Resuming upload from byte $confirmedBytes / ${session.totalBytes}")

        // Update the saved session with the server-confirmed byte count.
        settingsRepository.updateResumableBytesUploaded(confirmedBytes)

        // uploadChunked manages its own stream lifecycle (open, skip, retry on EOF).
        val uploadResult = uploadChunked(
            context = context,
            fileUri = localFileUri,
            sessionUri = session.sessionUri,
            offset = confirmedBytes,
            totalBytes = session.totalBytes,
            progressListener = progressListener,
        )

        // Verify upload integrity via MD5 checksum.
        verifyChecksum(context, localFileUri, uploadResult.md5Checksum)

        // Upload complete -- persist file ID first, then clear session.
        settingsRepository.updateResumableDriveFileId(uploadResult.driveFileId)
        settingsRepository.clearResumableSession()

        uploadHistoryRepository.insert(
            UploadHistoryEntity(
                timestamp = System.currentTimeMillis(),
                fileName = session.fileName,
                fileSizeBytes = session.totalBytes,
                status = UploadHistoryEntity.STATUS_SUCCESS,
                errorMessage = null,
                driveFolderId = session.driveFolderId,
                driveFileId = uploadResult.driveFileId,
            )
        )

        return ResumeAttemptResult.Completed(
            UploadStatus.Success(
                fileName = session.fileName,
                fileSizeBytes = session.totalBytes,
            )
        )
    }

    /**
     * Uploads a file to Google Drive in chunks via the resumable upload protocol.
     *
     * Opens the file InputStream internally and manages its lifecycle. If the stream
     * becomes invalidated (e.g., SAF InputStream closed when the app is backgrounded),
     * the method re-opens the stream from the SAF URI and retries the failed read once.
     *
     * Reads the file in [CHUNK_SIZE]-byte blocks and uploads each one to the session URI.
     * After each successful chunk, the confirmed byte count is persisted to DataStore so
     * a future retry can resume from that point.
     *
     * @param context Android Context for ContentResolver access.
     * @param fileUri SAF URI of the file to upload.
     * @param sessionUri The resumable session URI.
     * @param offset The byte offset to start uploading from (0 for fresh uploads).
     * @param totalBytes The total file size in bytes.
     * @param progressListener Optional progress callback.
     * @return The Google Drive file ID of the completed upload.
     */
    /**
     * Result of a chunked upload, containing both the Drive file ID and the
     * MD5 checksum returned by Google (if available).
     */
    private data class ChunkedUploadResult(
        val driveFileId: String,
        val md5Checksum: String?,
    )

    private suspend fun uploadChunked(
        context: Context,
        fileUri: Uri,
        sessionUri: String,
        offset: Long,
        totalBytes: Long,
        progressListener: UploadProgressListener?,
    ): ChunkedUploadResult {
        var currentOffset = offset
        val buffer = ByteArray(CHUNK_SIZE)
        var noProgressCount = 0

        // Open the stream and skip to the starting offset if resuming.
        var stream = openStreamAtOffset(context, fileUri, currentOffset)
            ?: throw IllegalStateException("Cannot open backup file at $fileUri")

        // Report initial progress (important for resumed uploads so UI shows where we are).
        progressListener?.invoke(currentOffset, totalBytes)

        try {
            while (currentOffset < totalBytes) {
                // Read up to CHUNK_SIZE bytes from the stream.
                val bytesToRead = minOf(CHUNK_SIZE.toLong(), totalBytes - currentOffset).toInt()
                var bytesRead = readFully(stream, buffer, bytesToRead)

                // If the stream returned EOF, the SAF InputStream may have been invalidated
                // (e.g., app backgrounded). Re-open the stream at the current offset and retry.
                if (bytesRead <= 0) {
                    Log.w(TAG, "Stream EOF at offset $currentOffset, re-opening from SAF URI")
                    stream.close()
                    stream = openStreamAtOffset(context, fileUri, currentOffset)
                        ?: throw IllegalStateException(
                            "Cannot re-open backup file at $fileUri after stream failure"
                        )
                    bytesRead = readFully(stream, buffer, bytesToRead)
                    if (bytesRead <= 0) {
                        throw IllegalStateException(
                            "Unexpected end of stream at offset $currentOffset " +
                                "(expected $totalBytes bytes) even after re-opening"
                        )
                    }
                }

                // Upload this chunk. Use the exact bytes read (may be less than buffer size
                // for the final chunk).
                val chunkData = if (bytesRead < buffer.size) {
                    buffer.copyOf(bytesRead)
                } else {
                    buffer
                }

                val result = driveRepository.uploadChunk(
                    sessionUri = sessionUri,
                    chunkData = chunkData,
                    offset = currentOffset,
                    totalBytes = totalBytes,
                )

                when (result) {
                    is GoogleDriveService.ChunkUploadResult.InProgress -> {
                        if (result.confirmedBytes <= currentOffset) {
                            // No progress was made — Google did not accept the chunk.
                            noProgressCount++
                            Log.w(
                                TAG,
                                "No progress: server confirmed ${result.confirmedBytes} " +
                                    "bytes but we sent from offset $currentOffset " +
                                    "(attempt $noProgressCount/$MAX_NO_PROGRESS_RETRIES)"
                            )
                            if (noProgressCount >= MAX_NO_PROGRESS_RETRIES) {
                                throw IllegalStateException(
                                    "Upload stuck: server confirmed ${result.confirmedBytes} " +
                                        "bytes after $MAX_NO_PROGRESS_RETRIES attempts " +
                                        "at offset $currentOffset"
                                )
                            }
                            // Re-open the stream at currentOffset since the stream position
                            // has advanced past the data we need to re-send.
                            stream.close()
                            stream = openStreamAtOffset(context, fileUri, currentOffset)
                                ?: throw IllegalStateException(
                                    "Cannot re-open backup file at $fileUri"
                                )
                            continue
                        }
                        noProgressCount = 0
                        currentOffset = result.confirmedBytes
                        Log.d(TAG, "Chunk uploaded: offset=$currentOffset total=$totalBytes")
                        // Persist the updated byte count so a retry can resume here.
                        settingsRepository.updateResumableBytesUploaded(currentOffset)
                        progressListener?.invoke(currentOffset, totalBytes)
                    }
                    is GoogleDriveService.ChunkUploadResult.Complete -> {
                        Log.d(TAG, "Chunk uploaded: COMPLETE offset=$totalBytes total=$totalBytes")
                        progressListener?.invoke(totalBytes, totalBytes)
                        return ChunkedUploadResult(result.driveFileId, result.md5Checksum)
                    }
                }
            }
        } finally {
            stream.close()
        }

        // If we reach here, all bytes were uploaded but we did not get a 200 response.
        // This should not happen with well-formed uploads, but handle it gracefully.
        throw IllegalStateException(
            "All $totalBytes bytes uploaded but server did not confirm completion"
        )
    }

    /**
     * Verifies that the uploaded file's MD5 checksum matches the local file.
     *
     * If the server-side checksum is available, computes the local file's MD5 and compares.
     * A mismatch throws [IllegalStateException], which leaves the session saved for retry
     * (the catch block in [invoke] preserves the session on exception).
     *
     * If the server did not return a checksum (null), the verification is skipped with a warning.
     *
     * @param context Android Context for ContentResolver access.
     * @param fileUri SAF URI of the local file.
     * @param remoteMd5 The MD5 checksum returned by Google Drive, or null.
     */
    private fun verifyChecksum(context: Context, fileUri: Uri, remoteMd5: String?) {
        if (remoteMd5 == null) {
            Log.w(TAG, "Server did not return MD5 checksum, skipping verification")
            return
        }
        val localMd5 = computeLocalMd5(context, fileUri)
        if (localMd5 == null) {
            Log.w(TAG, "Could not compute local file MD5, skipping verification")
            return
        }
        if (!localMd5.equals(remoteMd5, ignoreCase = true)) {
            throw IllegalStateException(
                "Checksum mismatch: local=$localMd5, remote=$remoteMd5. " +
                    "The uploaded file may be corrupted."
            )
        }
        Log.d(TAG, "Checksum verified: $localMd5")
    }

    /**
     * Computes the MD5 checksum of a local file via SAF.
     *
     * @param context Android Context for ContentResolver access.
     * @param fileUri SAF URI of the file.
     * @return The MD5 hex string (lowercase), or null if the file cannot be read.
     */
    private fun computeLocalMd5(context: Context, fileUri: Uri): String? {
        val stream = context.contentResolver.openInputStream(fileUri) ?: return null
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute local MD5", e)
            null
        } finally {
            stream.close()
        }
    }

    /**
     * Opens an InputStream for the given SAF URI and skips to the specified byte offset.
     *
     * @param context Android Context for ContentResolver access.
     * @param fileUri SAF URI of the file to open.
     * @param offset The byte offset to skip to (0 for the beginning).
     * @return The positioned InputStream, or null if the file cannot be opened.
     */
    private fun openStreamAtOffset(context: Context, fileUri: Uri, offset: Long): InputStream? {
        val stream = context.contentResolver.openInputStream(fileUri) ?: return null
        if (offset > 0) {
            skipBytes(stream, offset)
        }
        return stream
    }

    /**
     * Reads exactly [length] bytes from the InputStream into the buffer.
     *
     * InputStream.read() may return fewer bytes than requested. This method loops
     * until the requested number of bytes is read or the stream ends.
     *
     * @param stream The input stream to read from.
     * @param buffer The destination buffer.
     * @param length The number of bytes to read.
     * @return The actual number of bytes read, or -1 if the stream is at EOF.
     */
    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Int {
        var totalRead = 0
        while (totalRead < length) {
            val bytesRead = stream.read(buffer, totalRead, length - totalRead)
            if (bytesRead < 0) {
                return if (totalRead > 0) totalRead else -1
            }
            totalRead += bytesRead
        }
        return totalRead
    }

    /**
     * Skips [bytesToSkip] bytes in the InputStream.
     *
     * InputStream.skip() may skip fewer bytes than requested. This method loops
     * until all bytes are skipped. Falls back to reading and discarding if skip()
     * returns 0 (which some InputStream implementations do).
     *
     * @param stream The input stream to skip in.
     * @param bytesToSkip The number of bytes to skip.
     * @throws IllegalStateException if the stream ends before all bytes are skipped.
     */
    private fun skipBytes(stream: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuffer = ByteArray(8192)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                // Some InputStreams return 0 from skip(). Fall back to reading.
                val toRead = minOf(skipBuffer.size.toLong(), remaining).toInt()
                val read = stream.read(skipBuffer, 0, toRead)
                if (read < 0) {
                    throw IllegalStateException(
                        "Stream ended while skipping to resume offset " +
                            "(skipped ${bytesToSkip - remaining} of $bytesToSkip bytes)"
                    )
                }
                remaining -= read
            }
        }
        Log.d(TAG, "Skipped $bytesToSkip bytes to resume offset")
    }

    /**
     * Result of attempting to resume from a saved session.
     */
    private sealed interface ResumeAttemptResult {
        /** The upload completed successfully via resume. */
        data class Completed(val status: UploadStatus.Success) : ResumeAttemptResult

        /** The saved session is invalid and cannot be resumed. */
        data class SessionInvalid(val reason: String) : ResumeAttemptResult
    }

    companion object {
        private const val TAG = "PerformUpload"
        private const val MIME_TYPE = "application/octet-stream"

        /**
         * Chunk size for manual resumable uploads. Matches [GoogleDriveService.UPLOAD_CHUNK_SIZE]
         * (5 MB). Must be a multiple of 256 KB per Google's requirements, except for the
         * final chunk which can be any size.
         */
        private const val CHUNK_SIZE = GoogleDriveService.UPLOAD_CHUNK_SIZE

        /**
         * Maximum number of consecutive chunk uploads that make no progress before
         * we abort with an error instead of looping indefinitely.
         */
        private const val MAX_NO_PROGRESS_RETRIES = 3
    }
}
