package bosca.content.transition.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Transition(
    @ColumnName("from_state_id")
    val fromStateId: String,
    @ColumnName("to_state_id")
    val toStateId: String,
    val description: String,
    @ColumnName("enter_job_name")
    val enterJobName: String?,
    @ColumnName("exit_job_name")
    val exitJobName: String?,
    @Contextual
    val configuration: JsonElement?
)
