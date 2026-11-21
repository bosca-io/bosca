package bosca.workops.model.workflow

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Versioned scheme header/default edits preserve its stored per-task-type overrides. */
@Serializable
data class UpdateWorkflowSchemeInput(val name: String, val description: String? = null, @Contextual val defaultWorkflowId: UUID, val expectedVersion: Long)
