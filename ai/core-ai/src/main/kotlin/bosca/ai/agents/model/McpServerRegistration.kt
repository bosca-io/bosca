@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.agents.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class McpServerRegistration(
    @Contextual
    val id: Uuid = Uuid.NIL,
    val key: String,
    val name: String,
    val description: String = "",
    @ColumnName("transport_type")
    val transportType: McpTransportType,
    @Contextual
    val configuration: JsonElement,
    val enabled: Boolean = true,
    @Contextual
    @ColumnName("git_repository_id")
    val gitRepositoryId: Uuid? = null,
    @ColumnName("git_path")
    val gitPath: String? = null,
    @ColumnName("last_sync_error")
    val lastSyncError: String? = null
)
