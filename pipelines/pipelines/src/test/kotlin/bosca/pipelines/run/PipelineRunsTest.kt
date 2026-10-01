@file:OptIn(ExperimentalUuidApi::class, Internal::class, InternalDI::class)

package bosca.pipelines.run

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.events.catalog.EventCatalogRegistrar
import bosca.events.catalog.EventDescriptor
import bosca.pipelines.model.Pipeline
import bosca.pipelines.node.InputNode
import bosca.serialization.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exhaustive branch coverage of [resolvePipelineRunInput]:
 *  - JSON-typed pipelines (with/without an input node, with/without a schema, conforming vs violating
 *    payload), and
 *  - event-typed pipelines (explicit eventType vs the pipeline's accepted type as the fallback,
 *    catalogued vs missing serializer, decodable vs undecodable payload, and registrar fan-out).
 */
class PipelineRunsTest {

    @Serializable
    private data class SampleEvent(val id: String, val count: Int = 0)

    private val json = Json { ignoreUnknownKeys = true }

    /** A JSON-input pipeline whose Input node optionally carries a [schema]. */
    private fun jsonPipeline(schema: JsonElement? = null, withInputNode: Boolean = true): Pipeline {
        val nodes = if (withInputNode) {
            listOf(InputNode(id = "in", acceptedType = InputNode.JSON_TYPE, schema = schema))
        } else {
            emptyList()
        }
        return Pipeline(
            id = UUID.random(),
            name = "json-pipeline",
            acceptedInputType = InputNode.JSON_TYPE,
            nodes = nodes,
        )
    }

    /** An event-typed pipeline whose accepted type is [acceptedType]. */
    private fun eventPipeline(acceptedType: String): Pipeline = Pipeline(
        id = UUID.random(),
        name = "event-pipeline",
        acceptedInputType = acceptedType,
        nodes = listOf(InputNode(id = "in", acceptedType = acceptedType)),
    )

    /** Register an [EventCatalogRegistrar] exposing exactly [serializers]. */
    private fun catalog(serializers: Map<String, KSerializer<*>>) {
        provides<EventCatalogRegistrar> {
            object : EventCatalogRegistrar {
                override val events: List<EventDescriptor> = emptyList()
                override val serializers: Map<String, KSerializer<*>> = serializers
            }
        }
    }

    private val objectSchema: JsonElement = Json.parseToJsonElement(
        """
        {
          "type": "object",
          "required": ["email"],
          "properties": {
            "email": {"type": "string"},
            "count": {"type": "integer"}
          }
        }
        """,
    )

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
    }

    // --- JSON-typed branch ------------------------------------------------------------------------

    @Test
    fun `json pipeline with no input node passes the payload straight through`() = runTest {
        // inputNode?.schema?.let — inputNode is null (receiver-null safe-call arm).
        val payload = buildJsonObject { put("anything", JsonPrimitive("ok")) }
        val pipeline = jsonPipeline(withInputNode = false)

        val result = resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)

        assertEquals(payload, result.value, "ofJson should carry the payload verbatim")
        assertEquals(null, result.typeName, "plain JSON has no typed origin")
    }

    @Test
    fun `json pipeline with an input node but no schema passes through`() = runTest {
        // inputNode non-null, schema null (schema?.let arm where schema is null).
        val payload = buildJsonObject { put("x", JsonPrimitive(1)) }
        val pipeline = jsonPipeline(schema = null, withInputNode = true)

        val result = resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)

        assertEquals(payload, result.value)
    }

    @Test
    fun `json pipeline with a schema accepts a conforming payload`() = runTest {
        // schema present, validate returns empty -> check passes.
        val payload = buildJsonObject {
            put("email", JsonPrimitive("ada@x.io"))
            put("count", JsonPrimitive(3))
        }
        val pipeline = jsonPipeline(schema = objectSchema)

        val result = resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)

        assertEquals(payload, result.value)
    }

    @Test
    fun `json pipeline with a schema rejects a violating payload`() = runTest {
        // schema present, validate returns violations -> check(false) throws with a message.
        val payload = buildJsonObject { put("count", JsonPrimitive("not-an-int")) }
        val pipeline = jsonPipeline(schema = objectSchema)

        val ex = assertFailsWith<IllegalStateException> {
            resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)
        }
        assertTrue(
            "does not match the pipeline's input schema" in (ex.message ?: ""),
            "expected schema-violation message, got: ${ex.message}",
        )
        // Both violations (missing required 'email' + wrong type for 'count') are joined in.
        assertTrue("email" in (ex.message ?: ""), "message should mention missing email: ${ex.message}")
    }

    @Test
    fun `json pipeline rejects a top-level type mismatch against the schema`() = runTest {
        // A single violation joined into the message (joinToString one element).
        val payload = buildJsonArray { add(JsonPrimitive("not-an-object")) }
        val pipeline = jsonPipeline(schema = objectSchema)

        val ex = assertFailsWith<IllegalStateException> {
            resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)
        }
        assertTrue("expected object" in (ex.message ?: ""), "got: ${ex.message}")
    }

    @Test
    fun `json pipeline ignores the eventType argument entirely`() = runTest {
        // eventType is supplied but the JSON branch never consults it.
        val payload = buildJsonObject { put("k", JsonPrimitive("v")) }
        val pipeline = jsonPipeline(withInputNode = false)

        val result = resolvePipelineRunInput(pipeline, eventType = "ignored.event", payload = payload, json = json)

        assertEquals(payload, result.value)
    }

    // --- Event-typed branch -----------------------------------------------------------------------

    @Test
    fun `event pipeline decodes via the catalogued serializer for the accepted type`() = runTest {
        // typeName = eventType ?: acceptedInputType, eventType == null -> fallback (elvis right arm).
        catalog(mapOf("sample.event" to SampleEvent.serializer()))
        val pipeline = eventPipeline("sample.event")
        val payload = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("e1", 7))

        val result = resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)

        assertEquals(SampleEvent("e1", 7), result.value)
        assertEquals(SampleEvent.serializer().descriptor.serialName, result.typeName)
    }

    @Test
    fun `event pipeline prefers an explicit eventType over the accepted type`() = runTest {
        // eventType non-null -> elvis left arm; serializer resolved for the explicit name.
        catalog(mapOf("explicit.event" to SampleEvent.serializer()))
        val pipeline = eventPipeline("accepted.but.unused")
        val payload = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("e2"))

        val result = resolvePipelineRunInput(pipeline, eventType = "explicit.event", payload = payload, json = json)

        assertEquals(SampleEvent("e2"), result.value)
    }

    @Test
    fun `event pipeline decodes a minimal payload using serializer defaults`() = runTest {
        // count omitted -> default-value deserializer arm exercised.
        catalog(mapOf("sample.event" to SampleEvent.serializer()))
        val pipeline = eventPipeline("sample.event")
        val payload = buildJsonObject { put("id", JsonPrimitive("only-id")) }

        val result = resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)

        assertEquals(SampleEvent("only-id", 0), result.value)
    }

    @Test
    fun `event pipeline errors when no registrar is present`() = runTest {
        // findAll returns empty -> firstNotNullOfOrNull == null -> error(...).
        val pipeline = eventPipeline("uncatalogued.event")
        val payload = buildJsonObject { put("id", JsonPrimitive("x")) }

        val ex = assertFailsWith<IllegalStateException> {
            resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)
        }
        assertEquals("No catalogued serializer for event uncatalogued.event", ex.message)
    }

    @Test
    fun `event pipeline errors when the registrar lacks the requested type`() = runTest {
        // A registrar exists (it.exists true, it.get() called) but serializers[typeName] is null.
        catalog(mapOf("other.event" to SampleEvent.serializer()))
        val pipeline = eventPipeline("missing.event")
        val payload = buildJsonObject { put("id", JsonPrimitive("x")) }

        val ex = assertFailsWith<IllegalStateException> {
            resolvePipelineRunInput(pipeline, eventType = "missing.event", payload = payload, json = json)
        }
        assertEquals("No catalogued serializer for event missing.event", ex.message)
    }

    @Test
    fun `event pipeline finds the serializer across multiple registrars`() = runTest {
        // firstNotNullOfOrNull skips the empty registrar and resolves from the second (loop > 1).
        catalog(mapOf("other.event" to SampleEvent.serializer()))
        catalog(mapOf("sample.event" to SampleEvent.serializer()))
        val pipeline = eventPipeline("sample.event")
        val payload = json.encodeToJsonElement(SampleEvent.serializer(), SampleEvent("multi"))

        val result = resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)

        assertEquals(SampleEvent("multi"), result.value)
    }

    @Test
    fun `event pipeline surfaces an undecodable payload from the serializer`() = runTest {
        // decodeFromJsonElement throws (required field missing) and propagates uncaught.
        catalog(mapOf("sample.event" to SampleEvent.serializer()))
        val pipeline = eventPipeline("sample.event")
        val payload = buildJsonObject { put("count", JsonPrimitive(1)) } // 'id' required, missing

        assertFailsWith<Exception> {
            resolvePipelineRunInput(pipeline, eventType = null, payload = payload, json = json)
        }
    }

    @Test
    fun `event pipeline rejects a non-object payload for an object serializer`() = runTest {
        catalog(mapOf("sample.event" to SampleEvent.serializer()))
        val pipeline = eventPipeline("sample.event")

        assertFailsWith<Exception> {
            resolvePipelineRunInput(pipeline, eventType = null, payload = JsonNull, json = json)
        }
    }
}
