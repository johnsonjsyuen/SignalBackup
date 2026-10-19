package com.johnsonyuen.signalbackup.worker

import com.johnsonyuen.signalbackup.domain.model.FailureReason
import com.johnsonyuen.signalbackup.notification.BackupNotifierImpl
import com.johnsonyuen.signalbackup.notification.NotificationIntents
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupNotifierFixActionTest {

    @Test
    fun consentOpensConsentFlow() {
        assertEquals(NotificationIntents.ACTION_CONSENT, BackupNotifierImpl.fixActionFor(FailureReason.NEEDS_CONSENT))
    }

    @Test
    fun setupProblemsOpenSettings() {
        listOf(
            FailureReason.NOT_SIGNED_IN,
            FailureReason.LOCAL_FOLDER_NOT_SET,
            FailureReason.LOCAL_FOLDER_INACCESSIBLE,
            FailureReason.NO_BACKUP_FOUND,
            FailureReason.DRIVE_FOLDER_INACCESSIBLE,
        ).forEach { assertEquals(it.name, NotificationIntents.ACTION_SETTINGS, BackupNotifierImpl.fixActionFor(it)) }
    }

    @Test
    fun transientProblemsOpenHistory() {
        listOf(FailureReason.NETWORK, FailureReason.DRIVE_SERVER_ERROR, FailureReason.UNKNOWN)
            .forEach { assertEquals(it.name, NotificationIntents.ACTION_HISTORY, BackupNotifierImpl.fixActionFor(it)) }
    }
}
