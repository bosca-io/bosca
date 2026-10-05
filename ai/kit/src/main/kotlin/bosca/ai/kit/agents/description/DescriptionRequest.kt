package bosca.ai.kit.agents.description

import kotlinx.serialization.Serializable

/** What the [DescriptionAgent] summarizes — the document's already-extracted plain [text]. */
@Serializable
data class DescriptionRequest(val text: String)
