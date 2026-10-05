package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.project.ProjectRepository

/** Manages the git repositories a project owns — read by the release relay. */
interface ProjectRepositoryService : Service {
    suspend fun list(projectId: UUID): List<ProjectRepository>
    /** Reverse lookup: the project links owning [repositoryId] — how a git-side event finds its programs. */
    suspend fun listByRepository(repositoryId: UUID): List<ProjectRepository>
    suspend fun add(projectId: UUID, repositoryId: UUID): ProjectRepository
    suspend fun remove(projectId: UUID, id: UUID)
}
