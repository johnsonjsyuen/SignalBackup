package com.johnsonyuen.signalbackup.domain.upload

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import kotlinx.coroutines.delay
import java.io.IOException
import javax.inject.Inject

/** Checks an uploaded Drive file against the local size and MD5; trashes it on mismatch. */
class UploadVerifier @Inject constructor(private val drive: DriveRepository) {

    /**
     * Returns the (possibly refreshed) metadata if size and MD5 match. A missing remote MD5 is
     * re-fetched a few times, then accepted on size alone. Throws [ChecksumMismatchException].
     */
    suspend fun verify(uploaded: DriveFileMeta, localSize: Long, localMd5: String): DriveFileMeta {
        uploaded.sizeBytes?.let { if (it != localSize) mismatch(uploaded.id, "size") }

        var meta = uploaded
        var fetches = 0
        while ((meta.md5Checksum == null || meta.sizeBytes == null) && fetches < MD5_FETCH_ATTEMPTS) {
            fetches++
            delay(MD5_FETCH_DELAY_MS * fetches)
            meta = drive.getFile(uploaded.id) ?: throw IOException("Uploaded file is no longer on Drive")
        }

        val remoteSize = meta.sizeBytes ?: throw IOException("Drive did not report the uploaded size")
        if (remoteSize != localSize) mismatch(meta.id, "size")

        val remoteMd5 = meta.md5Checksum
        if (remoteMd5 == null) {
            UploadLog.w("Drive returned no MD5; accepted on size match only")
            return meta
        }
        if (!remoteMd5.equals(localMd5, ignoreCase = true)) mismatch(meta.id, "MD5")
        return meta
    }

    private suspend fun mismatch(fileId: String, what: String): Nothing {
        UploadLog.w("Uploaded $what does not match local file; moving remote copy to trash")
        try {
            drive.trashFile(fileId)
        } catch (e: IOException) {
            UploadLog.w("Could not trash mismatched upload (${e.javaClass.simpleName})")
        }
        throw ChecksumMismatchException("Uploaded file $what does not match the local file")
    }

    private companion object {
        const val MD5_FETCH_ATTEMPTS = 3
        const val MD5_FETCH_DELAY_MS = 2_000L
    }
}
