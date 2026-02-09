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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
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
    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Performs the upload operation, resuming from a saved session if one exists.
     *
     * Acts as a high-level orchestrator that delegates to focused private methods
     * for each phase of the upload workflow.
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

        // Step 1: Read and validate configuration.
        val config = readConfiguration()
            ?: return@withContext UploadStatus.Failed(ERROR_LOCAL_FOLDER_NOT_SET)

        val (localFolderUri, driveFolderId) = config

        // Separate null check so the error message is specific.
        if (driveFolderId == null) {
            return@withContext UploadStatus.Failed(ERROR_DRIVE_FOLDER_NOT_SET)
        }

        try {
            // Step 2: Try to resume from a saved session.
            val resumeStatus = attemptResumeFromSavedSession(
                context, driveFolderId, progressListener
            )
            if (resumeStatus != null) {
                return@withContext resumeStatus
            }

            // Step 3: Find the latest .backup file via SAF.
            val latestBackup = when (val result = findLatestBackupFile(context, localFolderUri)) {
                is BackupFileResult.Found -> result.file
                is BackupFileResult.FolderNotAccessible ->
                    return@withContext UploadStatus.Failed(ERROR_CANNOT_ACCESS_LOCAL_FOLDER)
                is BackupFileResult.NoBackupFiles ->
                    return@withContext UploadStatus.Failed(ERROR_NO_BACKUP_FILE_FOUND)
            }

            val fileName = latestBackup.name ?: DEFAULT_UNKNOWN_FILENAME
            val fileSize = latestBackup.length()

            // Step 4: Check for duplicate already in Drive.
            val duplicateStatus = checkForDuplicate(driveFolderId, fileName, fileSize)
            if (duplicateStatus != null) {
                return@withContext duplicateStatus
            }

            // Step 5: Initiate a new resumable upload session.
            val session = initiateUploadSession(driveFolderId, fileName, fileSize, latestBackup.uri)

            // Step 6: Upload the file in chunks.
            val uploadResult = uploadChunked(
                context = context,
                fileUri = latestBackup.uri,
                sessionUri = session.sessionUri,
                offset = 0L,
                totalBytes = fileSize,
                progressListener = progressListener,
            )

            // Step 7: Verify integrity and finalize.
            verifyChecksum(context, latestBackup.uri, uploadResult.md5Checksum)
            finalizeSuccessfulUpload(
                driveFileId = uploadResult.driveFileId,
                fileName = fileName,
                fileSizeBytes = fileSize,
                driveFolderId = driveFolderId,
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
            recordFailedUpload(driveFolderId, errorMsg)
            UploadStatus.Failed(errorMsg)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Configuration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Holds the user's configured local folder URI and Drive folder ID.
     *
     * @property localFolderUri The SAF URI of the local backup folder (never null here).
     * @property driveFolderId The Google Drive folder ID, or null if not yet configured.
     */
    private data class UploadConfiguration(
        val localFolderUri: String,
        val driveFolderId: String?,
    )

    /**
     * Reads the configured local folder URI and Drive folder ID from settings.
     *
     * @return An [UploadConfiguration] if at least the local folder is set,
     *         or null if the local folder URI is missing.
     */
    private suspend fun readConfiguration(): UploadConfiguration? {
        val localFolderUri = settingsRepository.localFolderUri.first()
        val driveFolderId = settingsRepository.driveFolderId.first()

        if (localFolderUri == null) {
            return null
        }
        return UploadConfiguration(localFolderUri, driveFolderId)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resume logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks for a saved resumable session and attempts to resume it.
     *
     * @return [UploadStatus.Success] if the resume completed the upload,
     *         or null if no valid session exists and a fresh upload should start.
     */
    private suspend fun attemptResumeFromSavedSession(
        context: Context,
        driveFolderId: String,
        progressListener: UploadProgressListener?,
    ): UploadStatus? {
        val savedSession = settingsRepository.getResumableSession() ?: run {
            Log.d(TAG, "No saved session found. Starting fresh upload.")
            return null
        }

        val resumeResult = tryResumeUpload(context, savedSession, driveFolderId, progressListener)

        return when (resumeResult) {
            is ResumeAttemptResult.Completed -> resumeResult.status
            is ResumeAttemptResult.SessionInvalid -> {
                Log.d(TAG, "Saved session invalid: ${resumeResult.reason}. Starting fresh.")
                settingsRepository.clearResumableSession()
                null
            }
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
            recordSuccessfulUpload(
                driveFileId = session.driveFileId,
                fileName = session.fileName,
                fileSizeBytes = session.totalBytes,
                driveFolderId = session.driveFolderId,
            )
            return ResumeAttemptResult.Completed(
                UploadStatus.Success(
                    fileName = session.fileName,
                    fileSizeBytes = session.totalBytes,
                )
            )
        }

        // Validate session against current state.
        val validationError = validateSession(context, session, currentDriveFolderId)
        if (validationError != null) {
            return validationError
        }

        // Query Google Drive for the actual progress of this session.
        val confirmedBytes = queryServerProgress(session)
            ?: return ResumeAttemptResult.SessionInvalid("Server rejected session URI (likely expired)")

        if (confirmedBytes is ServerProgressResult.AlreadyComplete) {
            settingsRepository.clearResumableSession()
            recordSuccessfulUpload(
                driveFileId = confirmedBytes.driveFileId,
                fileName = session.fileName,
                fileSizeBytes = session.totalBytes,
                driveFolderId = session.driveFolderId,
            )
            return ResumeAttemptResult.Completed(
                UploadStatus.Success(
                    fileName = session.fileName,
                    fileSizeBytes = session.totalBytes,
                )
            )
        }

        val resumeOffset = (confirmedBytes as ServerProgressResult.InProgress).confirmedBytes

        // Resume from the confirmed byte offset.
        Log.d(TAG, "Resuming upload from byte $resumeOffset / ${session.totalBytes}")
        settingsRepository.updateResumableBytesUploaded(resumeOffset)

        // uploadChunked manages its own stream lifecycle (open, skip, retry on EOF).
        val uploadResult = uploadChunked(
            context = context,
            fileUri = Uri.parse(session.localFileUri),
            sessionUri = session.sessionUri,
            offset = resumeOffset,
            totalBytes = session.totalBytes,
            progressListener = progressListener,
        )

        // Verify upload integrity via MD5 checksum.
        verifyChecksum(context, Uri.parse(session.localFileUri), uploadResult.md5Checksum)

        // Upload complete -- persist file ID first, then clear session.
        finalizeSuccessfulUpload(
            driveFileId = uploadResult.driveFileId,
            fileName = session.fileName,
            fileSizeBytes = session.totalBytes,
            driveFolderId = session.driveFolderId,
        )

        return ResumeAttemptResult.Completed(
            UploadStatus.Success(
                fileName = session.fileName,
                fileSizeBytes = session.totalBytes,
            )
        )
    }

    /**
     * Validates that a saved session is still usable for resuming.
     *
     * Checks session expiry, Drive folder match, local file existence, and file size.
     *
     * @return [ResumeAttemptResult.SessionInvalid] if validation fails, or null if valid.
     */
    private fun validateSession(
        context: Context,
        session: ResumableUploadSession,
        currentDriveFolderId: String,
    ): ResumeAttemptResult.SessionInvalid? {
        if (session.isExpired()) {
            return ResumeAttemptResult.SessionInvalid("Session expired (older than 6 days)")
        }

        if (session.driveFolderId != currentDriveFolderId) {
            return ResumeAttemptResult.SessionInvalid(
                "Drive folder changed (was ${session.driveFolderId}, now $currentDriveFolderId)"
            )
        }

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

        val currentFileSize = localFile.length()
        if (currentFileSize != session.totalBytes) {
            return ResumeAttemptResult.SessionInvalid(
                "File size changed (was ${session.totalBytes}, now $currentFileSize)"
            )
        }

        return null
    }

    /**
     * Wrapper around the server progress query result to distinguish between
     * in-progress and already-complete states without exposing GoogleDriveService types.
     */
    private sealed interface ServerProgressResult {
        data class InProgress(val confirmedBytes: Long) : ServerProgressResult
        data class AlreadyComplete(val driveFileId: String) : ServerProgressResult
    }

    /**
     * Queries Google Drive for the actual upload progress of a session.
     *
     * @return [ServerProgressResult] indicating progress or completion,
     *         or null if the session is expired/invalid on the server.
     */
    private suspend fun queryServerProgress(
        session: ResumableUploadSession,
    ): ServerProgressResult? {
        val progressResult = driveRepository.querySessionProgress(
            sessionUri = session.sessionUri,
            totalBytes = session.totalBytes,
        )

        return when (progressResult) {
            is GoogleDriveService.SessionProgressResult.Expired -> null
            is GoogleDriveService.SessionProgressResult.AlreadyComplete -> {
                Log.d(TAG, "Session query shows upload already complete, file ID: ${progressResult.driveFileId}")
                ServerProgressResult.AlreadyComplete(progressResult.driveFileId)
            }
            is GoogleDriveService.SessionProgressResult.InProgress -> {
                ServerProgressResult.InProgress(progressResult.confirmedBytes)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File discovery and deduplication
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of searching for the latest backup file.
     */
    private sealed interface BackupFileResult {
        /** The latest backup file was found. */
        data class Found(val file: DocumentFile) : BackupFileResult

        /** The local folder could not be accessed via SAF. */
        data object FolderNotAccessible : BackupFileResult

        /** The folder was accessible but no .backup files were found. */
        data object NoBackupFiles : BackupFileResult
    }

    /**
     * Finds the latest .backup file in the configured local folder via SAF.
     *
     * @param context Android Context for SAF access.
     * @param localFolderUri The SAF URI string of the local backup folder.
     * @return A [BackupFileResult] indicating whether the file was found, the folder
     *         was inaccessible, or no backup files exist.
     */
    private fun findLatestBackupFile(context: Context, localFolderUri: String): BackupFileResult {
        val uri = Uri.parse(localFolderUri)
        val folder = DocumentFile.fromTreeUri(context, uri)
        if (folder == null) {
            Log.w(TAG, "Cannot access local folder at $localFolderUri")
            return BackupFileResult.FolderNotAccessible
        }

        val latestBackup = folder.listFiles()
            .filter { it.isFile && it.name?.endsWith(BACKUP_FILE_EXTENSION) == true }
            .maxByOrNull { it.lastModified() }

        return if (latestBackup != null) {
            BackupFileResult.Found(latestBackup)
        } else {
            BackupFileResult.NoBackupFiles
        }
    }

    /**
     * Checks whether a file with the same name and size already exists in the Drive folder.
     *
     * Name-only matching is insufficient because a different file could have the same name.
     * If a duplicate is found, the upload is skipped and a success history entry is recorded.
     *
     * @return [UploadStatus.Success] if a duplicate exists and upload should be skipped,
     *         or null if no duplicate was found and upload should proceed.
     */
    private suspend fun checkForDuplicate(
        driveFolderId: String,
        fileName: String,
        fileSize: Long,
    ): UploadStatus.Success? {
        val existingFile = driveRepository.findFileByName(driveFolderId, fileName)
        if (existingFile != null && existingFile.sizeBytes == fileSize) {
            Log.d(TAG, "File '$fileName' (size=$fileSize) already exists in Drive folder (id=${existingFile.id}), skipping upload")
            recordSuccessfulUpload(
                driveFileId = existingFile.id,
                fileName = fileName,
                fileSizeBytes = fileSize,
                driveFolderId = driveFolderId,
            )
            return UploadStatus.Success(fileName = fileName, fileSizeBytes = fileSize)
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session initiation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiates a new resumable upload session with Google Drive and persists it.
     *
     * The session is saved immediately so that if the app is killed, the next attempt
     * can resume from the session URI.
     *
     * @return The newly created [ResumableUploadSession].
     */
    private suspend fun initiateUploadSession(
        driveFolderId: String,
        fileName: String,
        fileSize: Long,
        localFileUri: Uri,
    ): ResumableUploadSession {
        val sessionUri = driveRepository.initiateResumableUpload(
            folderId = driveFolderId,
            fileName = fileName,
            mimeType = MIME_TYPE,
            totalBytes = fileSize,
        )

        val session = ResumableUploadSession(
            sessionUri = sessionUri,
            localFileUri = localFileUri.toString(),
            fileName = fileName,
            bytesUploaded = 0L,
            totalBytes = fileSize,
            driveFolderId = driveFolderId,
            createdAtMillis = System.currentTimeMillis(),
        )
        settingsRepository.saveResumableSession(session)
        return session
    }

    // ─────────────────────────────────────────────────────────────────────────
    // History recording
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Persists the Drive file ID, clears the resumable session, and records a
     * successful upload in the history database.
     *
     * Persisting the file ID before clearing shrinks the crash window: if we crash
     * between these two calls, the session will have the file ID and Layer 2's early
     * check in tryResumeUpload will recover it.
     */
    private suspend fun finalizeSuccessfulUpload(
        driveFileId: String,
        fileName: String,
        fileSizeBytes: Long,
        driveFolderId: String,
    ) {
        settingsRepository.updateResumableDriveFileId(driveFileId)
        settingsRepository.clearResumableSession()
        recordSuccessfulUpload(driveFileId, fileName, fileSizeBytes, driveFolderId)
    }

    /**
     * Records a successful upload in the history database.
     */
    private suspend fun recordSuccessfulUpload(
        driveFileId: String,
        fileName: String,
        fileSizeBytes: Long,
        driveFolderId: String,
    ) {
        uploadHistoryRepository.insert(
            UploadHistoryEntity(
                timestamp = System.currentTimeMillis(),
                fileName = fileName,
                fileSizeBytes = fileSizeBytes,
                status = UploadHistoryEntity.STATUS_SUCCESS,
                errorMessage = null,
                driveFolderId = driveFolderId,
                driveFileId = driveFileId,
            )
        )
    }

    /**
     * Records a failed upload in the history database.
     */
    private suspend fun recordFailedUpload(driveFolderId: String, errorMsg: String) {
        uploadHistoryRepository.insert(
            UploadHistoryEntity(
                timestamp = System.currentTimeMillis(),
                fileName = DEFAULT_UNKNOWN_FILENAME,
                fileSizeBytes = 0,
                status = UploadHistoryEntity.STATUS_FAILED,
                errorMessage = errorMsg,
                driveFolderId = driveFolderId,
                driveFileId = null,
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chunked upload
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Result of a chunked upload, containing both the Drive file ID and the
     * MD5 checksum returned by Google (if available).
     */
    private data class ChunkedUploadResult(
        val driveFileId: String,
        val md5Checksum: String?,
    )

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
     * @return The [ChunkedUploadResult] containing the Drive file ID and checksum.
     */
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
                // Check if the coroutine has been cancelled (e.g., WorkManager stopped the worker).
                // This is the primary cancellation checkpoint -- without it, the upload loop
                // continues making network requests even after cancellation.
                coroutineContext.ensureActive()

                // Read the next chunk, handling SAF stream invalidation.
                val bytesToRead = minOf(CHUNK_SIZE.toLong(), totalBytes - currentOffset).toInt()
                val readResult = readChunkWithRetry(
                    context, fileUri, stream, buffer, bytesToRead, currentOffset, totalBytes
                )
                stream = readResult.stream

                // Upload this chunk to Google Drive.
                val chunkData = prepareChunkData(buffer, readResult.bytesRead)
                val result = driveRepository.uploadChunk(
                    sessionUri = sessionUri,
                    chunkData = chunkData,
                    offset = currentOffset,
                    totalBytes = totalBytes,
                )

                when (result) {
                    is GoogleDriveService.ChunkUploadResult.InProgress -> {
                        if (result.confirmedBytes <= currentOffset) {
                            // No progress was made -- Google did not accept the chunk.
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
     * Result of reading a chunk from the input stream, including the (possibly re-opened)
     * stream reference and the number of bytes read into the buffer.
     */
    private data class ChunkReadResult(
        val stream: InputStream,
        val bytesRead: Int,
    )

    /**
     * Reads a chunk from the stream, retrying once if the SAF InputStream was invalidated.
     *
     * If the stream returns EOF unexpectedly (e.g., app backgrounded invalidating the SAF
     * InputStream), this method closes the old stream, re-opens from the SAF URI at the
     * current offset, and retries the read once.
     *
     * @param context Android Context for ContentResolver access.
     * @param fileUri SAF URI for re-opening the stream.
     * @param currentStream The current input stream (may be invalidated).
     * @param buffer The destination buffer.
     * @param bytesToRead The number of bytes to read.
     * @param currentOffset The current byte offset in the file.
     * @param totalBytes The total file size in bytes (for error messages).
     * @return A [ChunkReadResult] with the (possibly new) stream and bytes read.
     * @throws IllegalStateException if the stream cannot be re-opened or still returns EOF.
     */
    private fun readChunkWithRetry(
        context: Context,
        fileUri: Uri,
        currentStream: InputStream,
        buffer: ByteArray,
        bytesToRead: Int,
        currentOffset: Long,
        totalBytes: Long,
    ): ChunkReadResult {
        var stream = currentStream
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

        return ChunkReadResult(stream, bytesRead)
    }

    /**
     * Prepares the byte array to upload as a chunk.
     *
     * If the bytes read are less than the buffer size (final chunk), returns a
     * trimmed copy. Otherwise returns the buffer directly.
     */
    private fun prepareChunkData(buffer: ByteArray, bytesRead: Int): ByteArray {
        return if (bytesRead < buffer.size) {
            buffer.copyOf(bytesRead)
        } else {
            buffer
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Checksum verification
    // ─────────────────────────────────────────────────────────────────────────

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
            val digest = MessageDigest.getInstance(MD5_ALGORITHM)
            val buffer = ByteArray(MD5_BUFFER_SIZE)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Stream utilities
    // ─────────────────────────────────────────────────────────────────────────

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
        val skipBuffer = ByteArray(SKIP_BUFFER_SIZE)
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

    // ─────────────────────────────────────────────────────────────────────────
    // Internal types
    // ─────────────────────────────────────────────────────────────────────────

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

        // MIME type for backup file uploads.
        private const val MIME_TYPE = "application/octet-stream"

        // File extension used to identify backup files in the local folder.
        private const val BACKUP_FILE_EXTENSION = ".backup"

        // Default file name used in error history entries when the actual name is unknown.
        private const val DEFAULT_UNKNOWN_FILENAME = "unknown"

        // MD5 digest algorithm name.
        private const val MD5_ALGORITHM = "MD5"

        // Buffer size for MD5 checksum computation (8 KB).
        private const val MD5_BUFFER_SIZE = 8192

        // Buffer size for skip fallback reads (8 KB).
        private const val SKIP_BUFFER_SIZE = 8192

        // Specific error messages for configuration and file discovery issues.
        private const val ERROR_LOCAL_FOLDER_NOT_SET =
            "Local backup folder not configured"
        private const val ERROR_DRIVE_FOLDER_NOT_SET =
            "Google Drive folder not configured"
        private const val ERROR_CANNOT_ACCESS_LOCAL_FOLDER =
            "Cannot access local folder"
        private const val ERROR_NO_BACKUP_FILE_FOUND =
            "No backup file found"

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
