package bosca.content.attributes.model

import kotlinx.serialization.Serializable

@Serializable
class TemplateWorkflow(
    val workflowId: String,
    val autoRun: Boolean,
)