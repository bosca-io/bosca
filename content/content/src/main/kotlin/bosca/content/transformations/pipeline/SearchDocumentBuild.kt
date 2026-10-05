package bosca.content.transformations.pipeline

import bosca.configuration.service.ConfigurationService
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.search.model.SearchTransformConfiguration
import bosca.search.model.SearchTransformExpressions
import bosca.search.pipeline.SearchDocumentPipeline
import bosca.serialization.JsonConverter.toAny
import bosca.serialization.JsonConverter.toJsonElement
import com.dashjoin.jsonata.Jsonata
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared logic for the entity-specific **Build Search Document** nodes. Each node fetches the
 * entity's rich search context (its transform's `toContext`), then this helper applies the
 * node-configured JSONata (or, when blank, the platform's seeded `search` expression for that entity
 * type — so the default behavior matches the index job executors), or removes the entity when it is
 * not visible for the target index.
 *
 * All JSON conversion is native-safe: explicit serializers and the reflection-free
 * [com.dashjoin.jsonata.Jsonata] bridge (no `serializer<T>()` lookups).
 */
internal object SearchDocumentBuild {

    /**
     * The removal signal the Index node honors: a JSON object carrying the entity's content id and the
     * delete action. Emitted when an entity is not visible for the target index, so re-indexing an
     * entity that has become non-visible removes its stale document.
     */
    fun removalSignal(contentId: String): JsonElement = buildJsonObject {
        put(SearchDocumentPipeline.CONTENT_ID_FIELD, contentId)
        put(SearchDocumentPipeline.ACTION_FIELD, SearchDocumentPipeline.ACTION_DELETE)
    }

    /**
     * The effective JSONata for this build: the node's [expression] if set, else the configured
     * `search` expression selected by [select]. Applies it to [element]; a blank/absent expression
     * yields the raw context JSON unchanged.
     */
    suspend fun shape(
        context: PipelineContext,
        element: JsonElement,
        expression: String,
        select: (SearchTransformExpressions) -> String?,
    ): JsonElement {
        val expr = expression.ifBlank { configuredExpression(context, select) ?: "" }
        if (expr.isBlank()) return element
        return Jsonata.jsonata(expr).evaluate(element.toAny()).toJsonElement()
    }

    private suspend fun configuredExpression(
        context: PipelineContext,
        select: (SearchTransformExpressions) -> String?,
    ): String? {
        val configurations = provide<ConfigurationService>()
        val configuration = configurations.getByKey("search") ?: return null
        val value = configurations.getValue(configuration.id)?.takeIf { it !is JsonNull } ?: return null
        return select(context.json.decodeFromJsonElement(SearchTransformConfiguration.serializer(), value).expressions)
    }
}
