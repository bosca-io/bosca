package bosca.workops.service

import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectInput

/**
 * Service surface for [Project] (R1). The service is also responsible
 * for the per-project task-key counter row in
 * `workops.project_key_counter`: every project create initializes the
 * counter at 0 inside the same transaction so the first task minted
 * against the project receives `{KEY}-1` without any race window.
 *
 * R1 forbids deleting a non-empty parent at the database layer (FK
 * `ON DELETE RESTRICT`); the matching service-level rule uses
 * `archive` / `unarchive` instead. Hard-delete is a Phase 7
 * permission-gated operation that runs only after every child task is
 * already gone.
 */
interface ProjectService : PermissionService<Project, UUID> {

    suspend fun listAll(): List<Project>

    suspend fun listByProgram(programId: UUID, offset: Long, limit: Int): List<Project>

    suspend fun getById(id: UUID): Project?

    suspend fun getByKey(key: String): Project?

    suspend fun getByIds(ids: List<UUID>): List<Project>

    suspend fun create(input: ProjectInput): Project

    /**
     * Atomically reserves the next task sequence for [projectId]. The owning project service keeps
     * task-key allocation behind the project aggregate instead of exposing its counter repository.
     */
    suspend fun reserveNextTaskSequence(projectId: UUID): Long

    suspend fun update(id: UUID, input: ProjectInput, expectedVersion: Long): Project

    /**
     * Moves a project to [programId] while preserving its key, children, and
     * project-scoped permissions.
     */
    suspend fun move(id: UUID, programId: UUID, expectedVersion: Long): Project

    suspend fun archive(id: UUID, expectedVersion: Long): Project

    suspend fun unarchive(id: UUID, expectedVersion: Long): Project
}
