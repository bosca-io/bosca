package bosca.workops.service

import bosca.db.transaction
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsArchivedException
import bosca.workops.model.WorkOpsConflictException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.project.Program
import bosca.workops.model.project.ProgramInput
import bosca.workops.repository.PortfolioPermissionRepository
import bosca.workops.repository.PortfolioRepository
import bosca.workops.repository.ProgramPermissionRepository
import bosca.workops.repository.ProgramRepository

@ServiceImplementation
class ProgramServiceImpl(
    private val repository: ProgramRepository,
    private val portfolioRepository: PortfolioRepository,
    private val permissionRepository: ProgramPermissionRepository,
    private val portfolioPermissionRepository: PortfolioPermissionRepository,
    private val portfolioPermissionEvaluator: PortfolioPermissionEvaluator,
) : ProgramService {

    override suspend fun getPermissions(entity: Program): List<EntityPermission> =
        permissionRepository.getByProgramId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = permissionRepository.getByProgramIds(batch.keys)
        val grouped = permissions.groupBy { it.programId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: Program,
        action: PermissionAction,
    ): Boolean {
        val portfolio = portfolioRepository.getById(entity.portfolioId) ?: return false
        return portfolioPermissionEvaluator.isAllowed(authentication, portfolio, action)
    }

    override suspend fun listAll(): List<Program> = repository.getAll()

    override suspend fun listByPortfolio(portfolioId: UUID, offset: Long, limit: Int): List<Program> =
        repository.getByPortfolio(portfolioId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun getById(id: UUID): Program? = repository.getById(id)

    override suspend fun getByKey(portfolioId: UUID, key: String): Program? =
        repository.getByKey(portfolioId, key)

    override suspend fun getByIds(ids: List<UUID>): List<Program> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: ProgramInput): Program = transaction {
        validateKey(input.key)
        val portfolio = portfolioRepository.getById(input.portfolioId)
            ?: throw WorkOpsNotFoundException("Portfolio", input.portfolioId.toString())
        if (portfolio.archivedAt != null) {
            throw WorkOpsArchivedException("Portfolio", portfolio.id)
        }
        if (repository.getByKey(input.portfolioId, input.key) != null) {
            throw WorkOpsConflictException("Program", "${portfolio.key}-${input.key}")
        }
        val saved = repository.add(
            Program(
                portfolioId = input.portfolioId,
                key = input.key,
                name = input.name,
                description = input.description,
                ownerProfileId = input.ownerProfileId,
                startDate = input.startDate,
                targetDate = input.targetDate,
            )
        )
        val parentPermissions = portfolioPermissionRepository.getByPortfolioId(input.portfolioId)
        for (permission in parentPermissions) {
            permissionRepository.add(saved.id, permission.groupId, permission.action)
        }
        saved
    }

    override suspend fun update(id: UUID, input: ProgramInput, expectedVersion: Long): Program = transaction {
        val existing = repository.getById(id) ?: throw WorkOpsNotFoundException("Program", id.toString())
        if (existing.key != input.key) {
            throw WorkOpsValidationException("key", "Program key cannot be changed via update; use renameProgram")
        }
        if (existing.portfolioId != input.portfolioId) {
            throw WorkOpsValidationException("portfolioId", "Program cannot be re-parented via update; use moveProgram")
        }
        repository.update(
            id = id,
            name = input.name,
            description = input.description,
            ownerProfileId = input.ownerProfileId,
            startDate = input.startDate,
            targetDate = input.targetDate,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Program", id)
    }

    override suspend fun archive(id: UUID, expectedVersion: Long): Program = transaction {
        repository.archive(id, expectedVersion)
            ?: run {
                val current = repository.getById(id)
                    ?: throw WorkOpsNotFoundException("Program", id.toString())
                if (current.archivedAt != null) current
                else throw OptimisticLockFailedException("Program", id)
            }
    }

    override suspend fun unarchive(id: UUID, expectedVersion: Long): Program = transaction {
        repository.unarchive(id, expectedVersion)
            ?: run {
                val current = repository.getById(id)
                    ?: throw WorkOpsNotFoundException("Program", id.toString())
                if (current.archivedAt == null) current
                else throw OptimisticLockFailedException("Program", id)
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
    }
}
