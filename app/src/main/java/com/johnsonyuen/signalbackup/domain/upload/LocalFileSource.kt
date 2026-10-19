package com.johnsonyuen.signalbackup.domain.upload

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable

/** Size and lastModified of a local file as currently reported by its provider (null = unknown). */
data class LocalFileStat(val sizeBytes: Long?, val lastModifiedMillis: Long?)

/** Read access to local backup files; the SAF implementation is [SafLocalFileSource]. */
interface LocalFileSource {
    /** Current metadata, or null if the file no longer exists or can't be read. */
    suspend fun stat(uri: String): LocalFileStat?

    /** Opens a seekable reader; throws IOException if the file can't be opened. */
    suspend fun open(uri: String): SeekableReader
}

/** A random-access byte source. */
interface SeekableReader : Closeable {
    suspend fun seek(position: Long)

    /** Reads up to [length] bytes into [buffer] at [offset]; returns -1 at end of file. */
    suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Reads until [length] bytes are in `buffer[0 until length]` or EOF; returns the bytes read. */
suspend fun SeekableReader.readFully(buffer: ByteArray, length: Int): Int {
    var total = 0
    while (total < length) {
        currentCoroutineContext().ensureActive()
        val n = read(buffer, total, length - total)
        if (n < 0) break
        total += n
    }
    return total
}
