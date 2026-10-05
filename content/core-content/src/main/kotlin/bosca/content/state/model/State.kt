package bosca.content.state.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class State(
    val id: String,
    val name: String,
    val description: String,
    val type: WorkflowStateType,
    @Contextual
    val configuration: JsonElement,
    @ColumnName("job_name")
    val jobName: String? = null
)

