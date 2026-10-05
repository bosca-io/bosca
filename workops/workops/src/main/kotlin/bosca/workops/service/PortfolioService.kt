package bosca.workops.service

import bosca.db.transaction
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsConflictException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.PortfolioInput
import bosca.workops.repository.PortfolioPermissionRepository
import bosca.workops.repository.PortfolioRepository

@ServiceImplementation
class PortfolioServiceImpl(
    private val repository: PortfolioRepository,
    private val permissionRepository: PortfolioPermissionRepository,
    private val securityService: SecurityService,
) : PortfolioService {

    override suspend fun getPermissions(entity: Portfolio): List<EntityPermission> =
        permissionRepository.getByPortfolioId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = permissionRepository.getByPortfolioIds(batch.keys)
        val grouped = permissions.groupBy { it.portfolioId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun list(offset: Long, limit: Int): List<Portfolio> =
        repository.getAll(offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun getById(id: UUID): Portfolio? = repository.getById(id)

    override suspend fun getByKey(key: String): Portfolio? = repository.getByKey(key)

    override suspend fun getByIds(ids: List<UUID>): List<Portfolio> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: PortfolioInput): Portfolio = transaction {
        validateKey(input.key)
        if (repository.getByKey(input.key) != null) {
            throw WorkOpsConflictException("Portfolio", input.key)
        }
        val saved = repository.add(
            Portfolio(
                key = input.key,
                name = input.name,
                description = input.description,
                ownerProfileId = input.ownerProfileId,
            )
        )
        seedDefaultPermissions(saved.id)
        saved
    }

    override suspend fun update(id: UUID, input: PortfolioInput, expectedVersion: Long): Portfolio = transaction {
        val existing = repository.getById(id) ?: throw WorkOpsNotFoundException("Portfolio", id.toString())
        if (existing.key != input.key) {
            // R1 implementation decision: portfolio key renames go through
            // a dedicated rename flow that updates the project key counter.
            // Plain update mutations only mutate non-key fields.
            throw WorkOpsValidationException("key", "Portfolio key cannot be changed via update; use renamePortfolio")
        }
        repository.update(
            id = id,
            name = input.name,
            description = input.description,
            ownerProfileId = input.ownerProfileId,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Portfolio", id)
    }

    override suspend fun archive(id: UUID, expectedVersion: Long): Portfolio = transaction {
        // The repo's WHERE clause filters `archived_at is null`, so a
        // null return here is either an optimistic-lock failure OR an
        // already-archived row. Re-read to disambiguate so the error
        // names the failure mode.
        repository.archive(id, expectedVersion)
            ?: run {
                val current = repository.getById(id)
                    ?: throw WorkOpsNotFoundException("Portfolio", id.toString())
                if (current.archivedAt != null) current
                else throw OptimisticLockFailedException("Portfolio", id)
            }
    }

    override suspend fun unarchive(id: UUID, expectedVersion: Long): Portfolio = transaction {
        repository.unarchive(id, expectedVersion)
            ?: run {
                val current = repository.getById(id)
                    ?: throw WorkOpsNotFoundException("Portfolio", id.toString())
                if (current.archivedAt == null) current
                else throw OptimisticLockFailedException("Portfolio", id)
            }
    }

    private suspend fun seedDefaultPermissions(portfolioId: UUID) {
        for ((groupName, actions) in DEFAULT_GROUP_PERMISSIONS) {
            val group = securityService.getGroupByName(groupName, GroupType.SYSTEM) ?: continue
            for (action in actions) {
                permissionRepository.add(portfolioId, group.id, action)
            }
        }
    }

    private fun validateKey(key: String) {
        if (!KEY_PATTERN.matches(key)) {
            throw WorkOpsValidationException("key", "must match $KEY_PATTERN")
        }
    }

    companion object {
        private val KEY_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,9}$")
        private const val MAX_PAGE = 100

        private val DEFAULT_GROUP_PERMISSIONS = mapOf(
            "administrators" to listOf(PermissionAction.MANAGE, PermissionAction.EDIT, PermissionAction.VIEW, PermissionAction.LIST),
            "managers" to listOf(PermissionAction.MANAGE, PermissionAction.EDIT, PermissionAction.VIEW, PermissionAction.LIST),
            "editors" to listOf(PermissionAction.EDIT, PermissionAction.VIEW, PermissionAction.LIST),
        )
    }
}
