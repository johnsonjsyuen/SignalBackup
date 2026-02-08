package com.johnsonyuen.signalbackup.data.repository

import java.io.InputStream

data class DriveFolder(val id: String, val name: String)

interface DriveRepository {

    suspend fun uploadFile(
        folderId: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
    ): String

    suspend fun listFolders(parentId: String?): List<DriveFolder>

    suspend fun createFolder(name: String, parentId: String?): DriveFolder
}
