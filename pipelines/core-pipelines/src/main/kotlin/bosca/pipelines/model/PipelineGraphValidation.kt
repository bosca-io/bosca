package bosca.pipelines.model

import kotlinx.serialization.json.JsonElement

/** Engine validation and deterministic editor layout for an unsaved graph; never executes nodes. */
data class PipelineGraphValidation(val valid: Boolean, val errors: List<String>, val graph: JsonElement)
