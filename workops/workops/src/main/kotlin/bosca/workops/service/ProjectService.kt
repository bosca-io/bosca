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
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectInput
import bosca.workops.repository.ProgramPermissionRepository
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectKeyCounterRepository
import bosca.workops.repository.ProjectPermissionRepository
import bosca.workops.repository.ProjectRepository

@ServiceImplementation
class ProjectServiceImpl(
    private val repository: ProjectRepository,
    private val programRepository: ProgramRepository,
    private val keyCounterRepository: ProjectKeyCounterRepository,
    private val permissionRepository: ProjectPermissionRepository,
    private val programPermissionRepository: ProgramPermissionRepository,
    private val programPermissionEvaluator: ProgramPermissionEvaluator,
) : ProjectService {

    override suspend fun getPermissions(entity: Project): List<EntityPermission> =
        permissionRepository.getByProjectId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = permissionRepository.getByProjectIds(batch.keys)
        val grouped = permissions.groupBy { it.projectId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: Project,
        action: PermissionAction,
    ): Boolean {
        val program = programRepository.getById(entity.programId) ?: return false
        return programPermissionEvaluator.isAllowed(authentication, program, action)
    }

    override suspend fun listAll(): List<Project> = repository.getAll()

    override suspend fun listByProgram(programId: UUID, offset: Long, limit: Int): List<Project> =
        repository.getByProgram(programId, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    override suspend fun getById(id: UUID): Project? = repository.getById(id)

    override suspend fun getByKey(key: String): Project? = repository.getByKey(key)

    override suspend fun getByIds(ids: List<UUID>): List<Project> =
        if (ids.isEmpty()) emptyList() else repository.getByIds(ids)

    override suspend fun create(input: ProjectInput): Project = transaction {
        validateKey(input.key)
        val program = programRepository.getById(input.programId)
            ?: throw WorkOpsNotFoundException("Program", input.programId.toString())
        if (program.archivedAt != null) {
            throw WorkOpsArchivedException("Program", program.id)
        }
        if (repository.getByKey(input.key) != null) {
            throw WorkOpsConflictException("Project", input.key)
        }
        val saved = repository.add(
            Project(
                programId = input.programId,
                key = input.key,
                name = input.name,
                description = input.description,
                ownerProfileId = input.ownerProfileId,
                defaultTaskTypeSchemeId = input.defaultTaskTypeSchemeId ?: DEFAULT_TASK_TYPE_SCHEME_ID,
                defaultWorkflowSchemeId = DEFAULT_WORKFLOW_SCHEME_ID,
                defaultFieldConfigurationSchemeId = DEFAULT_FIELD_CONFIGURATION_SCHEME_ID,
                taskCreationFormSchemaKey = input.taskCreationFormSchemaKey ?: "workops.create-task",
            )
        )
        keyCounterRepository.initialize(saved.id)
        val parentPermissions = programPermissionRepository.getByProgramId(input.programId)
        for (permission in parentPermissions) {
            permissionRepository.add(saved.id, permission.groupId, permission.action)
        }
        saved
    }

    override suspend fun reserveNextTaskSequence(projectId: UUID): Long =
        keyCounterRepository.reserveNext(projectId)
            ?: throw WorkOpsNotFoundException("Project", projectId.toString())

    override suspend fun update(id: UUID, input: ProjectInput, expectedVersion: Long): Project = transaction {
        val existing = repository.getById(id) ?: throw WorkOpsNotFoundException("Project", id.toString())
        if (existing.key != input.key) {
            throw WorkOpsValidationException("key", "Project key cannot be changed via update; use renameProject")
        }
        if (existing.programId != input.programId) {
            throw WorkOpsValidationException("programId", "Project cannot be re-parented via update; use projects.move")
        }
        repository.update(
            id = id,
            name = input.name,
            description = input.description,
            ownerProfileId = input.ownerProfileId,
            defaultTaskTypeSchemeId = input.defaultTaskTypeSchemeId,
            taskCreationFormSchemaKey = input.taskCreationFormSchemaKey ?: existing.taskCreationFormSchemaKey,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Project", id)
    }

    override suspend fun move(id: UUID, programId: UUID, expectedVersion: Long): Project = transaction {
        val existing = repository.getById(id)
            ?: throw WorkOpsNotFoundException("Project", id.toString())
        val targetProgram = programRepository.getById(programId)
            ?: throw WorkOpsNotFoundException("Program", programId.toString())
        if (targetProgram.archivedAt != null) {
            throw WorkOpsArchivedException("Program", targetProgram.id)
        }
        if (existing.programId == programId) {
            if (existing.version != expectedVersion) {
                throw OptimisticLockFailedException("Project", id)
            }
            return@transaction existing
        }
        repository.move(id, programId, expectedVersion)
            ?: throw OptimisticLockFailedException("Project", id)
    }

    override suspend fun archive(id: UUID, expectedVersion: Long): Project = transaction {
        repository.archive(id, expectedVersion)
            ?: run {
                val current = repository.getById(id)
                    ?: throw WorkOpsNotFoundException("Project", id.toString())
                if (current.archivedAt != null) current
                else throw OptimisticLockFailedException("Project", id)
            }
    }

    override suspend fun unarchive(id: UUID, expectedVersion: Long): Project = transaction {
        repository.unarchive(id, expectedVersion)
            ?: run {
                val current = repository.getById(id)
                    ?: throw WorkOpsNotFoundException("Project", id.toString())
                if (current.archivedAt == null) current
                else throw OptimisticLockFailedException("Project", id)
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

        /**
         * The seeded "Default Task Type Scheme" (R3). The id is
         * stable across the V2 migration's seed insert so projects
         * created without an explicit scheme reference fall through
         * to this scheme without an extra round-trip.
         */
        val DEFAULT_TASK_TYPE_SCHEME_ID: UUID =
            UUID.parse("10000000-0000-0000-0000-000000000001")

        /**
         * The seeded "Default Workflow Scheme" (R4). Pinned to the
         * V3 migration's seeded scheme that points at the seeded
         * "Default" workflow.
         */
        val DEFAULT_WORKFLOW_SCHEME_ID: UUID =
            UUID.parse("60000000-0000-0000-0000-000000000001")

        /**
         * The seeded "Default Field Configuration Scheme" (R5).
         * Empty by default — admins extend it as their projects
         * need typed custom fields.
         */
        val DEFAULT_FIELD_CONFIGURATION_SCHEME_ID: UUID =
            UUID.parse("80000000-0000-0000-0000-000000000001")
    }
}
