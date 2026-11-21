package bosca.workops.model.workflow

import kotlinx.serialization.Serializable

/** Header fields for a new empty workflow definition. */
@Serializable
data class CreateWorkflowInput(val name: String, val description: String? = null)
