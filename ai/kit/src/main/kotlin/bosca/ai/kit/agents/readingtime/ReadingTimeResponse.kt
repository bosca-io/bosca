package bosca.ai.kit.agents.readingtime

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable

/**
 * The [ReadingTimeAgent]'s structured estimate: the document's [totalWordCount] and the estimated
 * [readingTimeInMinutes] (at ~200 words/minute).
 */
@Serializable
@LLMDescription("An estimated reading time for the document.")
data class ReadingTimeResponse(
    @property:LLMDescription("Total number of words in the text.")
    val totalWordCount: Int,
    @property:LLMDescription("Estimated reading time in whole minutes, assuming 200 words per minute.")
    val readingTimeInMinutes: Int,
)
