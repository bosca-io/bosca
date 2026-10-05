package bosca.ai.kit.agents.pipeline

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/** A concise account of the pipeline work, including a Studio path when a pipeline was saved. */
@Serializable
@LLMDescription("The result of a pipeline authoring, dry-run, or execution request.")
data class PipelineResponse(
    val message: String,
    val pipelineId: String? = null,
    val editorPath: String? = null,
)
