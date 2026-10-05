package bosca.backup.graphql

import bosca.backup.BackupRecord
import bosca.backup.BackupRepository
import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID

/**
 * Marker object representing the Backups query namespace in the GraphQL schema.
 */
object Backups

/**
 * Handles read-only GraphQL operations for listing and inspecting backup records.
 * All operations require super-admin group membership to prevent unauthorized access
 * to system-level backup data.
 */
@TypeController
class BackupsController(
    private val backupRepository: BackupRepository,
    private val groupEvaluator: GroupEvaluator
) : GraphQLController<Backups> {

    companion object {
        private const val MAX_PAGE_SIZE = 100
    }

    /** Lists all backup records with pagination, ordered by creation date descending. */
    @Field
    suspend fun list(
        authorization: AuthenticationContext,
        offset: Int,
        limit: Int
    ): List<BackupRecord> {
        groupEvaluator.verifyHasSaGroup(authorization)
        return backupRepository.getAll(offset.toLong(), limit.coerceAtMost(MAX_PAGE_SIZE))
    }

    /** Retrieves a single backup record by its identifier. */
    @Field
    suspend fun backup(
        authorization: AuthenticationContext,
        id: UUID
    ): BackupRecord? {
        groupEvaluator.verifyHasSaGroup(authorization)
        return backupRepository.getById(id)
    }
}
