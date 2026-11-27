package bosca.workops.model.workflow

import kotlinx.serialization.Serializable

/** Versioned header edits leave the workflow's state and transition graph unchanged. */
@Serializable
data class UpdateWorkflowInput(val name: String, val description: String? = null, val expectedVersion: Long)
