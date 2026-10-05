package bosca.trait.model

import kotlinx.serialization.Serializable

@Serializable
data class TraitWorkflow(
    val traitId: String,
    val workflowId: String
)

@Serializable
data class TraitContentType(
    val traitId: String,
    val contentType: String
)
