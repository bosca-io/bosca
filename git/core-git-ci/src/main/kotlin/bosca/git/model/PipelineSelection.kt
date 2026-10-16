package bosca.git.model

import bosca.serialization.UUID
import kotlinx.serialization.Serializable

/** The run selected for one YAML file; null means that file was intentionally skipped. */
@Serializable
data class PipelineSelection(val filePath: String, val runId: UUID? = null)
