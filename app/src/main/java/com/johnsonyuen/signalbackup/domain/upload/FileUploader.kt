package com.johnsonyuen.signalbackup.domain.upload

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import com.johnsonyuen.signalbackup.domain.model.SessionProgress
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** A file that is on Drive and verified against the local copy. */
data class VerifiedUpload(val file: DriveFileMeta, val md5: String, val bytesSent: Long)

/** The uploaded file's size or MD5 differs from the local file; the remote copy was trashed. */
class ChecksumMismatchException(message: String) : IOException(message)

/** The local file changed (or disappeared) since it was scanned or while it was uploading. */
class LocalFileChangedException(message: String) : IOException(message)

/** Uploads single local files to Drive (resumable for large files) and verifies them. */
@Singleton
class FileUploader @Inject constructor(
    private val drive: DriveRepository,
    private val sessions: UploadSessionRepository,
    private val localFiles: LocalFileSource,
    private val networkPolicy: NetworkPolicy,
    private val chunkUploader: ChunkUploader,
    private val verifier: UploadVerifier,
    private val md5Calculator: Md5Calculator,
    private val clock: Clock,
) {

    /**
     * Uploads one local file into [driveParentId] as [file].name, resuming the persisted session when it
     * matches (same relativePath, size, lastModified, parent, not expired, failureCount < MAX). Small files
     * (< 5 MiB) use DriveRepository.uploadSmallFile. Verifies size and MD5 against Drive; on mismatch
     * trashes the remote file, clears the session and throws ChecksumMismatchException.
     * [onProgress] receives bytes of THIS file confirmed by Drive so far.
     * Throws WifiRequiredException, NeedsConsentException, NotSignedInException, DriveHttpException,
     * IOException, LocalFileChangedException, ChecksumMismatchException, CancellationException.
     */
    suspend fun upload(
        file: LocalBackupFile,
        driveParentId: String,
        onProgress: (Long) -> Unit = {},
    ): VerifiedUpload {
        networkPolicy.ensureUploadAllowed()
        ensureUnchanged(file)
        if (file.sizeBytes < SMALL_FILE_THRESHOLD) return uploadSmall(file, driveParentId, onProgress)

        val existing = sessions.get()
        if (existing != null) {
            if (canResume(existing, file, driveParentId)) {
                resume(existing, file, onProgress)?.let { return it }
            } else {
                UploadLog.d("Discarding persisted session that doesn't match this file")
                sessions.clear()
            }
        }
        return uploadFresh(file, driveParentId, onProgress, allowRestart = true)
    }

    /** Local MD5 (lowercase hex) of a file, streaming; cancellable. */
    suspend fun md5(file: LocalBackupFile): String = md5Calculator.md5(file)

    private fun canResume(s: ResumableUploadSession, file: LocalBackupFile, parentId: String): Boolean =
        s.relativePath == file.relativePath &&
            s.totalBytes == file.sizeBytes &&
            s.localLastModifiedMillis == file.lastModifiedMillis &&
            s.driveParentId == parentId &&
            // A completed-but-unverified session no longer depends on the session URI's lifetime.
            (s.driveFileId != null || !s.isExpired(clock.millis())) &&
            s.failureCount < ResumableUploadSession.MAX_FAILURES

    /** Continues a persisted session; returns null when a fresh session is needed. */
    private suspend fun resume(
        session: ResumableUploadSession,
        file: LocalBackupFile,
        onProgress: (Long) -> Unit,
    ): VerifiedUpload? {
        try {
            val completedId = session.driveFileId
            if (completedId != null) {
                UploadLog.d("Verifying previously completed upload")
                val meta = drive.getFile(completedId)
                if (meta == null) {
                    sessions.clear()
                    return null
                }
                return finish(meta, file, md5(file), bytesSent = 0L, onProgress)
            }

            return when (val progress = drive.querySessionProgress(session.sessionUri, file.sizeBytes)) {
                SessionProgress.Expired -> {
                    UploadLog.d("Persisted session expired; starting over")
                    sessions.clear()
                    null
                }
                is SessionProgress.Complete -> {
                    sessions.markCompleted(progress.file.id, progress.file.md5Checksum)
                    finish(progress.file, file, md5(file), bytesSent = 0L, onProgress)
                }
                is SessionProgress.InProgress -> {
                    UploadLog.d("Resuming upload from Drive's confirmed offset")
                    sessions.updateBytesUploaded(progress.confirmedBytes)
                    onProgress(progress.confirmedBytes)
                    val result = runChunks(session.sessionUri, file, progress.confirmedBytes, null, onProgress)
                    finish(result.file, file, md5(file), result.bytesSent, onProgress)
                }
            }
        } catch (e: SessionExpiredException) {
            sessions.clear()
            return null
        } catch (e: IOException) {
            if (e is LocalFileChangedException || e is ChecksumMismatchException) throw e
            val failures = sessions.incrementFailureCount()
            if (failures >= ResumableUploadSession.MAX_FAILURES) {
                UploadLog.w("Abandoning upload session after $failures failed resumes")
                sessions.clear()
            }
            throw e
        }
    }

    private suspend fun uploadFresh(
        file: LocalBackupFile,
        parentId: String,
        onProgress: (Long) -> Unit,
        allowRestart: Boolean,
    ): VerifiedUpload {
        val sessionUri = drive.initiateResumableUpload(parentId, file.name, MIME_TYPE, file.sizeBytes)
        sessions.save(
            ResumableUploadSession(
                sessionUri = sessionUri,
                localFileUri = file.uri,
                relativePath = file.relativePath,
                fileName = file.name,
                totalBytes = file.sizeBytes,
                localLastModifiedMillis = file.lastModifiedMillis,
                bytesUploaded = 0L,
                driveParentId = parentId,
                createdAtMillis = clock.millis(),
            ),
        )
        onProgress(0L)
        val md5 = StreamingMd5()
        val result = try {
            runChunks(sessionUri, file, 0L, md5, onProgress)
        } catch (e: SessionExpiredException) {
            sessions.clear()
            if (allowRestart) return uploadFresh(file, parentId, onProgress, allowRestart = false)
            throw IOException("Upload session expired", e)
        }
        val localMd5 = md5.hexIfComplete(file.sizeBytes) ?: md5(file)
        return finish(result.file, file, localMd5, result.bytesSent, onProgress)
    }

    /** Streams from [startOffset] to completion and records the Drive file id immediately. */
    private suspend fun runChunks(
        sessionUri: String,
        file: LocalBackupFile,
        startOffset: Long,
        md5: StreamingMd5?,
        onProgress: (Long) -> Unit,
    ): ChunkRunResult {
        val result = try {
            localFiles.open(file.uri).use { reader ->
                chunkUploader.upload(
                    sessionUri = sessionUri,
                    reader = reader,
                    totalBytes = file.sizeBytes,
                    startOffset = startOffset,
                    md5 = md5,
                    onConfirmed = { confirmed ->
                        sessions.updateBytesUploaded(confirmed)
                        onProgress(confirmed)
                    },
                    periodicCheck = {
                        networkPolicy.ensureUploadAllowed()
                        ensureUnchanged(file)
                    },
                )
            }
        } catch (e: LocalFileChangedException) {
            sessions.clear()
            throw e
        }
        sessions.markCompleted(result.file.id, result.file.md5Checksum)
        return result
    }

    /** Post-upload checks shared by all paths; clears the session on any definitive outcome. */
    private suspend fun finish(
        meta: DriveFileMeta,
        file: LocalBackupFile,
        localMd5: String,
        bytesSent: Long,
        onProgress: (Long) -> Unit,
        usesSession: Boolean = true,
    ): VerifiedUpload {
        try {
            ensureUnchanged(file)
        } catch (e: LocalFileChangedException) {
            trashQuietly(meta.id)
            if (usesSession) sessions.clear()
            throw e
        }
        val verified = try {
            verifier.verify(meta, file.sizeBytes, localMd5)
        } catch (e: ChecksumMismatchException) {
            if (usesSession) sessions.clear()
            throw e
        }
        if (usesSession) sessions.clear()
        onProgress(file.sizeBytes)
        UploadLog.d("Upload verified")
        return VerifiedUpload(verified, localMd5.lowercase(), bytesSent)
    }

    private suspend fun uploadSmall(
        file: LocalBackupFile,
        parentId: String,
        onProgress: (Long) -> Unit,
    ): VerifiedUpload {
        val size = file.sizeBytes.toInt()
        val data = ByteArray(size)
        localFiles.open(file.uri).use { reader ->
            if (reader.readFully(data, size) != size) {
                throw LocalFileChangedException("Local file is shorter than expected")
            }
            if (reader.read(ByteArray(1), 0, 1) >= 0) {
                throw LocalFileChangedException("Local file is longer than expected")
            }
        }
        val localMd5 = md5Hex(data)
        onProgress(0L)
        val meta = drive.uploadSmallFile(parentId, file.name, MIME_TYPE, data)
        return finish(meta, file, localMd5, size.toLong(), onProgress, usesSession = false)
    }

    private suspend fun ensureUnchanged(file: LocalBackupFile) {
        val stat = localFiles.stat(file.uri)
            ?: throw LocalFileChangedException("Local file is no longer readable")
        val sizeChanged = stat.sizeBytes != null && stat.sizeBytes != file.sizeBytes
        val timeChanged = stat.lastModifiedMillis != null && stat.lastModifiedMillis != file.lastModifiedMillis
        if (sizeChanged || timeChanged) throw LocalFileChangedException("Local file changed since it was scanned")
    }

    private suspend fun trashQuietly(fileId: String) {
        try {
            drive.trashFile(fileId)
        } catch (e: IOException) {
            UploadLog.w("Could not trash remote file (${e.javaClass.simpleName})")
        }
    }

    companion object {
        /** Files below this size use a single multipart request. */
        const val SMALL_FILE_THRESHOLD = 5L * 1024 * 1024
        const val MIME_TYPE = "application/octet-stream"
    }
}
