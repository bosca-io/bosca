package bosca.trait.model

import kotlinx.serialization.Serializable

@Serializable
data class TraitInput(
    val id: String,
    val name: String,
    val description: String,
    val deleteWorkflowId: String?,
    val workflowIds: List<String>,
    val contentTypes: List<String>
)
