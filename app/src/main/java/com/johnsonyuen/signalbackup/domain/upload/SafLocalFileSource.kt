package com.johnsonyuen.signalbackup.domain.upload

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

/** Reads SAF documents through a file descriptor so readers can seek with FileChannel.position(). */
class SafLocalFileSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalFileSource {

    override suspend fun stat(uri: String): LocalFileStat? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                LocalFileStat(
                    sizeBytes = if (cursor.isNull(0)) null else cursor.getLong(0),
                    lastModifiedMillis = if (cursor.isNull(1)) null else cursor.getLong(1),
                )
            }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }
    }

    override suspend fun open(uri: String): SeekableReader = withContext(Dispatchers.IO) {
        val pfd = try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
        } catch (e: SecurityException) {
            throw IOException("No permission to read local file", e)
        } catch (e: IllegalArgumentException) {
            throw FileNotFoundException("Local file not found")
        } ?: throw FileNotFoundException("Local file could not be opened")
        SafSeekableReader(pfd)
    }
}

/** Seeks via the descriptor's FileChannel instead of InputStream.skip(). */
private class SafSeekableReader(pfd: ParcelFileDescriptor) : SeekableReader {
    // AutoCloseInputStream closes the descriptor together with the stream.
    private val stream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
    private val channel = stream.channel

    override suspend fun seek(position: Long) {
        withContext(Dispatchers.IO) { channel.position(position) }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        withContext(Dispatchers.IO) { stream.read(buffer, offset, length) }

    override fun close() {
        stream.close()
    }
}
