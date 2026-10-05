package bosca.ai.kit.agents

import bosca.ai.chat.model.AnalyticsInvestigationStep
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * What Kit returns — polymorphic on purpose. An answer may be plain [Text] (a chat reply), a
 * reference to a [Document] Kit created or changed, or an [Analytics] result carrying the data and a
 * suggested visualization — and more kinds can be added later. Kit's output is "whatever shape fits
 * the request", which is why the planner is typed `AIAgentPlannerStrategy<KitRequest, KitResponse>`
 * rather than `<String, String>`. `@Serializable` (a sealed hierarchy → native kotlinx polymorphism,
 * no module registration) so it can be snapshotted as part of [KitState].
 */
@Serializable
sealed interface KitResponse {

    /** A plain-text answer — a chat reply, a simple acknowledgement, … */
    @Serializable
    data class Text(val text: String) : KitResponse

    /**
     * Kit needs more information before it can act — a clarifying [question] for the user. Distinct
     * from [Text] so a caller knows the turn isn't an answer but a request for input (the user's
     * reply comes back as the next [KitRequest]).
     */
    @Serializable
    data class Question(val text: String) : KitResponse

    /** A document Kit created or changed, identified by its [metadataId] (a UUID), with a human-facing [message]. */
    @Serializable
    data class Document(@Contextual val metadataId: UUID, val message: String) : KitResponse

    /**
     * An analytics answer: a one-sentence [summary] plus the tabular result ([columns]/[rows]), the
     * SQL [query] that produced it (the statement the SQL sub-agent actually executed, so the user
     * can see exactly where the numbers came from), and a suggested [visualization] (e.g. `"table"`,
     * `"bar"`, `"none"`) — the structured data the SQL sub-agent produced, carried through rather
     * than flattened to text. [query] defaults to empty so responses snapshotted before it existed
     * still decode.
     */
    @Serializable
    data class Analytics(
        val summary: String,
        val query: String = "",
        val columns: List<String> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        val visualization: String = "TABLE",
        val savedQueryId: String? = null,
        val savedQueryKey: String? = null,
        val visualizationId: String? = null,
        val dashboardId: String? = null,
        val investigation: List<AnalyticsInvestigationStep> = emptyList(),
    ) : KitResponse

    /** A generated meta [description] for the document in context (not persisted — returned to the caller). */
    @Serializable
    data class Description(val description: String) : KitResponse

    /** The [topics] Kit matched to the document in context, each a real topic collection (id + name). */
    @Serializable
    data class Topics(val topics: List<KitTopic>) : KitResponse

    /** An estimated reading time for the document in context: [totalWordCount] and [readingTimeInMinutes]. */
    @Serializable
    data class ReadingTime(val totalWordCount: Int, val readingTimeInMinutes: Int) : KitResponse

    /**
     * A result Kit produced via the platform GraphQL API: a human-facing [message], the actual [data]
     * the operation returned (the GraphQL response JSON), and a reference to *what that data is* — its
     * GraphQL [type] name and the [sdl] definition of that type — so a caller can interpret/render the
     * data (e.g. knowing it's a `Metadata`, with its schema) rather than just reading a sentence.
     */
    @Serializable
    data class GraphQL(
        val message: String,
        val data: JsonElement,
        // Serialized as "dataType": as a polymorphic KitResponse subtype it would otherwise collide with
        // the kit Json's "type" class discriminator (and the planner checkpoint serialization would fail).
        @SerialName("dataType") val type: String,
        val sdl: String,
    ) : KitResponse
}

/** A topic collection Kit matched to a document — its [id] and display [name]. */
@Serializable
data class KitTopic(@Contextual val id: UUID, val name: String)
