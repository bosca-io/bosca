package bosca.backup

import bosca.queue.annotations.IJobDefinition
import kotlinx.serialization.Serializable

/**
 * Defines a restore job that imports data from a previously created backup archive
 * stored in the platform's object storage. Reads the archive, re-creates database
 * records, and re-uploads binary files to object storage. Conflict resolution is
 * controlled by [conflictStrategy].
 */
@Serializable
data class RestoreJob(
    /** The storage path of the backup ZIP archive to restore from. */
    val backupPath: String,
    /** How to handle records that already exist in the target database. */
    val conflictStrategy: ConflictStrategy = ConflictStrategy.SKIP
) : IJobDefinition
