package bosca.backup

import bosca.queue.annotations.IJobDefinition
import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/**
 * Defines a backup job that exports all database records and object storage content
 * into a portable ZIP archive. The archive is stored in the platform's object
 * storage and can later be used with [RestoreJob] to reconstruct the data on
 * the same or a different Bosca instance.
 */
@Serializable
data class BackupJob(
    /** Unique identifier for this backup operation, used to track progress and locate the result. */
    val backupId: UUID,
    /** If true, include binary files from object storage; if false, export only database records. */
    val includeFiles: Boolean = true
) : IJobDefinition
