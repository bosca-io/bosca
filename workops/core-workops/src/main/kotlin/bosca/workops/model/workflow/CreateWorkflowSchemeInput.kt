package bosca.workops.model.workflow

import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** A scheme starts with a default workflow and no per-task-type overrides. */
@Serializable
data class CreateWorkflowSchemeInput(val name: String, val description: String? = null, @Contextual val defaultWorkflowId: UUID)
