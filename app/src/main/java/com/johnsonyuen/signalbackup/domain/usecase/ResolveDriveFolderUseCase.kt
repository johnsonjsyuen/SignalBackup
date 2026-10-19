package com.johnsonyuen.signalbackup.domain.usecase

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepository
import com.johnsonyuen.signalbackup.domain.model.NotSignedInException
import javax.inject.Inject

/** Outcome of [ResolveDriveFolderUseCase]. */
sealed interface DriveFolderResolution {
    val folderId: String
    val folderName: String

    data class Ok(override val folderId: String, override val folderName: String) : DriveFolderResolution

    /** A new "Signal Backup" folder was created (none configured, or old one inaccessible). */
    data class Relocated(
        override val folderId: String,
        override val folderName: String,
        val previousFolderName: String?,
    ) : DriveFolderResolution
}

/**
 * Ensures the configured Drive folder is usable under the `drive.file` scope; otherwise creates a
 * tagged "Signal Backup" folder in My Drive, saves it, and clears synced-file records and the upload session.
 */
class ResolveDriveFolderUseCase @Inject constructor(
    private val settings: SettingsRepository,
    private val drive: DriveRepository,
    private val syncedFiles: SyncedFileRepository,
    private val uploadSessions: UploadSessionRepository,
) {
    /** Throws NeedsConsentException / NotSignedInException / DriveHttpException / IOException. */
    suspend operator fun invoke(): DriveFolderResolution {
        val current = settings.current()
        if (current.googleAccountEmail == null) throw NotSignedInException()

        val configuredId = current.driveFolderId
        if (configuredId != null) {
            val meta = drive.getFile(configuredId)
            if (meta != null && meta.isFolder) {
                if (meta.name.isNotEmpty() && meta.name != current.driveFolderName) {
                    settings.setDriveFolder(meta.id, meta.name)
                }
                return DriveFolderResolution.Ok(meta.id, meta.name.ifEmpty { current.driveFolderName.orEmpty() })
            }
        }

        val folder = drive.createFolder(DEFAULT_FOLDER_NAME, parentId = null)
        settings.setDriveFolder(folder.id, folder.name)
        syncedFiles.clear()
        uploadSessions.clear()
        return DriveFolderResolution.Relocated(
            folderId = folder.id,
            folderName = folder.name,
            previousFolderName = if (configuredId != null) current.driveFolderName else null,
        )
    }

    companion object {
        const val DEFAULT_FOLDER_NAME = "Signal Backup"
    }
}
