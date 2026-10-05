package bosca.ai.kit.agents

import ai.koog.agents.planner.goap.GoapAgentState
import bosca.content.metadata.model.BibleChapter
import bosca.documents.Content
import kotlinx.serialization.Serializable

/**
 * The path Kit's planner sends a request down — decided by the `route` action. [CLARIFY] is the
 * "don't assume" route: when the router lacks the information to route confidently, it asks the user.
 * [DESCRIBE]/[TOPICS]/[READING_TIME] generate metadata *for the document in context* and return it.
 * [SCRIPTURE] retrieves exact Bible text from the installed translation rather than model memory.
 * [SCRIPT] manages server-side Kotlin scripts. [PIPELINE] authors and safely verifies automation
 * graphs. [IMAGE] generates or
 * edits images via Gemini and stores them as content. [GRAPHQL] introspects and manages Bosca through
 * the platform's own GraphQL API (queries and content mutations), as the calling user.
 */
@Serializable
enum class KitRoute { WRITE, QUERY, SCRIPTURE, CHAT, CLARIFY, DESCRIBE, TOPICS, READING_TIME, SCRIPT, PIPELINE, IMAGE, GRAPHQL }

/**
 * Kit's GOAP world state, typed to Kit's own [KitRequest] / [KitResponse]. The boolean flags
 * ([hasScripture]/[hasDocument]/[responded]) are what the A* planner reasons about; the heavy objects
 * ([scripture]/[content]) ride alongside and are never serialized through the LLM.
 *
 * [responded] is the planning signal a terminal action flips to say "I will produce an answer" — so a
 * `belief` can predict the goal is reachable without fabricating a placeholder [response]; the real
 * [response] payload is attached by the action's `execute`. [isReplyable] (computed) is the planner's
 * goal: the state is done when it *can* answer, whatever the answer's shape.
 */
@Serializable
data class KitState(
    val request: KitRequest,
    val route: KitRoute? = null,
    val title: String = "",
    val references: List<String> = emptyList(),
    val translation: String = "",
    val clarification: String = "",
    val hasScripture: Boolean = false,
    val scripture: List<BibleChapter> = emptyList(),
    val hasDocument: Boolean = false,
    val content: Content? = null,
    val responded: Boolean = false,
    val response: KitResponse? = null,
) : GoapAgentState<KitRequest, KitResponse>() {

    /** The state can answer once a terminal action has [responded]. */
    val isReplyable: Boolean get() = responded

    override val agentInput: KitRequest = request

    override fun provideOutput(): KitResponse = response ?: KitResponse.Text("")
}
