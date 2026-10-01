package bosca.ai.agents.repository

import bosca.ai.agents.git.KeyIdEntry
import bosca.ai.agents.model.AgentResource
import bosca.db.annotation.Query
import bosca.db.annotation.Repository
import bosca.serialization.UUID

@Repository
interface AgentResourceRepository {

    @Query("select * from ai.agent_resources order by name")
    suspend fun getAll(): List<AgentResource>

    @Query("select * from ai.agent_resources where id = :id")
    suspend fun getById(id: UUID): AgentResource?

    @Query("select * from ai.agent_resources where key = :key")
    suspend fun getByKey(key: String): AgentResource?

    @Query("select * from ai.agent_resources where git_repository_id = :repositoryId and git_path = :gitPath")
    suspend fun getByGitRepository(repositoryId: UUID, gitPath: String): AgentResource?

    @Query("select key, id from ai.agent_resources")
    suspend fun getKeyIndex(): List<KeyIdEntry>

    @Query("insert into ai.agent_resources (key, name, description, configuration, static_text, metadata_id, document_metadata_id, document_version, content_metadata_id, script_id, graphql_operation, graphql_input_transform, graphql_output_transform, git_repository_id, git_path) values (:key, :name, :description, :configuration, :staticText, :metadataId, :documentMetadataId, :documentVersion, :contentMetadataId, :scriptId, :graphqlOperation, :graphqlInputTransform, :graphqlOutputTransform, :gitRepositoryId, :gitPath) returning *")
    suspend fun add(resource: AgentResource): AgentResource

    @Query("update ai.agent_resources set key = :key, name = :name, description = :description, configuration = :configuration, static_text = :staticText, metadata_id = :metadataId, document_metadata_id = :documentMetadataId, document_version = :documentVersion, content_metadata_id = :contentMetadataId, script_id = :scriptId, graphql_operation = :graphqlOperation, graphql_input_transform = :graphqlInputTransform, graphql_output_transform = :graphqlOutputTransform where id = :id returning *")
    suspend fun update(resource: AgentResource): AgentResource

    @Query("update ai.agent_resources set git_repository_id = :gitRepositoryId, git_path = :gitPath where id = :id")
    suspend fun linkToGit(id: UUID, gitRepositoryId: UUID?, gitPath: String?)

    @Query("update ai.agent_resources set last_sync_error = :lastSyncError where id = :id")
    suspend fun setSyncError(id: UUID, lastSyncError: String?)

    @Query("delete from ai.agent_resources where id = :id")
    suspend fun deleteById(id: UUID)
}
