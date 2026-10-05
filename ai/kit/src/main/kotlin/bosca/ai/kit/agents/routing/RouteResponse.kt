package bosca.ai.kit.agents.routing

import ai.koog.agents.core.tools.annotations.LLMDescription
import bosca.ai.kit.agents.KitRoute
import kotlinx.serialization.Serializable

/**
 * The [RouteAgent]'s structured decision: which [KitRoute] to take, the bits the WRITE and SCRIPTURE
 * paths need up front (title, Scripture references, translation), and a short [rationale]. The rationale is
 * what lets a future review/refinement pass take a *second look* at a route before the planner commits.
 */
@Serializable
@LLMDescription("How Kit should handle the user's request, with a brief rationale.")
data class RouteResponse(
    @property:LLMDescription("WRITE to author/edit a document; QUERY for a data/analytics question or to save/update/execute analytics queries, visualizations, and dashboards; SCRIPTURE to retrieve exact Bible text or recommend passages; DESCRIBE, TOPICS, or READING_TIME to generate that metadata for the document in context; SCRIPT to manage server-side scripts; PIPELINE to create/edit/validate/dry-run/activate/run an automation graph; IMAGE to generate or edit an image; GRAPHQL to inspect or manage other platform entities via the API; CHAT for anything else; CLARIFY when unsure.")
    val route: KitRoute,
    @property:LLMDescription("A concise document title (WRITE only).")
    val title: String = "",
    @property:LLMDescription("Scripture references to retrieve verbatim, e.g. [\"John 3:16\"] (WRITE or SCRIPTURE only). For a topical SCRIPTURE request, choose relevant references but never supply their text.")
    val references: List<String> = emptyList(),
    @property:LLMDescription("Requested Bible translation name or abbreviation, e.g. \"NIV\" or \"KJV\" (SCRIPTURE only). Empty means the installed default translation.")
    val translation: String = "",
    @property:LLMDescription("When route is CLARIFY, a specific question to ask the user to resolve the ambiguity.")
    val question: String = "",
    @property:LLMDescription("A brief reason for choosing this route.")
    val rationale: String = "",
)
