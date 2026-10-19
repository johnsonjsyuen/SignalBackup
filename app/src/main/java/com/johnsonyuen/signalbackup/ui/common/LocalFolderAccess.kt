package com.johnsonyuen.signalbackup.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shows about the selected Signal backup folder. */
data class LocalFolderInfo(
    /** Display name from SAF, or null when unreadable. */
    val name: String?,
    val accessible: Boolean,
    /** True when the folder contains `signal-*.backup` files or `signal-backup-*` snapshot folders. */
    val hasSignalBackups: Boolean,
)

/** SAF helpers for the selected local folder (kept out of ViewModels so they stay JVM-testable). */
@Singleton
class LocalFolderAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Reads the folder's name and checks whether it holds Signal backups. */
    suspend fun inspect(uriString: String): LocalFolderInfo = withContext(Dispatchers.IO) {
        try {
            val dir = DocumentFile.fromTreeUri(context, Uri.parse(uriString))
            if (dir == null || !dir.exists() || !dir.canRead()) {
                LocalFolderInfo(name = dir?.name, accessible = false, hasSignalBackups = false)
            } else {
                val hasBackups = dir.listFiles().any { isSignalBackupItem(it.name, it.isDirectory) }
                LocalFolderInfo(name = dir.name, accessible = true, hasSignalBackups = hasBackups)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local folder inspection failed: ${e.javaClass.simpleName}")
            LocalFolderInfo(name = null, accessible = false, hasSignalBackups = false)
        }
    }

    /** Drops the persisted read grant for [uriString]. Never throws. */
    fun releasePermission(uriString: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "No persisted permission to release")
        }
    }

    companion object {
        private const val TAG = "LocalFolderAccess"

        /** Matches legacy `signal-*.backup` files and Signal 8.30+ `signal-backup-*` snapshot folders. */
        fun isSignalBackupItem(name: String?, isDirectory: Boolean): Boolean {
            if (name == null) return false
            return if (isDirectory) name.startsWith("signal-backup-")
            else name.startsWith("signal-") && name.endsWith(".backup")
        }
    }
}
