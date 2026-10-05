@file:OptIn(ExperimentalUuidApi::class)

package bosca.backup.graphql

import bosca.backup.BackupJob
import bosca.backup.BackupRecord
import bosca.backup.BackupRepository
import bosca.backup.ConflictStrategy
import bosca.backup.RestoreJob
import bosca.backup.enqueue
import bosca.content.metadata.service.MetadataService
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.storage.service.ObjectStorageService
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Marker object representing the BackupsMutation namespace in the GraphQL schema.
 */
object BackupsMutation

/**
 * Handles GraphQL mutation operations for initiating backups, restoring from
 * backup archives, and managing backup records. All operations require
 * super-admin group membership.
 */
@TypeController
class BackupsMutationController(
    private val backupRepository: BackupRepository,
    private val groupEvaluator: GroupEvaluator,
    private val metadataService: MetadataService,
    private val objectStorageService: ObjectStorageService
) : GraphQLController<BackupsMutation> {

    /**
     * Creates a new backup tracking record and enqueues a [BackupJob] for
     * asynchronous execution. Returns the backup record immediately so the
     * caller can poll for status updates.
     */
    @Field
    suspend fun create(
        authorization: AuthenticationContext,
        includeFiles: Boolean
    ): BackupRecord {
        groupEvaluator.verifyHasSaGroup(authorization)
        val id = Uuid.random()
        val record = backupRepository.create(id, includeFiles)
        BackupJob(backupId = id, includeFiles = includeFiles).enqueue()
        return record
    }

    /**
     * Initiates a restore from a previously completed backup. Looks up the
     * backup record, validates that it has a completed archive path, and
     * enqueues a [RestoreJob] for asynchronous execution.
     */
    @Field
    suspend fun restore(
        authorization: AuthenticationContext,
        backupId: UUID,
        conflictStrategy: ConflictStrategy
    ): Boolean {
        groupEvaluator.verifyHasSaGroup(authorization)
        val backup = backupRepository.getById(backupId) ?: error("Backup not found")
        val path = backup.path ?: error("Backup not yet completed")
        RestoreJob(backupPath = path, conflictStrategy = conflictStrategy).enqueue()
        return true
    }

    /**
     * Registers an already-uploaded metadata item as an imported backup.
     * The metadata must exist and have uploaded content. Creates a backup
     * record in "completed" state linked to the metadata and its storage path.
     */
    @Field
    suspend fun importBackup(
        authorization: AuthenticationContext,
        metadataId: UUID
    ): BackupRecord {
        groupEvaluator.verifyHasSaGroup(authorization)
        val metadata = metadataService.getById(metadataId) ?: error("Metadata not found")
        require(metadata.uploaded != null) { "Metadata content has not been uploaded yet" }
        val id = Uuid.random()
        val record = backupRepository.create(id, includeFiles = true)
        val backupPath = objectStorageService.getPath(metadata).toString()
        backupRepository.setMetadataId(id, metadataId)
        backupRepository.setPath(id, backupPath)
        backupRepository.updateStatus(id, "completed")
        return backupRepository.getById(id) ?: record
    }

    /**
     * Deletes a backup tracking record and its associated metadata item
     * and stored archive content.
     */
    @Field
    suspend fun delete(
        authorization: AuthenticationContext,
        id: UUID
    ): Boolean {
        groupEvaluator.verifyHasSaGroup(authorization)
        val backup = backupRepository.getById(id)
        if (backup?.metadataId != null) {
            val metadata = metadataService.getById(backup.metadataId)
            if (metadata != null) {
                val path = objectStorageService.getPath(metadata)
                objectStorageService.delete(path)
                metadataService.delete(metadata)
            }
        }
        backupRepository.delete(id)
        return true
    }
}
