package com.johnsonyuen.signalbackup.domain.upload

import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.MessageDigest
import javax.inject.Inject

private const val MD5 = "MD5"

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    val digits = "0123456789abcdef"
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xff
        chars[i * 2] = digits[v ushr 4]
        chars[i * 2 + 1] = digits[v and 0x0f]
    }
    return String(chars)
}

internal fun md5Hex(data: ByteArray, length: Int = data.size): String =
    MessageDigest.getInstance(MD5).apply { update(data, 0, length) }.digest().toHex()

/** Streams a local file through MD5. */
class Md5Calculator @Inject constructor(private val localFiles: LocalFileSource) {

    /** Lowercase hex MD5; throws [LocalFileChangedException] if the length differs from the scan. */
    suspend fun md5(file: LocalBackupFile): String {
        val digest = MessageDigest.getInstance(MD5)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        localFiles.open(file.uri).use { reader ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = reader.read(buffer, 0, buffer.size)
                if (n < 0) break
                digest.update(buffer, 0, n)
                total += n
            }
        }
        if (total != file.sizeBytes) {
            throw LocalFileChangedException("Local file length changed while hashing")
        }
        return digest.digest().toHex()
    }

    private companion object {
        const val BUFFER_SIZE = 1024 * 1024
    }
}

/**
 * MD5 of the bytes sent so far, tolerant of re-sends after re-seeking backwards: only bytes past
 * [digestedBytes] are hashed. A forward gap makes the result unavailable (caller re-hashes the file).
 */
class StreamingMd5 {
    private val digest = MessageDigest.getInstance(MD5)
    private var broken = false

    var digestedBytes: Long = 0L
        private set

    fun update(buffer: ByteArray, length: Int, fileOffset: Long) {
        if (broken) return
        val end = fileOffset + length
        if (end <= digestedBytes) return
        if (fileOffset > digestedBytes) {
            broken = true
            return
        }
        val skip = (digestedBytes - fileOffset).toInt()
        digest.update(buffer, skip, length - skip)
        digestedBytes = end
    }

    /** Hex digest if exactly [totalBytes] contiguous bytes were hashed, else null. */
    fun hexIfComplete(totalBytes: Long): String? =
        if (!broken && digestedBytes == totalBytes) digest.digest().toHex() else null
}
