package bosca.attributes

import kotlinx.serialization.Serializable

@Serializable
data class TemplateWorkflowInput(
    val workflowId: String,
    val autoRun: Boolean,
)