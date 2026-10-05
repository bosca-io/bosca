@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class AgentTool(
    @Contextual
    val id: Uuid = Uuid.NIL,
    val key: String,
    val name: String,
    val description: String,
    @Contextual
    val configuration: JsonElement? = null,
    @Contextual
    @ColumnName("script_id")
    val scriptId: Uuid? = null,
    @Contextual
    @ColumnName("mcp_server_id")
    val mcpServerId: Uuid? = null,
    @ColumnName("graphql_operation")
    val graphqlOperation: String? = null,
    @ColumnName("graphql_input_transform")
    val graphqlInputTransform: String? = null,
    @ColumnName("graphql_output_transform")
    val graphqlOutputTransform: String? = null,
    @Contextual
    @ColumnName("prompt_id")
    val promptId: Uuid? = null,
    @Contextual
    @ColumnName("model_id")
    val modelId: Uuid? = null,
    @Contextual
    @ColumnName("agent_id")
    val agentId: Uuid? = null,
    @Contextual
    @ColumnName("git_repository_id")
    val gitRepositoryId: Uuid? = null,
    @ColumnName("git_path")
    val gitPath: String? = null,
    @ColumnName("last_sync_error")
    val lastSyncError: String? = null
)
