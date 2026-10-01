package bosca.ai.kit.agents.chat

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.KitRoute
import kotlinx.serialization.Serializable

/**
 * The [ChatAgent]'s structured result: either a conversational [text] reply, OR a hand-off — when the
 * request actually needs another Kit capability to *execute* it (write a document, answer a data
 * question), chat punts it up so the right agent runs it rather than attempting it here.
 */
@Serializable
@LLMDescription("A conversational reply, or a hand-off to another Kit capability that should execute the request.")
data class ChatResponse(
    @property:LLMDescription("The conversational reply to show the user. Keep it brief when handing off.")
    val text: String,
    @property:LLMDescription("Set ONLY when the request needs another capability to EXECUTE it: WRITE to author/edit a document, QUERY for a data/analytics question, SCRIPTURE to retrieve exact Bible text or recommend passages, GRAPHQL to look up or manage real platform data (what content/templates/collections/metadata exist or are available, or create/edit/delete entities). Leave null for an ordinary conversational reply.")
    val handOffTo: KitRoute? = null,
    @property:LLMDescription("Bible references to retrieve when handOffTo is SCRIPTURE. For topical recommendations choose relevant references, but never supply their text.")
    val references: List<String> = emptyList(),
    @property:LLMDescription("Requested Bible translation name or abbreviation when handOffTo is SCRIPTURE. Empty means the installed default translation.")
    val translation: String = "",
)
