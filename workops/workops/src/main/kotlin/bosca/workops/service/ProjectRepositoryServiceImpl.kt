package bosca.workops.service

import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.project.ProjectRepository
import bosca.workops.repository.ProjectRepositoryRepository

@ServiceImplementation
class ProjectRepositoryServiceImpl(
    private val repository: ProjectRepositoryRepository,
) : ProjectRepositoryService {
    override suspend fun list(projectId: UUID): List<ProjectRepository> = repository.list(projectId)
    override suspend fun listByRepository(repositoryId: UUID): List<ProjectRepository> = repository.listByRepository(repositoryId)
    override suspend fun add(projectId: UUID, repositoryId: UUID): ProjectRepository = repository.add(projectId, repositoryId)
    override suspend fun remove(projectId: UUID, id: UUID) = repository.remove(projectId, id)
}
