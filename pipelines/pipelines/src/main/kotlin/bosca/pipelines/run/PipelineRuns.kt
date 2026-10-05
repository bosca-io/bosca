@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.run

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.events.catalog.EventCatalogRegistrar
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.inputNode
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.JsonSchemaValidator
import bosca.pipelines.node.PipelineValue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.ExperimentalUuidApi

/**
 * Resolves an on-demand run payload into the pipeline's input value — shared by the GraphQL run/dry
 * run mutations and the REST endpoint. JSON-input pipelines ([InputNode.JSON_TYPE]) validate the
 * payload against the declared input schema and pass it through as JSON; event-typed pipelines
 * decode the payload via the Event Catalog's compiled serializer for [eventType] (defaulting to the
 * pipeline's accepted type). Throws with a caller-readable message on schema violations or an
 * undecodable payload.
 *
 * The run itself is driven durably by [bosca.pipelines.service.PipelineRunService.start] (with the
 * caller's principal) — manual and API runs are real durable runs (so they suspend/resume like any
 * other), recorded in run history by the durable machinery.
 */
@OptIn(InternalDI::class)
suspend fun resolvePipelineRunInput(
    pipeline: Pipeline,
    eventType: String?,
    payload: JsonElement,
    json: Json,
): PipelineValue {
    if (pipeline.acceptedInputType == InputNode.JSON_TYPE) {
        pipeline.inputNode?.schema?.let { schema ->
            val violations = JsonSchemaValidator.validate(payload, schema)
            check(violations.isEmpty()) {
                "Payload does not match the pipeline's input schema — ${violations.joinToString("; ")}"
            }
        }
        return PipelineValue.ofJson(payload)
    }
    val typeName = eventType ?: pipeline.acceptedInputType
    val serializer = ProviderRegistry.findAll(EventCatalogRegistrar::class)
        .filter { it.exists }
        .firstNotNullOfOrNull { it.get().serializers[typeName] }
        ?: error("No catalogued serializer for event $typeName")

    @Suppress("UNCHECKED_CAST")
    val typed = serializer as KSerializer<Any>
    return PipelineValue.of(json.decodeFromJsonElement(typed, payload), typed)
}
