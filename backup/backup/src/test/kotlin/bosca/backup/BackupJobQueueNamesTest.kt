package bosca.backup

import kotlin.test.Test
import kotlin.test.assertEquals

class BackupJobQueueNamesTest {

    @Test
    fun `BackupJobQueueNames constants are correct`() {
        assertEquals("backupQueue", BackupJobQueueNames.backupJobQueue)
        assertEquals("backupQueueRunner", BackupJobQueueNames.backupRunner)
        assertEquals("backup", BackupJobQueueNames.backupQueue)
    }

    @Test
    fun `BackupJobQueueNames queue name differs from job queue name`() {
        val jobQueue = BackupJobQueueNames.backupJobQueue
        val queue = BackupJobQueueNames.backupQueue
        assertEquals("backupQueue", jobQueue)
        assertEquals("backup", queue)
    }
}
