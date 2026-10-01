package bosca.pipelines.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A suspended node's staged output: the [value] it will emit on resume, plus an
 * optional [port] to emit it on. A plain gate stores `port = null` (its value flows along every
 * outbound edge); a node with branchable outcomes — e.g. a HubSpot write — stores the classified
 * outcome's port (`out`, `alreadyExists`, `error`) so the resume routes it down the right branch.
 */
@Serializable
data class PipelineRunNodeResult(
    val value: JsonElement,
    val port: String? = null,
)
