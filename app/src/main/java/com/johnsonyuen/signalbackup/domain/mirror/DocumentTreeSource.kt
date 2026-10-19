package com.johnsonyuen.signalbackup.domain.mirror

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One child document returned by a SAF directory listing. */
data class DocumentEntry(
    val documentId: String,
    /** Document URI (built with the tree URI) as a string. */
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/** Thin abstraction over the SAF queries [BackupFolderScanner] needs (fakeable in tests). */
interface DocumentTreeSource {
    suspend fun hasPersistedReadPermission(treeUri: String): Boolean

    /** Throws IllegalArgumentException when [treeUri] isn't a tree URI. */
    suspend fun rootDocumentId(treeUri: String): String

    /**
     * Children of [parentDocumentId]; null when the provider returned no cursor.
     * Throws SecurityException when access was revoked.
     */
    suspend fun listChildren(treeUri: String, parentDocumentId: String): List<DocumentEntry>?
}

/** [DocumentTreeSource] using one ContentResolver query per directory. */
@Singleton
class SafDocumentTreeSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocumentTreeSource {

    override suspend fun hasPersistedReadPermission(treeUri: String): Boolean =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(treeUri)
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }

    override suspend fun rootDocumentId(treeUri: String): String =
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))

    override suspend fun listChildren(
        treeUri: String,
        parentDocumentId: String,
    ): List<DocumentEntry>? = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        val cursor = context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
            ?: return@withContext null
        cursor.use { c -> readEntries(tree, c) }
    }

    private fun readEntries(tree: Uri, c: Cursor): List<DocumentEntry> {
        val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
        val modCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val result = ArrayList<DocumentEntry>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            val id = c.getString(idCol) ?: continue
            result += DocumentEntry(
                documentId = id,
                uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                name = c.getString(nameCol) ?: "",
                isDirectory = c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol),
                lastModifiedMillis = if (c.isNull(modCol)) 0L else c.getLong(modCol),
            )
        }
        return result
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MirrorModule {
    @Binds
    abstract fun bindDocumentTreeSource(impl: SafDocumentTreeSource): DocumentTreeSource
}
