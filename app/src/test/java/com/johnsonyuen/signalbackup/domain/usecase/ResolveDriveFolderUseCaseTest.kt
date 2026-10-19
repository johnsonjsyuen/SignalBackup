package com.johnsonyuen.signalbackup.domain.usecase

import com.johnsonyuen.signalbackup.data.repository.DriveRepository
import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.data.repository.SyncedFileRepository
import com.johnsonyuen.signalbackup.data.repository.UploadSessionRepository
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.DriveFileMeta
import com.johnsonyuen.signalbackup.domain.model.DriveFolder
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.NotSignedInException
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ResolveDriveFolderUseCaseTest {

    private val settings = mockk<SettingsRepository>(relaxUnitFun = true)
    private val drive = mockk<DriveRepository>()
    private val syncedFiles = mockk<SyncedFileRepository>()
    private val sessions = mockk<UploadSessionRepository>()
    private val useCase = ResolveDriveFolderUseCase(settings, drive, syncedFiles, sessions)

    init {
        coEvery { syncedFiles.clear() } just Runs
        coEvery { sessions.clear() } just Runs
    }

    private fun settings(email: String? = "me@example.com", folderId: String? = null, folderName: String? = null) =
        AppSettings(
            localFolderUri = "content://x",
            driveFolderId = folderId,
            driveFolderName = folderName,
            scheduleEnabled = true,
            scheduleHour = 3,
            scheduleMinute = 0,
            googleAccountEmail = email,
            themeMode = "SYSTEM",
            wifiOnly = false,
        )

    private fun folderMeta(id: String, name: String) = DriveFileMeta(
        id = id, name = name, sizeBytes = null, md5Checksum = null, createdTimeMillis = null,
        isFolder = true, uploadedByApp = true, ownedByMe = true,
    )

    @Test
    fun accessibleFolder_isOk_andNothingCleared() = runTest {
        coEvery { settings.current() } returns settings(folderId = "f1", folderName = "Backups")
        coEvery { drive.getFile("f1") } returns folderMeta("f1", "Backups")

        val result = useCase()

        assertEquals(DriveFolderResolution.Ok("f1", "Backups"), result)
        coVerify(exactly = 0) { drive.createFolder(any(), any()) }
        coVerify(exactly = 0) { syncedFiles.clear() }
        coVerify(exactly = 0) { sessions.clear() }
        coVerify(exactly = 0) { settings.setDriveFolder(any(), any()) }
    }

    @Test
    fun renamedFolder_updatesStoredName() = runTest {
        coEvery { settings.current() } returns settings(folderId = "f1", folderName = "Old")
        coEvery { drive.getFile("f1") } returns folderMeta("f1", "New")

        val result = useCase()

        assertEquals(DriveFolderResolution.Ok("f1", "New"), result)
        coVerify { settings.setDriveFolder("f1", "New") }
    }

    @Test
    fun noFolderConfigured_createsAndRelocates() = runTest {
        coEvery { settings.current() } returns settings()
        coEvery { drive.createFolder("Signal Backup", null) } returns DriveFolder("new1", "Signal Backup")

        val result = useCase()

        assertEquals(DriveFolderResolution.Relocated("new1", "Signal Backup", previousFolderName = null), result)
        coVerify { settings.setDriveFolder("new1", "Signal Backup") }
        coVerify { syncedFiles.clear() }
        coVerify { sessions.clear() }
    }

    @Test
    fun inaccessibleFolder_createsAndRelocates() = runTest {
        coEvery { settings.current() } returns settings(folderId = "old", folderName = "My Old Folder")
        coEvery { drive.getFile("old") } returns null
        coEvery { drive.createFolder("Signal Backup", null) } returns DriveFolder("new1", "Signal Backup")

        val result = useCase()

        assertEquals(DriveFolderResolution.Relocated("new1", "Signal Backup", "My Old Folder"), result)
        coVerify { settings.setDriveFolder("new1", "Signal Backup") }
        coVerify { syncedFiles.clear() }
        coVerify { sessions.clear() }
    }

    @Test
    fun configuredItemIsNotFolder_relocates() = runTest {
        coEvery { settings.current() } returns settings(folderId = "f", folderName = "x")
        coEvery { drive.getFile("f") } returns folderMeta("f", "x").copy(isFolder = false)
        coEvery { drive.createFolder(any(), null) } returns DriveFolder("new1", "Signal Backup")

        val result = useCase()

        assertEquals("new1", (result as DriveFolderResolution.Relocated).folderId)
    }

    @Test
    fun notSignedIn_throws() = runTest {
        coEvery { settings.current() } returns settings(email = null)
        try {
            useCase()
            fail("expected NotSignedInException")
        } catch (e: NotSignedInException) {
        }
        coVerify(exactly = 0) { drive.createFolder(any(), any()) }
    }

    @Test
    fun driveError_propagates_withoutClearing() = runTest {
        coEvery { settings.current() } returns settings(folderId = "f1", folderName = "B")
        coEvery { drive.getFile("f1") } throws DriveHttpException(503, null, "HTTP 503")
        try {
            useCase()
            fail("expected DriveHttpException")
        } catch (e: DriveHttpException) {
            assertEquals(503, e.statusCode)
        }
        coVerify(exactly = 0) { syncedFiles.clear() }
        coVerify(exactly = 0) { drive.createFolder(any(), any()) }
    }
}
