package bosca.communications.service

import bosca.communications.model.BmlMessageProject
import bosca.communications.model.MessageBmlTemplate
import bosca.communications.model.ResolvedMessageTemplate
import bosca.communications.repository.BmlMessageProjectRepository
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation

@ServiceImplementation
class BmlMessageRegistryServiceImpl(
    private val projects: BmlMessageProjectRepository,
) : BmlMessageRegistryService {

    override suspend fun listProjects(): List<BmlMessageProject> = projects.getAll()

    override suspend fun getProject(projectKey: String): BmlMessageProject? = projects.get(projectKey)

    override suspend fun registerProject(projectKey: String, description: String?, repositoryId: UUID?): BmlMessageProject {
        require(projectKey.isNotBlank()) { "projectKey must not be blank" }
        return projects.upsert(projectKey, description, repositoryId)
    }

    override suspend fun pinProjectVersion(projectKey: String, version: String?): BmlMessageProject =
        projects.setPinnedVersion(projectKey, version?.takeIf { it.isNotBlank() })
            ?: throw IllegalArgumentException("unknown BML message project: '$projectKey'")

    override suspend fun removeProject(projectKey: String): Boolean = projects.delete(projectKey) > 0

    override suspend fun resolve(template: MessageBmlTemplate): ResolvedMessageTemplate =
        // Sending works without registration; a registered project contributes its pin.
        ResolvedMessageTemplate(template.project, template.templateKey, projects.get(template.project)?.pinnedVersion)
}
