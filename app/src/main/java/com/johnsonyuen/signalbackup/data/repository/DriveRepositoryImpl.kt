package com.johnsonyuen.signalbackup.data.repository

import com.johnsonyuen.signalbackup.data.remote.GoogleDriveService
import java.io.InputStream
import javax.inject.Inject

class DriveRepositoryImpl @Inject constructor(
    private val driveService: GoogleDriveService,
) : DriveRepository {

    override suspend fun uploadFile(
        folderId: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
    ): String {
        return driveService.uploadFile(folderId, fileName, mimeType, inputStream)
    }

    override suspend fun listFolders(parentId: String?): List<DriveFolder> {
        return driveService.listFolders(parentId)
    }

    override suspend fun createFolder(name: String, parentId: String?): DriveFolder {
        return driveService.createFolder(name, parentId)
    }
}
