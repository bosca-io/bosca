package bosca.ai.kit.agents.readingtime

import kotlinx.serialization.Serializable

/** What the [ReadingTimeAgent] estimates a reading time for — the document's already-extracted plain [text]. */
@Serializable
data class ReadingTimeRequest(val text: String)
