package com.johnsonyuen.signalbackup.domain.upload

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.domain.model.ChunkUploadResult
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.SessionProgress
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import javax.inject.Inject
import kotlin.math.min

/** Drive reported the resumable session as gone (404/410); the caller starts a new one. */
internal class SessionExpiredException : Exception("Resumable upload session expired")

/** Outcome of [ChunkUploader.upload]. */
data class ChunkRunResult(val file: DriveFileMeta, val bytesSent: Long)

/**
 * Sends a file to an open resumable session in 8 MiB chunks, always continuing from the offset Drive
 * confirms, retrying transient failures with backoff and re-syncing via querySessionProgress.
 */
class ChunkUploader @Inject constructor(
    private val drive: DriveRepository,
    private val retryPolicy: UploadRetryPolicy,
) {

    /**
     * Uploads `[startOffset, totalBytes)` from [reader]. [md5] (if given) is fed every byte sent.
     * [onConfirmed] gets each confirmed offset; [periodicCheck] runs before every [CHECK_EVERY_CHUNKS]th chunk.
     */
    internal suspend fun upload(
        sessionUri: String,
        reader: SeekableReader,
        totalBytes: Long,
        startOffset: Long,
        md5: StreamingMd5?,
        onConfirmed: suspend (Long) -> Unit,
        periodicCheck: suspend () -> Unit,
    ): ChunkRunResult {
        val buffer = ByteArray(CHUNK_SIZE)
        var offset = startOffset
        var bytesSent = 0L
        var noProgress = 0
        var chunks = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            if (chunks > 0 && chunks % CHECK_EVERY_CHUNKS == 0) periodicCheck()
            chunks++

            val length = min(CHUNK_SIZE.toLong(), totalBytes - offset).coerceAtLeast(0L).toInt()
            if (length > 0) {
                reader.seek(offset)
                val read = reader.readFully(buffer, length)
                if (read < length) throw LocalFileChangedException("Local file is shorter than expected")
                md5?.update(buffer, length, offset)
            }

            val step = sendWithRetry(sessionUri, buffer, length, offset, totalBytes) { bytesSent += it }
            when (step) {
                is Step.Done -> return ChunkRunResult(step.file, bytesSent)
                is Step.Confirmed -> {
                    val confirmed = step.bytes
                    if (confirmed < 0 || confirmed > totalBytes) {
                        throw IOException("Drive confirmed an invalid byte range")
                    }
                    if (confirmed <= offset) {
                        noProgress++
                        UploadLog.w("No upload progress at chunk boundary ($noProgress)")
                        if (noProgress >= MAX_NO_PROGRESS) throw IOException("Upload is not making progress")
                    } else {
                        noProgress = 0
                    }
                    if (confirmed != offset + length) UploadLog.d("Re-seeking to Drive's confirmed offset")
                    offset = confirmed
                    onConfirmed(confirmed)
                }
            }
        }
    }

    /**
     * Sends one chunk; on retryable errors backs off, asks Drive for its offset, and either resends
     * (offset unchanged) or returns the new offset so the caller re-seeks. A zero [length] only queries.
     */
    private suspend fun sendWithRetry(
        sessionUri: String,
        buffer: ByteArray,
        length: Int,
        offset: Long,
        totalBytes: Long,
        onSent: (Long) -> Unit,
    ): Step {
        var attempt = 1
        var needQuery = length == 0
        while (true) {
            try {
                if (needQuery) {
                    when (val p = drive.querySessionProgress(sessionUri, totalBytes)) {
                        is SessionProgress.Complete -> return Step.Done(p.file)
                        SessionProgress.Expired -> throw SessionExpiredException()
                        is SessionProgress.InProgress ->
                            if (p.confirmedBytes != offset || length == 0) return Step.Confirmed(p.confirmedBytes)
                    }
                    needQuery = false
                }
                onSent(length.toLong())
                return when (val r = drive.uploadChunk(sessionUri, buffer, length, offset, totalBytes)) {
                    is ChunkUploadResult.InProgress -> Step.Confirmed(r.confirmedBytes)
                    is ChunkUploadResult.Complete -> Step.Done(r.file)
                }
            } catch (e: IOException) {
                if (e is DriveHttpException && (e.statusCode == 404 || e.statusCode == 410)) {
                    throw SessionExpiredException()
                }
                if (!retryPolicy.isRetryable(e) || attempt >= UploadRetryPolicy.MAX_ATTEMPTS) throw e
                val wait = retryPolicy.backoffMillis(attempt)
                UploadLog.w("Chunk attempt $attempt failed (${e.javaClass.simpleName}); retrying in ${wait}ms")
                delay(wait)
                attempt++
                needQuery = true
            }
        }
    }

    private sealed interface Step {
        data class Confirmed(val bytes: Long) : Step
        data class Done(val file: DriveFileMeta) : Step
    }

    companion object {
        /** 8 MiB: a multiple of Drive's required 256 KiB granularity. */
        const val CHUNK_SIZE = 32 * 256 * 1024
        const val CHECK_EVERY_CHUNKS = 4
        const val MAX_NO_PROGRESS = 3
    }
}
