@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A resource the Bosca MCP server can offer to MCP clients (MCP `resources` requests).
 *
 * Like [AgentTool], an `AgentResource` has exactly one implementation variant — the single
 * non-null impl field selects how its content is produced (the application layer enforces
 * this XOR):
 *  - [staticText] — inline static content.
 *  - [metadataId] — the metadata object serialized as JSON.
 *  - [documentMetadataId] (+ optional [documentVersion]) — the metadata's document body.
 *  - [contentMetadataId] — the metadata's file/blob content.
 *  - [scriptId] — the output of running a Bosca script.
 *  - [graphqlOperation] (+ optional [graphqlInputTransform] / [graphqlOutputTransform]) —
 *    the output of a pinned GraphQL operation.
 *
 * The entity is Git-syncable via the agent repo's `resources/` directory.
 */
@Serializable
data class AgentResource(
    @Contextual
    val id: Uuid = Uuid.NIL,
    val key: String,
    val name: String,
    val description: String,
    @Contextual
    val configuration: JsonElement? = null,
    @ColumnName("static_text")
    val staticText: String? = null,
    @Contextual
    @ColumnName("metadata_id")
    val metadataId: Uuid? = null,
    @Contextual
    @ColumnName("document_metadata_id")
    val documentMetadataId: Uuid? = null,
    @ColumnName("document_version")
    val documentVersion: Int? = null,
    @Contextual
    @ColumnName("content_metadata_id")
    val contentMetadataId: Uuid? = null,
    @Contextual
    @ColumnName("script_id")
    val scriptId: Uuid? = null,
    @ColumnName("graphql_operation")
    val graphqlOperation: String? = null,
    @ColumnName("graphql_input_transform")
    val graphqlInputTransform: String? = null,
    @ColumnName("graphql_output_transform")
    val graphqlOutputTransform: String? = null,
    @Contextual
    @ColumnName("git_repository_id")
    val gitRepositoryId: Uuid? = null,
    @ColumnName("git_path")
    val gitPath: String? = null,
    @ColumnName("last_sync_error")
    val lastSyncError: String? = null
)
