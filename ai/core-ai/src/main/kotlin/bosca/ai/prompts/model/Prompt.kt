@file:OptIn(ExperimentalUuidApi::class)

package bosca.ai.prompts.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class Prompt(
    @Contextual
    val id: Uuid = Uuid.NIL,
    val key: String,
    val name: String,
    val description: String,
    @ColumnName("system_prompt")
    val systemPrompt: String,
    @ColumnName("user_prompt")
    val userPrompt: String,
    @ColumnName("input_type")
    val inputType: String,
    @ColumnName("output_type")
    val outputType: String,
    @Contextual
    val schema: JsonElement?,
    @Contextual
    @ColumnName("git_repository_id")
    val gitRepositoryId: Uuid? = null,
    @ColumnName("git_path")
    val gitPath: String? = null,
    @ColumnName("last_sync_error")
    val lastSyncError: String? = null
)
