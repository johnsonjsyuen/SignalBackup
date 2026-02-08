package com.johnsonyuen.signalbackup.data.remote

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.johnsonyuen.signalbackup.data.local.datastore.SettingsDataStore
import com.johnsonyuen.signalbackup.data.repository.DriveFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

class GoogleDriveService @Inject constructor(
    private val credential: GoogleAccountCredential,
    private val settingsDataStore: SettingsDataStore,
) {

    private val drive: Drive by lazy {
        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential,
        )
            .setApplicationName("SignalBackup")
            .build()
    }

    private suspend fun ensureAccount() {
        val email = settingsDataStore.googleAccountEmail.first()
            ?: throw IllegalStateException("No Google account configured. Please sign in first.")
        credential.selectedAccountName = email
    }

    suspend fun uploadFile(
        folderId: String,
        fileName: String,
        mimeType: String,
        inputStream: InputStream,
    ): String = withContext(Dispatchers.IO) {
        ensureAccount()
        val fileMetadata = com.google.api.services.drive.model.File().apply {
            name = fileName
            parents = listOf(folderId)
        }
        val mediaContent = InputStreamContent(mimeType, inputStream)
        val file = drive.files().create(fileMetadata, mediaContent)
            .setFields("id")
            .execute()
        file.id
    }

    suspend fun listFolders(parentId: String?): List<DriveFolder> = withContext(Dispatchers.IO) {
        ensureAccount()
        val query = buildString {
            append("mimeType = 'application/vnd.google-apps.folder' and trashed = false")
            if (parentId != null) {
                append(" and '$parentId' in parents")
            } else {
                append(" and 'root' in parents")
            }
        }
        val result = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id, name)")
            .setOrderBy("name")
            .execute()
        result.files?.map { DriveFolder(it.id, it.name) } ?: emptyList()
    }

    suspend fun createFolder(name: String, parentId: String?): DriveFolder =
        withContext(Dispatchers.IO) {
            ensureAccount()
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                this.name = name
                this.mimeType = "application/vnd.google-apps.folder"
                if (parentId != null) {
                    parents = listOf(parentId)
                }
            }
            val file = drive.files().create(fileMetadata)
                .setFields("id, name")
                .execute()
            DriveFolder(file.id, file.name)
        }
}
