package bosca.trait.model

import bosca.db.annotation.ColumnName
import kotlinx.serialization.Serializable

@Serializable
data class Trait(
    val id: String,
    val name: String,
    val description: String,
    @ColumnName("delete_workflow_id")
    val deleteWorkflowId: String?,
)
