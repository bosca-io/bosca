@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.service

import bosca.pipelines.repository.PipelineRunIteration
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exercises the two run-engine value types that carry optional constructor params —
 * [PipelineRunIteration] (the durable ForEach aggregation row) and [PipelineRunNodeResult] (a
 * suspended node's staged output). For each: round-trip a fully-populated instance through the
 * serializer (covers every "field present" write arm plus the all-fields-equal `equals` path),
 * construct via the synthetic `<init>$default` constructor while OMITTING each optional param
 * individually (covers each `<init>$default` mask bit and the "field absent → default" decode arm),
 * exercise the `this === other` / wrong-type `equals` arms, and confirm a single-field-mutated copy
 * per constructor field is non-equal (covers each per-field `equals` arm).
 */
class RunServiceValueTypesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule { contextual(UUID::class, UUIDSerializer()) }
    }

    private val id1 = UUID.random()
    private val obj = buildJsonObject { put("0", "a") }
    private val obj2 = buildJsonObject { put("0", "b") }

    @Test
    fun pipelineRunIteration() {
        val full = PipelineRunIteration(
            parentRunId = id1,
            nodeId = "n",
            total = 3,
            continueOnError = true,
            results = obj,
            maxConcurrency = 2,
            items = JsonArray(listOf(obj)),
        )
        // Round-trip preserves equality + hashCode.
        val decoded = json.decodeFromJsonElement(
            PipelineRunIteration.serializer(),
            json.encodeToJsonElement(PipelineRunIteration.serializer(), full),
        )
        assertEquals(full, decoded)
        assertEquals(full.hashCode(), decoded.hashCode())
        // String round-trip too: the streaming string decoder takes the generated serializer's
        // SEQUENTIAL-decode arm, complementing the tree decoder's non-sequential loop above.
        assertEquals(full, json.decodeFromString(
            PipelineRunIteration.serializer(), json.encodeToString(PipelineRunIteration.serializer(), full)))
        // `this === other` true arm and the wrong-type (`other !is`) false arm.
        assertEquals(full, full)
        assertFalse(full.equals(Any()))
        assertTrue(full.toString().isNotEmpty())

        // Synthetic `<init>$default`: omit the only optional param (`results`) so its default applies.
        val defaulted = PipelineRunIteration(parentRunId = id1, nodeId = "n", total = 3, continueOnError = true)
        assertEquals(JsonObject(emptyMap()), defaulted.results)
        assertEquals(0, defaulted.maxConcurrency)
        assertEquals(null, defaulted.items)
        val omittedItems = PipelineRunIteration(
            parentRunId = id1,
            nodeId = "n",
            total = 3,
            continueOnError = true,
            results = obj,
            maxConcurrency = 2,
        )
        val omittedConcurrency = PipelineRunIteration(
            parentRunId = id1,
            nodeId = "n",
            total = 3,
            continueOnError = true,
            results = obj,
            items = JsonArray(listOf(obj)),
        )
        assertEquals(null, omittedItems.items)
        assertEquals(0, omittedConcurrency.maxConcurrency)
        // Round-trip the defaulted form so the "field absent → default" decode arm runs.
        // @ColumnName is a DB-layer mapping; the kotlinx serializer uses the Kotlin property names.
        val minimal = json.decodeFromJsonElement(
            PipelineRunIteration.serializer(),
            json.parseToJsonElement("""{"parentRunId":"$id1","nodeId":"n","total":3,"continueOnError":true}"""),
        )
        assertEquals(JsonObject(emptyMap()), minimal.results)
        minimal.hashCode()

        // Each single-field-mutated copy is non-equal (per-field `equals` arms).
        assertNotEquals(full, full.copy(parentRunId = UUID.random()))
        assertNotEquals(full, full.copy(nodeId = "m"))
        assertNotEquals(full, full.copy(total = 4))
        assertNotEquals(full, full.copy(continueOnError = false))
        assertNotEquals(full, full.copy(results = obj2))
        assertNotEquals(full, full.copy(maxConcurrency = 1))
        assertNotEquals(full, full.copy(items = JsonArray(emptyList())))

        // Generated deserializer's throwMissingFieldException arm: omit the required `continueOnError`.
        assertFailsWith<SerializationException> {
            json.decodeFromJsonElement(
                PipelineRunIteration.serializer(),
                json.parseToJsonElement("""{"parentRunId":"$id1","nodeId":"n","total":3}"""),
            )
        }
    }

    @Test
    fun pipelineRunNodeResult() {
        val full = PipelineRunNodeResult(value = obj, port = "out")
        val decoded = json.decodeFromJsonElement(
            PipelineRunNodeResult.serializer(),
            json.encodeToJsonElement(PipelineRunNodeResult.serializer(), full),
        )
        assertEquals(full, decoded)
        assertEquals(full.hashCode(), decoded.hashCode())
        // String round-trip too: the streaming string decoder takes the generated serializer's
        // SEQUENTIAL-decode arm, complementing the tree decoder's non-sequential loop above.
        assertEquals(full, json.decodeFromString(
            PipelineRunNodeResult.serializer(), json.encodeToString(PipelineRunNodeResult.serializer(), full)))
        assertEquals(full, full)
        assertFalse(full.equals(Any()))
        assertTrue(full.toString().isNotEmpty())

        // Synthetic `<init>$default`: omit the only optional param (`port`) so its `null` default applies.
        val defaulted = PipelineRunNodeResult(value = obj)
        assertEquals(null, defaulted.port)
        // Decode a minimal payload (port absent) to run the "field absent → default" decode arm; its
        // null port covers the null arm of the generated hashCode.
        val minimal = json.decodeFromJsonElement(
            PipelineRunNodeResult.serializer(),
            json.parseToJsonElement("""{"value":{"0":"a"}}"""),
        )
        assertEquals(null, minimal.port)
        minimal.hashCode()

        assertNotEquals(full, full.copy(value = obj2))
        assertNotEquals(full, full.copy(port = "error"))

        // Generated deserializer's throwMissingFieldException arm: omit the required `value`. Use both
        // the tree decoder and the streaming string decoder so whichever routes through the generated
        // synthetic constructor's missing-field check is exercised.
        assertFailsWith<SerializationException> {
            json.decodeFromJsonElement(
                PipelineRunNodeResult.serializer(),
                json.parseToJsonElement("""{"port":"out"}"""),
            )
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString(PipelineRunNodeResult.serializer(), """{"port":"out"}""")
        }
    }
}
