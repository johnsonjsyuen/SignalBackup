package com.johnsonyuen.signalbackup.domain.upload

import com.johnsonyuen.signalbackup.data.repository.SettingsRepository
import com.johnsonyuen.signalbackup.domain.model.AppSettings
import com.johnsonyuen.signalbackup.domain.model.DriveHttpException
import com.johnsonyuen.signalbackup.domain.model.LocalBackupFile
import com.johnsonyuen.signalbackup.domain.model.ResumableUploadSession
import com.johnsonyuen.signalbackup.domain.model.WifiRequiredException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class FileUploaderTest {

    private val chunk = ChunkUploader.CHUNK_SIZE
    private val mib = 1024 * 1024

    private lateinit var local: InMemoryLocalFileSource
    private lateinit var drive: FakeDriveRepository
    private lateinit var sessions: FakeUploadSessionRepository
    private var wifiOnly = false
    private var unmetered = true
    private lateinit var uploader: FileUploader

    @Before
    fun setUp() {
        local = InMemoryLocalFileSource()
        drive = FakeDriveRepository()
        sessions = FakeUploadSessionRepository()
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } answers {
            AppSettings(
                localFolderUri = "content://local",
                driveFolderId = PARENT,
                driveFolderName = "Signal Backup",
                scheduleEnabled = true,
                scheduleHour = 3,
                scheduleMinute = 0,
                googleAccountEmail = "user@example.com",
                themeMode = "SYSTEM",
                wifiOnly = wifiOnly,
            )
        }
        val policy = NetworkPolicy(settings) { unmetered }
        uploader = FileUploader(
            drive = drive,
            sessions = sessions,
            localFiles = local,
            networkPolicy = policy,
            chunkUploader = ChunkUploader(drive, UploadRetryPolicy(Random(1))),
            verifier = UploadVerifier(drive),
            md5Calculator = Md5Calculator(local),
            clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
        )
    }

    private fun localFile(size: Int, name: String = "backup.db"): Pair<LocalBackupFile, ByteArray> {
        val content = Random(size).nextBytes(size)
        val uri = "content://local/$name"
        local.put(uri, content, LAST_MODIFIED)
        return LocalBackupFile(uri, "snap/$name", name, size.toLong(), LAST_MODIFIED) to content
    }

    private fun persistedSession(file: LocalBackupFile, uri: String, driveFileId: String? = null) =
        ResumableUploadSession(
            sessionUri = uri,
            localFileUri = file.uri,
            relativePath = file.relativePath,
            fileName = file.name,
            totalBytes = file.sizeBytes,
            localLastModifiedMillis = file.lastModifiedMillis,
            bytesUploaded = 0L,
            driveParentId = PARENT,
            createdAtMillis = NOW - 1000,
            driveFileId = driveFileId,
        )

    private suspend inline fun <reified T : Throwable> TestScope.assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("Expected ${T::class.simpleName} but got $e", e)
        }
        fail("Expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun freshMultiChunkUpload() = runTest {
        val (file, content) = localFile(2 * chunk + 3 * mib)
        val progress = mutableListOf<Long>()

        val result = uploader.upload(file, PARENT) { progress += it }

        assertArrayEquals(content, drive.receivedContent(result.file.id))
        assertEquals(md5Hex(content), result.md5)
        assertEquals(listOf(0L, chunk.toLong(), 2L * chunk), drive.chunkOffsets)
        assertEquals(content.size.toLong(), result.bytesSent)
        assertEquals(content.size.toLong(), progress.last())
        assertEquals(listOf(result.file.id), sessions.markedCompleted)
        assertNull(sessions.session)
        // MD5 came from the streamed chunks: only the upload read the file.
        assertEquals(1, local.opens)
    }

    @Test
    fun resumesFromConfirmedOffset() = runTest {
        val (file, content) = localFile(2 * chunk + mib)
        val remote = FakeDriveRepository.Session(PARENT, file.name, file.sizeBytes)
        System.arraycopy(content, 0, remote.content, 0, chunk)
        remote.persisted = chunk.toLong()
        drive.sessions["old"] = remote
        sessions.save(persistedSession(file, "old"))

        val result = uploader.upload(file, PARENT)

        assertEquals(0, drive.initiateCount)
        assertEquals(listOf(chunk.toLong(), 2L * chunk), drive.chunkOffsets)
        assertArrayEquals(content, drive.receivedContent(result.file.id))
        assertEquals(md5Hex(content), result.md5)
        assertNull(sessions.session)
    }

    @Test
    fun partialAcknowledgementReSeeks() = runTest {
        val (file, content) = localFile(2 * chunk + mib)
        val partial = 3 * mib
        drive.chunkBehavior = { call, _ -> if (call == 0) ChunkBehavior.Partial(partial) else ChunkBehavior.Normal }

        val result = uploader.upload(file, PARENT)

        assertEquals(listOf(0L, partial.toLong(), partial.toLong() + chunk), drive.chunkOffsets)
        assertArrayEquals(content, drive.receivedContent(result.file.id))
        assertEquals(md5Hex(content), result.md5)
    }

    @Test
    fun resumeWithoutRangeHeaderResendsChunk() = runTest {
        val (file, content) = localFile(chunk + mib)
        drive.chunkBehavior = { call, _ -> if (call == 0) ChunkBehavior.NoRange else ChunkBehavior.Normal }

        val result = uploader.upload(file, PARENT)

        assertEquals(listOf(0L, 0L, chunk.toLong()), drive.chunkOffsets)
        assertArrayEquals(content, drive.receivedContent(result.file.id))
    }

    @Test
    fun abortsAfterRepeatedNoProgress() = runTest {
        val (file, _) = localFile(chunk + mib)
        drive.chunkBehavior = { _, _ -> ChunkBehavior.NoRange }

        assertThrows<java.io.IOException> { uploader.upload(file, PARENT) }
        assertEquals(ChunkUploader.MAX_NO_PROGRESS, drive.chunkOffsets.size)
    }

    @Test
    fun serverErrorThenSuccessBacksOff() = runTest {
        val (file, content) = localFile(chunk + mib)
        drive.chunkBehavior = { call, _ ->
            if (call == 0) ChunkBehavior.Fail(DriveHttpException(503, null, "unavailable")) else ChunkBehavior.Normal
        }

        val result = uploader.upload(file, PARENT)

        assertTrue("backoff should wait at least 1s", currentTime >= 1000)
        assertEquals(1, drive.queryCount)
        assertEquals(listOf(0L, 0L, chunk.toLong()), drive.chunkOffsets)
        assertArrayEquals(content, drive.receivedContent(result.file.id))
    }

    @Test
    fun nonRetryableErrorPropagatesWithoutRetry() = runTest {
        val (file, _) = localFile(chunk + mib)
        drive.chunkBehavior = { _, _ -> ChunkBehavior.Fail(DriveHttpException(403, "storageQuotaExceeded", "quota")) }

        val e = assertThrows<DriveHttpException> { uploader.upload(file, PARENT) }
        assertTrue(e.isQuotaExceeded)
        assertEquals(1, drive.chunkOffsets.size)
        assertEquals(0L, currentTime)
    }

    @Test
    fun expiredSessionStartsFresh() = runTest {
        val (file, content) = localFile(chunk + mib)
        sessions.save(persistedSession(file, "expired-on-drive"))

        val result = uploader.upload(file, PARENT)

        assertEquals(1, drive.initiateCount)
        assertArrayEquals(content, drive.receivedContent(result.file.id))
        assertNull(sessions.session)
    }

    @Test
    fun locallyExpiredSessionIsNotQueried() = runTest {
        val (file, _) = localFile(chunk + mib)
        sessions.save(persistedSession(file, "old").copy(createdAtMillis = NOW - ResumableUploadSession.MAX_SESSION_AGE_MS - 1))

        uploader.upload(file, PARENT)

        assertEquals(0, drive.queryCount)
        assertEquals(1, drive.initiateCount)
    }

    @Test
    fun checksumMismatchTrashesAndThrows() = runTest {
        val (file, _) = localFile(chunk + mib)
        drive.md5Override = "00000000000000000000000000000000"

        assertThrows<ChecksumMismatchException> { uploader.upload(file, PARENT) }

        assertEquals(1, drive.trashed.size)
        assertNull(sessions.session)
    }

    @Test
    fun alreadyCompleteSessionIsVerified() = runTest {
        val (file, content) = localFile(chunk + mib)
        val meta = drive.addFile(file.name, content)
        sessions.save(persistedSession(file, "old", driveFileId = meta.id))

        val result = uploader.upload(file, PARENT)

        assertEquals(meta.id, result.file.id)
        assertEquals(md5Hex(content), result.md5)
        assertEquals(0L, result.bytesSent)
        assertTrue(drive.chunkOffsets.isEmpty())
        assertNull(sessions.session)
    }

    @Test
    fun alreadyCompleteSessionWithBadChecksumIsTrashed() = runTest {
        val (file, content) = localFile(chunk + mib)
        val meta = drive.addFile(file.name, content, md5 = "ffffffffffffffffffffffffffffffff")
        sessions.save(persistedSession(file, "old", driveFileId = meta.id))

        assertThrows<ChecksumMismatchException> { uploader.upload(file, PARENT) }

        assertEquals(listOf(meta.id), drive.trashed)
        assertNull(sessions.session)
    }

    @Test
    fun sessionCompleteOnQueryIsVerified() = runTest {
        val (file, content) = localFile(chunk + mib)
        val remote = FakeDriveRepository.Session(PARENT, file.name, file.sizeBytes)
        val meta = drive.addFile(file.name, content)
        remote.fileId = meta.id
        drive.sessions["old"] = remote
        sessions.save(persistedSession(file, "old"))

        val result = uploader.upload(file, PARENT)

        assertEquals(meta.id, result.file.id)
        assertEquals(listOf(meta.id), sessions.markedCompleted)
        assertTrue(drive.chunkOffsets.isEmpty())
    }

    @Test
    fun missingRemoteMd5FallsBackToSize() = runTest {
        val (file, content) = localFile(chunk + mib)
        val meta = drive.addFile(file.name, content, md5 = null)
        sessions.save(persistedSession(file, "old", driveFileId = meta.id))

        val result = uploader.upload(file, PARENT)

        assertEquals(meta.id, result.file.id)
        assertTrue(drive.trashed.isEmpty())
    }

    @Test
    fun fileChangedBeforeUploadThrows() = runTest {
        val (file, _) = localFile(chunk + mib)
        local.files.getValue(file.uri).lastModified = LAST_MODIFIED + 5

        assertThrows<LocalFileChangedException> { uploader.upload(file, PARENT) }
        assertEquals(0, drive.initiateCount)
    }

    @Test
    fun fileChangedMidUploadThrowsAndTrashes() = runTest {
        val (file, _) = localFile(2 * chunk + mib)
        drive.afterChunk = { call -> if (call == 0) { local.files.getValue(file.uri).lastModified = LAST_MODIFIED + 1 } }

        assertThrows<LocalFileChangedException> { uploader.upload(file, PARENT) }

        assertEquals(1, drive.trashed.size)
        assertNull(sessions.session)
    }

    @Test
    fun fileChangedDuringLongUploadDetectedByPeriodicCheck() = runTest {
        val (file, _) = localFile(5 * chunk + mib)
        drive.afterChunk = { call -> if (call == 0) { local.files.getValue(file.uri).lastModified = LAST_MODIFIED + 1 } }

        assertThrows<LocalFileChangedException> { uploader.upload(file, PARENT) }

        assertEquals(ChunkUploader.CHECK_EVERY_CHUNKS, drive.chunkOffsets.size)
        assertTrue(drive.trashed.isEmpty())
        assertNull(sessions.session)
    }

    @Test
    fun wifiRequiredBeforeStart() = runTest {
        val (file, _) = localFile(chunk + mib)
        wifiOnly = true
        unmetered = false

        assertThrows<WifiRequiredException> { uploader.upload(file, PARENT) }
        assertEquals(0, drive.initiateCount)
    }

    @Test
    fun wifiLostMidUploadKeepsSession() = runTest {
        val (file, _) = localFile(5 * chunk + mib)
        wifiOnly = true
        drive.afterChunk = { call -> if (call == 1) { unmetered = false } }

        assertThrows<WifiRequiredException> { uploader.upload(file, PARENT) }

        assertEquals(ChunkUploader.CHECK_EVERY_CHUNKS, drive.chunkOffsets.size)
        assertEquals(ChunkUploader.CHECK_EVERY_CHUNKS.toLong() * chunk, sessions.session?.bytesUploaded)
    }

    @Test
    fun smallFileUsesSingleRequest() = runTest {
        val (file, content) = localFile(mib)

        val result = uploader.upload(file, PARENT)

        assertEquals(1, drive.smallUploads)
        assertEquals(0, drive.initiateCount)
        assertArrayEquals(content, drive.receivedContent(result.file.id))
        assertEquals(md5Hex(content), result.md5)
        assertNull(sessions.session)
    }

    @Test
    fun smallFileChecksumMismatchTrashes() = runTest {
        val (file, _) = localFile(mib)
        drive.md5Override = "00000000000000000000000000000000"

        assertThrows<ChecksumMismatchException> { uploader.upload(file, PARENT) }
        assertEquals(1, drive.trashed.size)
    }

    private companion object {
        const val PARENT = "parent"
        const val NOW = 1_800_000_000_000L
        const val LAST_MODIFIED = 1_700_000_000_000L
    }
}
