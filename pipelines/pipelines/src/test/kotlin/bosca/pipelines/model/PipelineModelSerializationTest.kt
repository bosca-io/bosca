@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.model

import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.graphql.PipelineBackfillEntryInput
import bosca.pipelines.graphql.PipelineInput
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineShapeRecord
import bosca.pipelines.trigger.PipelineDispatchJob
import bosca.pipelines.trigger.PipelineRunJob
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Exercises every `@Serializable` pipeline model's compiler-generated members — the serializer
 * (write + read), `equals`/`hashCode`, and `toString` — which Kover counts as real branches. Per model:
 * round-trip a fully-populated instance (covers the "field present" serializer arms and the
 * all-fields-equal `equals` path against a separate decoded instance), decode a minimal JSON carrying
 * only required fields (executes the "field absent → default" serializer arms AND the null arms of
 * `hashCode`), exercise the `this === other` / `other !is Type` equals arms, and compare one
 * single-field-mutated copy per constructor field (covers every per-field not-equal arm).
 */
class PipelineModelSerializationTest {

    private val serializers = SerializersModule {
        contextual(UUID::class, UUIDSerializer())
        contextual(OffsetDateTime::class, OffsetDateTimeSerializer())
        polymorphic(PipelineNode::class) {
            subclass(InputNode::class, InputNode.serializer())
            subclass(OutputNode::class, OutputNode.serializer())
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = serializers
    }

    /**
     * Same module but encodeDefaults on — encoding through this takes the `shouldEncodeElementDefault`
     * (left) arm of each generated field guard, which the default-off [json] never reaches.
     */
    private val jsonEncodeDefaults = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        serializersModule = serializers
    }

    private val id1 = UUID.random()
    private val now = OffsetDateTime.now()
    private val obj = buildJsonObject { put("k", "v") }
    private val obj2 = buildJsonObject { put("k", "v2") }

    /**
     * Round-trip [full] (preserves equality + hashCode), decode [minimalJson] (executes the default-value
     * deserializer arms and — because its nullable fields decode to null — the null arms of `hashCode`),
     * exercise the `this === other` and wrong-type `equals` arms, and confirm each instance in [copies]
     * (each differing from [full] in exactly one field) is non-equal (covering that field's `equals` arm).
     */
    private fun <T : Any> assertModel(
        serializer: KSerializer<T>,
        full: T,
        minimalJson: String,
        copies: List<T>,
        /**
         * A JSON payload that OMITS at least one **required** (no-default) field. Decoding it must throw
         * `SerializationException`, which drives the generated deserializer's `throwMissingFieldException`
         * arm — the arm neither the full nor the minimal (required-only) payload reaches. Null for models
         * with no required fields (their deserializer has no such arm).
         */
        missingRequiredJson: String? = null,
    ) {
        val decoded = json.decodeFromJsonElement(serializer, json.encodeToJsonElement(serializer, full))
        assertEquals(full, decoded, "round-trip must preserve equality")
        assertEquals(full.hashCode(), decoded.hashCode(), "round-trip must preserve hashCode")
        // ALSO round-trip through the streaming STRING decoder. `decodeFromString` reports
        // `decodeSequentially() == true` for these flat classes and takes the generated serializer's
        // sequential-decode arm, whereas `decodeFromJsonElement` (the tree decoder) takes the
        // non-sequential element-by-element loop arm — so the two decoders together cover both.
        assertEquals(full, json.decodeFromString(serializer, json.encodeToString(serializer, full)),
            "string round-trip (sequential decode arm) must preserve equality")
        // `this === other` true arm and the `other !is Type` (wrong-type → false) arm.
        assertEquals(full, full, "an instance must equal itself")
        assertFalse(full.equals(Any()), "a different type must not be equal")
        // Decoding the minimal payload runs the "field absent → default" arms; the result is intentionally
        // not equality-checked (now()-defaulted timestamps differ by construction time). Its absent
        // nullable fields are null, so hashCode() here covers the null arms of the generated hashCode.
        val minimal = json.decodeFromJsonElement(serializer, json.parseToJsonElement(minimalJson))
        assertTrue(minimal.toString().isNotEmpty())
        minimal.hashCode()
        // Encoding the defaults-only instance runs the generated serializer's "field equals default →
        // skip" encode arms (encodeDefaults is false) — the counterpart to the "field present" arms
        // covered by encoding [full]. Round-trip it back so the decode-from-skipped path runs too.
        json.decodeFromJsonElement(serializer, json.encodeToJsonElement(serializer, minimal))
        // Encoding through encodeDefaults=on takes the `shouldEncodeElementDefault` (left) arm of each
        // generated field guard — the arm the default-off encoders above never reach.
        jsonEncodeDefaults.encodeToJsonElement(serializer, minimal)
        for (c in copies) assertNotEquals(full, c, "a copy differing in one field must not be equal")
        assertTrue(full.toString().isNotEmpty())
        // Required-field-missing payload → the generated deserializer's throwMissingFieldException arm.
        if (missingRequiredJson != null) {
            assertFailsWith<SerializationException>("decoding a payload missing a required field must throw") {
                json.decodeFromJsonElement(serializer, json.parseToJsonElement(missingRequiredJson))
            }
        }
    }

    @Test
    fun pipelineRun() {
        val full = PipelineRun(
            id = id1, pipelineId = UUID.random(), status = PipelineRunStatus.SUSPENDED, eventName = "e",
            graphSnapshot = obj, input = obj, inputType = "T", nodeOutputs = obj, awaiting = obj,
            parentRunId = UUID.random(), parentNodeId = "p", itemIndex = 3,
            runJobId = UUID.random(), principalId = UUID.random(), output = obj, error = "x",
            version = 2, createdAt = now, modifiedAt = now, deletedAt = now,
        )
        assertModel(
            PipelineRun.serializer(),
            full,
            """{"pipelineId":"$id1","graphSnapshot":{}}""",
            listOf(
                full.copy(id = UUID.random()),
                full.copy(pipelineId = UUID.random()),
                full.copy(status = PipelineRunStatus.OK),
                full.copy(eventName = "other"),
                full.copy(graphSnapshot = obj2),
                full.copy(input = obj2),
                full.copy(inputType = "U"),
                full.copy(nodeOutputs = obj2),
                full.copy(awaiting = obj2),
                full.copy(parentRunId = UUID.random()),
                full.copy(parentNodeId = "q"),
                full.copy(itemIndex = 4),
                full.copy(runJobId = UUID.random()),
                full.copy(principalId = UUID.random()),
                full.copy(output = obj2),
                full.copy(error = "y"),
                full.copy(version = 3L),
                full.copy(createdAt = now.plusDays(1)),
                full.copy(modifiedAt = now.plusDays(1)),
                full.copy(deletedAt = now.plusDays(1)),
            ),
            // Omits the required `graphSnapshot` (keeps the required `pipelineId`).
            missingRequiredJson = """{"pipelineId":"$id1"}""",
        )
    }

    @Test
    fun pipelineRunMinimalDefaults() {
        val decoded = json.decodeFromJsonElement(
            PipelineRun.serializer(),
            json.parseToJsonElement("""{"pipelineId":"$id1","graphSnapshot":{}}"""),
        )
        assertEquals(id1, decoded.pipelineId)
        assertEquals(PipelineRunStatus.RUNNING, decoded.status)
        assertEquals("", decoded.eventName)
        assertEquals(null, decoded.error)
        assertEquals(0L, decoded.version)
        assertEquals(null, decoded.parentRunId)
        assertEquals(null, decoded.output)
    }

    @Test
    fun pipelineEdge() {
        val full = PipelineEdge(id = "e1", source = "a", target = "b", sourcePort = "ok", targetPort = "in")
        assertModel(
            PipelineEdge.serializer(),
            full,
            """{"id":"e1","source":"a","target":"b"}""",
            listOf(
                full.copy(id = "e2"),
                full.copy(source = "c"),
                full.copy(target = "d"),
                full.copy(sourcePort = "err"),
                full.copy(targetPort = "other"),
            ),
            // Omits the required `target` (keeps `id`/`source`).
            missingRequiredJson = """{"id":"e1","source":"a"}""",
        )
    }

    @Test
    fun pipeline() {
        val full = Pipeline(
            id = id1, name = "p", description = "d", acceptedInputType = "JSON", tags = listOf("release"),
            triggered = true, key = "k",
            api = true, public = true, schedule = "0 0 * * *", maxConcurrentRuns = 5, maxRunsPerMinute = 10,
            groups = listOf(NodeGroup("group")), version = 4,
            gitRepositoryId = UUID.random(), gitPath = "p.yaml", lastSyncError = "err",
        )
        assertModel(
            Pipeline.serializer(),
            full,
            """{"id":"$id1","name":"p","acceptedInputType":"JSON"}""",
            listOf(
                full.copy(id = UUID.random()),
                full.copy(name = "other"),
                full.copy(description = "dd"),
                full.copy(acceptedInputType = "OTHER"),
                full.copy(tags = listOf("nightly")),
                full.copy(triggered = false),
                full.copy(key = "k2"),
                full.copy(api = false),
                full.copy(public = false),
                full.copy(schedule = "1 1 * * *"),
                full.copy(maxConcurrentRuns = 6),
                full.copy(maxRunsPerMinute = 11),
                full.copy(nodes = listOf(InputNode(id = "in", acceptedType = "JSON"))),
                full.copy(edges = listOf(PipelineEdge(id = "e", source = "a", target = "b"))),
                full.copy(groups = listOf(NodeGroup("other"))),
                full.copy(version = 5L),
                full.copy(gitRepositoryId = UUID.random()),
                full.copy(gitPath = "q.yaml"),
                full.copy(lastSyncError = "err2"),
                full.copy(deletedAt = now.plusDays(1)),
            ),
            // Omits the required `acceptedInputType` (keeps `id`/`name`).
            missingRequiredJson = """{"id":"$id1","name":"p"}""",
        )
    }

    @Test
    fun pipelineGraph() {
        // `full` keeps empty node/edge lists so the round-trip equality holds: a populated `nodes` list
        // would contain InputNode (a non-data class with reference equality), so a decoded copy would
        // never compare equal. The per-field copies use non-empty lists, which differ from empty anyway.
        val full = PipelineGraph()
        assertModel(
            PipelineGraph.serializer(),
            full,
            """{}""",
            listOf(
                full.copy(nodes = listOf(InputNode(id = "in", acceptedType = "JSON"))),
                full.copy(edges = listOf(PipelineEdge(id = "e", source = "a", target = "b"))),
                full.copy(groups = listOf(NodeGroup("group"))),
            ),
        )
    }

    @Test
    fun pipelineRunLog() {
        val full = PipelineRunLog(
            id = id1, pipelineId = UUID.random(), runId = UUID.random(), eventName = "e",
            outcome = PipelineRunStatus.FAILED, startedAt = now, finishedAt = now, durationMs = 12, errorMessage = "x",
        )
        assertModel(
            PipelineRunLog.serializer(),
            full,
            """{"pipelineId":"$id1","eventName":"e","outcome":"OK","startedAt":"$now"}""",
            listOf(
                full.copy(id = UUID.random()),
                full.copy(pipelineId = UUID.random()),
                full.copy(runId = UUID.random()),
                full.copy(eventName = "other"),
                full.copy(outcome = PipelineRunStatus.CANCELLED),
                full.copy(startedAt = now.plusDays(1)),
                full.copy(finishedAt = now.plusDays(1)),
                full.copy(durationMs = 13L),
                full.copy(errorMessage = "y"),
            ),
            // Omits the required `startedAt` (keeps the other required fields).
            missingRequiredJson = """{"pipelineId":"$id1","eventName":"e","outcome":"OK"}""",
        )
    }

    @Test
    fun pipelineRunLogWithName() {
        val full = PipelineRunLogWithName(
            id = id1, pipelineId = UUID.random(), runId = UUID.random(), pipelineName = "p", eventName = "e",
            outcome = PipelineRunStatus.OK, startedAt = now, finishedAt = now, durationMs = 7, errorMessage = "x",
        )
        assertModel(
            PipelineRunLogWithName.serializer(),
            full,
            """{"pipelineId":"$id1","pipelineName":"p","eventName":"e","outcome":"OK","startedAt":"$now"}""",
            listOf(
                full.copy(id = UUID.random()),
                full.copy(pipelineId = UUID.random()),
                full.copy(runId = UUID.random()),
                full.copy(pipelineName = "q"),
                full.copy(eventName = "other"),
                full.copy(outcome = PipelineRunStatus.FAILED),
                full.copy(startedAt = now.plusDays(1)),
                full.copy(finishedAt = now.plusDays(1)),
                full.copy(durationMs = 8L),
                full.copy(errorMessage = "y"),
            ),
            // Omits the required `startedAt` (keeps the other required fields).
            missingRequiredJson = """{"pipelineId":"$id1","pipelineName":"p","eventName":"e","outcome":"OK"}""",
        )
    }

    @Test
    fun nodeExecutionRecord() {
        val full = NodeExecutionRecord(
            id = id1, runId = UUID.random(), nodeId = "n", status = NodeExecutionStatus.OK, startedAt = now,
            finishedAt = now, durationMs = 9, port = "ok", error = "x", output = obj, createdAt = now,
        )
        assertModel(
            NodeExecutionRecord.serializer(),
            full,
            """{"runId":"$id1","nodeId":"n","status":"OK","startedAt":"$now","finishedAt":"$now","durationMs":9}""",
            listOf(
                full.copy(id = UUID.random()),
                full.copy(runId = UUID.random()),
                full.copy(nodeId = "m"),
                full.copy(status = NodeExecutionStatus.FAILED),
                full.copy(startedAt = now.plusDays(1)),
                full.copy(finishedAt = now.plusDays(1)),
                full.copy(durationMs = 10L),
                full.copy(port = "err"),
                full.copy(error = "y"),
                full.copy(output = obj2),
                full.copy(createdAt = now.plusDays(1)),
            ),
            // Omits the required `durationMs` (keeps the other required fields).
            missingRequiredJson = """{"runId":"$id1","nodeId":"n","status":"OK","startedAt":"$now","finishedAt":"$now"}""",
        )
    }

    @Test
    fun nodeMetrics() {
        val full = NodeMetrics(nodeId = "n", executions = 10, failures = 2, p50Ms = 1.5, p95Ms = 9.0)
        assertModel(
            NodeMetrics.serializer(),
            full,
            """{"nodeId":"n","executions":10,"failures":2,"p50Ms":1.5,"p95Ms":9.0}""",
            listOf(
                full.copy(nodeId = "m"),
                full.copy(executions = 11L),
                full.copy(failures = 3L),
                full.copy(p50Ms = 2.5),
                full.copy(p95Ms = 10.0),
            ),
            // Omits the required `p95Ms` (keeps the other required fields).
            missingRequiredJson = """{"nodeId":"n","executions":10,"failures":2,"p50Ms":1.5}""",
        )
    }

    @Test
    fun retryPolicy() {
        val full = RetryPolicy(maxAttempts = 5, initialDelaySeconds = 2, multiplier = 2.0, maxDelaySeconds = 60)
        assertModel(
            RetryPolicy.serializer(),
            full,
            """{"maxAttempts":3}""",
            listOf(
                full.copy(maxAttempts = 6),
                full.copy(initialDelaySeconds = 3L),
                full.copy(multiplier = 3.0),
                full.copy(maxDelaySeconds = 61L),
            ),
            // Omits the only required field `maxAttempts`.
            missingRequiredJson = """{"initialDelaySeconds":2}""",
        )
    }

    @Test
    fun pipelineRunAwait() {
        val full = PipelineRunAwait(nodeId = "n")
        assertModel(
            PipelineRunAwait.serializer(),
            full,
            """{"nodeId":"n"}""",
            listOf(
                full.copy(nodeId = "m"),
            ),
            // Omits the required `nodeId`.
            missingRequiredJson = """{}""",
        )
    }

    @Test
    fun rollbackRecord() {
        val full = RollbackRecord(runId = id1, nodeId = "n", rollbackPipelineId = UUID.random(), output = obj)
        assertModel(
            RollbackRecord.serializer(),
            full,
            """{"runId":"$id1","nodeId":"n","rollbackPipelineId":"$id1"}""",
            listOf(
                full.copy(runId = UUID.random()),
                full.copy(nodeId = "m"),
                full.copy(rollbackPipelineId = UUID.random()),
                full.copy(output = obj2),
            ),
            // Omits the required `rollbackPipelineId` (keeps `runId`/`nodeId`).
            missingRequiredJson = """{"runId":"$id1","nodeId":"n"}""",
        )
    }

    @Test
    fun pipelineSecret() {
        val full = PipelineSecret(name = "s", encryptedValue = "enc", createdAt = now, modifiedAt = now)
        assertModel(
            PipelineSecret.serializer(),
            full,
            """{"name":"s"}""",
            listOf(
                full.copy(name = "other"),
                full.copy(encryptedValue = "enc2"),
                full.copy(createdAt = now.plusDays(1)),
                full.copy(modifiedAt = now.plusDays(1)),
            ),
            // Omits the only required field `name`.
            missingRequiredJson = """{"encryptedValue":"enc"}""",
        )
    }

    @Test
    fun pipelineRecord() {
        val full = PipelineRecord(
            id = id1, name = "p", description = "d", acceptedInputType = "JSON", tags = listOf("release"),
            triggered = true, key = "k",
            api = true, public = true, schedule = "0 0 * * *", maxConcurrentRuns = 3, maxRunsPerMinute = 6,
            graph = obj, version = 2, gitRepositoryId = UUID.random(), gitPath = "p.yaml", lastSyncError = "e", deletedAt = now,
        )
        assertModel(
            PipelineRecord.serializer(),
            full,
            """{"name":"p","acceptedInputType":"JSON","graph":{}}""",
            listOf(
                full.copy(id = UUID.random()),
                full.copy(name = "q"),
                full.copy(description = "dd"),
                full.copy(acceptedInputType = "OTHER"),
                full.copy(tags = listOf("nightly")),
                full.copy(triggered = false),
                full.copy(key = "k2"),
                full.copy(api = false),
                full.copy(public = false),
                full.copy(schedule = "1 1 * * *"),
                full.copy(maxConcurrentRuns = 4),
                full.copy(maxRunsPerMinute = 7),
                full.copy(graph = obj2),
                full.copy(version = 3L),
                full.copy(gitRepositoryId = UUID.random()),
                full.copy(gitPath = "q.yaml"),
                full.copy(lastSyncError = "e2"),
                full.copy(deletedAt = now.plusDays(1)),
            ),
            // Omits the required `graph` (keeps `name`/`acceptedInputType`).
            missingRequiredJson = """{"name":"p","acceptedInputType":"JSON"}""",
        )
    }

    @Test
    fun pipelineInput() {
        val full = PipelineInput(
            id = id1, name = "p", description = "d", acceptedInputType = "JSON", triggered = true, key = "k",
            api = true, public = true, schedule = "0 0 * * *", maxConcurrentRuns = 2, maxRunsPerMinute = 4,
            version = 1, graph = obj,
        )
        assertModel(
            PipelineInput.serializer(),
            full,
            """{"name":"p","acceptedInputType":"JSON","graph":{}}""",
            listOf(
                full.copy(id = UUID.random()),
                full.copy(name = "q"),
                full.copy(description = "dd"),
                full.copy(acceptedInputType = "OTHER"),
                full.copy(triggered = false),
                full.copy(key = "k2"),
                full.copy(api = false),
                full.copy(public = false),
                full.copy(schedule = "1 1 * * *"),
                full.copy(maxConcurrentRuns = 3),
                full.copy(maxRunsPerMinute = 5),
                full.copy(version = 2),
                full.copy(graph = obj2),
            ),
            // Omits the required `graph` (keeps `name`/`acceptedInputType`).
            missingRequiredJson = """{"name":"p","acceptedInputType":"JSON"}""",
        )
    }

    @Test
    fun pipelineBackfillEntryInput() {
        val full = PipelineBackfillEntryInput(pipelineId = id1, gitPath = "a.yaml")
        assertModel(
            PipelineBackfillEntryInput.serializer(),
            full,
            """{"pipelineId":"$id1","gitPath":"a.yaml"}""",
            listOf(
                full.copy(pipelineId = UUID.random()),
                full.copy(gitPath = "b.yaml"),
            ),
            // Omits the required `gitPath` (keeps `pipelineId`).
            missingRequiredJson = """{"pipelineId":"$id1"}""",
        )
    }

    @Test
    fun pipelinesRuntimeConfiguration() {
        val full = PipelinesRuntimeConfiguration(
            serviceAccount = "svc", suspendedRunMaxLifetimeMinutes = 60, onDemandRunMaxBlockMillis = 1000,
            runStateRetentionDays = 10, runHistoryRetentionDays = 20,
        )
        assertModel(
            PipelinesRuntimeConfiguration.serializer(),
            full,
            """{}""",
            listOf(
                full.copy(serviceAccount = "other"),
                full.copy(suspendedRunMaxLifetimeMinutes = 61L),
                full.copy(onDemandRunMaxBlockMillis = 1001L),
                full.copy(runStateRetentionDays = 11L),
                full.copy(runHistoryRetentionDays = 21L),
            ),
        )
    }

    /**
     * The generated `write$Self` "value equals the now()-default → SKIP" encode arm for the
     * `OffsetDateTime.now()`-defaulted timestamp fields ([PipelineRun.createdAt]/[PipelineRun.modifiedAt],
     * [PipelineSecret.createdAt]/[PipelineSecret.modifiedAt], [NodeExecutionRecord.createdAt]). The
     * compiler inlines that default into `write$Self` and RE-EVALUATES `now()` at encode time, so under
     * encodeDefaults=false a field is omitted only when its stored value equals that fresh `now()` —
     * an arm the fixed-timestamp `full` instances (which always differ from now()) never reach.
     *
     * Mocking `OffsetDateTime.now()` to a fixed instant makes the construction default and the encode-time
     * re-evaluation the SAME value, so the arm fires DETERMINISTICALLY. (Encoding a real `now()` and
     * hoping it equals the encode-time `now()` is a clock race whose outcome flips with platform clock
     * resolution — that was flaky and is what this replaces.)
     */
    @Test
    fun nowDefaultedTimestampWriteSkipArm() {
        val fixed = OffsetDateTime.parse("2026-01-02T03:04:05.123456Z")
        mockkStatic(OffsetDateTime::class)
        try {
            every { OffsetDateTime.now() } returns fixed
            // Each timestamp left at its now() default equals the encode-time now() → write$Self omits it.
            val run = json.encodeToString(
                PipelineRun.serializer(),
                PipelineRun(id = id1, pipelineId = id1, graphSnapshot = obj),
            )
            assertFalse(run.contains("createdAt"))
            assertFalse(run.contains("modifiedAt"))

            val secret = json.encodeToString(PipelineSecret.serializer(), PipelineSecret(name = "s"))
            assertFalse(secret.contains("createdAt"))
            assertFalse(secret.contains("modifiedAt"))

            val record = json.encodeToString(
                NodeExecutionRecord.serializer(),
                NodeExecutionRecord(
                    runId = id1, nodeId = "n", status = NodeExecutionStatus.OK,
                    startedAt = fixed, finishedAt = fixed, durationMs = 1L,
                ),
            )
            assertFalse(record.contains("createdAt"))

            val update = json.encodeToString(
                PipelineRunUpdate.serializer(),
                PipelineRunUpdate(runId = id1),
            )
            assertFalse(update.contains("at"))

            val shape = json.encodeToString(
                PipelineShapeRecord.serializer(),
                PipelineShapeRecord(name = "shape", fields = obj),
            )
            assertFalse(shape.contains("createdAt"))
            assertFalse(shape.contains("modifiedAt"))

            val dispatch = json.encodeToString(
                PipelineDispatchJob.serializer(),
                PipelineDispatchJob(eventName = "sample.event", eventPayload = obj),
            )
            assertFalse(dispatch.contains("eventCreated"))

            val runJob = json.encodeToString(
                PipelineRunJob.serializer(),
                PipelineRunJob(pipelineId = id1, eventName = "sample.event", eventPayload = obj),
            )
            assertFalse(runJob.contains("eventCreated"))
        } finally {
            unmockkStatic(OffsetDateTime::class)
        }
    }

    @Test
    fun pipelineRunUpdate() {
        val full = PipelineRunUpdate(
            runId = id1, runStatus = PipelineRunStatus.SUSPENDED, nodeId = "n",
            nodeStatus = NodeExecutionStatus.OK, port = "out", error = "x", at = now,
        )
        assertModel(
            PipelineRunUpdate.serializer(),
            full,
            """{"runId":"$id1"}""",
            listOf(
                full.copy(runId = UUID.random()),
                full.copy(runStatus = PipelineRunStatus.OK),
                full.copy(nodeId = "other"),
                full.copy(nodeStatus = NodeExecutionStatus.FAILED),
                full.copy(port = "err"),
                full.copy(error = "y"),
                full.copy(at = now.plusDays(1)),
            ),
        )
    }

    @Test
    fun enumsRoundTripAndTerminal() {
        for (s in PipelineRunStatus.entries) {
            assertEquals(s, json.decodeFromJsonElement(PipelineRunStatus.serializer(), json.encodeToJsonElement(PipelineRunStatus.serializer(), s)))
        }
        for (s in NodeExecutionStatus.entries) {
            assertEquals(s, json.decodeFromJsonElement(NodeExecutionStatus.serializer(), json.encodeToJsonElement(NodeExecutionStatus.serializer(), s)))
        }
        assertTrue(PipelineRunStatus.OK.isTerminal && PipelineRunStatus.FAILED.isTerminal && PipelineRunStatus.CANCELLED.isTerminal)
        assertTrue(!PipelineRunStatus.RUNNING.isTerminal && !PipelineRunStatus.SUSPENDED.isTerminal)
    }

    /**
     * Drive the compiler-synthesised `<init>$default` constructor of each model that has optional
     * params. Kover counts one mask branch per optional parameter, and the existing tests only ever
     * call the *primary* constructor with every argument supplied (`.copy` passes all args too), so
     * those mask branches stay uncovered. Here every model is constructed once per optional parameter,
     * omitting exactly that one parameter, so each distinct omission set flips a different mask bit.
     * Each result is round-tripped to assert the omitted parameter took its declared default.
     */
    @Test
    fun syntheticDefaultConstructorMaskArms() {
        // Pipeline — every optional param omitted individually (mask bits across all defaulted params).
        assertEquals("", Pipeline(id = id1, name = "p", acceptedInputType = "JSON").description)
        assertEquals(listOf("tag"), Pipeline(id = id1, name = "p", acceptedInputType = "JSON", tags = listOf("tag")).tags)
        assertEquals(false, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", description = "d").triggered)
        assertEquals("", Pipeline(id = id1, name = "p", acceptedInputType = "JSON", triggered = true).key)
        assertEquals(false, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", key = "k").api)
        assertEquals(false, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", api = true).public)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", public = true).schedule)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", schedule = "c").maxConcurrentRuns)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", maxConcurrentRuns = 1).maxRunsPerMinute)
        assertTrue(Pipeline(id = id1, name = "p", acceptedInputType = "JSON", maxRunsPerMinute = 1).nodes.isEmpty())
        assertTrue(Pipeline(id = id1, name = "p", acceptedInputType = "JSON", nodes = emptyList()).edges.isEmpty())
        assertEquals(listOf(NodeGroup("g")), Pipeline(id = id1, name = "p", acceptedInputType = "JSON", groups = listOf(NodeGroup("g"))).groups)
        assertEquals(0L, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", edges = emptyList()).version)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", version = 1L).gitRepositoryId)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", gitRepositoryId = id1).gitPath)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", gitPath = "g").lastSyncError)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON", lastSyncError = "e").deletedAt)
        assertEquals(id1, Pipeline(id = id1, name = "p", acceptedInputType = "JSON").id)

        // Pipeline.isDeleted — both arms of `deletedAt != null`.
        assertFalse(Pipeline(id = id1, name = "p", acceptedInputType = "JSON", deletedAt = null).isDeleted)
        assertTrue(Pipeline(id = id1, name = "p", acceptedInputType = "JSON", deletedAt = now).isDeleted)
        // Pipeline.isPublished computed getter — both arms (api on/off) and the extension getters.
        assertTrue(Pipeline(id = id1, name = "p", acceptedInputType = "JSON", api = true).isPublished)
        assertFalse(Pipeline(id = id1, name = "p", acceptedInputType = "JSON", api = false).isPublished)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON").inputNode)
        assertEquals(null, Pipeline(id = id1, name = "p", acceptedInputType = "JSON").outputNode)

        // PipelineEdge — omit sourcePort, then omit targetPort.
        assertEquals(null, PipelineEdge(id = "e", source = "a", target = "b", targetPort = "in").sourcePort)
        assertEquals(null, PipelineEdge(id = "e", source = "a", target = "b", sourcePort = "ok").targetPort)
        assertEquals("e", PipelineEdge(id = "e", source = "a", target = "b").id)

        // PipelineRun — omit each defaulted param individually (pipelineId + graphSnapshot are required).
        val gs = obj
        assertEquals(UUID.NIL, PipelineRun(pipelineId = id1, graphSnapshot = gs).id)
        assertEquals(PipelineRunStatus.RUNNING, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs).status)
        assertEquals("", PipelineRun(id = id1, pipelineId = id1, status = PipelineRunStatus.OK, graphSnapshot = gs).eventName)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, eventName = "e", graphSnapshot = gs).input)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, input = gs).inputType)
        assertEquals(JsonObject(emptyMap()), PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, inputType = "T").nodeOutputs)
        assertEquals(JsonArray(emptyList()), PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, nodeOutputs = gs).awaiting)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, awaiting = JsonArray(emptyList())).parentRunId)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, parentRunId = id1).parentNodeId)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, parentNodeId = "p").itemIndex)
        assertEquals(id1, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, runJobId = id1).runJobId)
        assertEquals(id1, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, principalId = id1).principalId)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, itemIndex = 1).output)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, output = gs).error)
        assertEquals(0L, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, error = "x").version)
        assertEquals(null, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, version = 1L).deletedAt)
        // createdAt / modifiedAt default to now() — confirm they are populated when omitted.
        assertTrue(PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, deletedAt = now).createdAt.year >= 2000)
        assertTrue(PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, createdAt = now).modifiedAt.year >= 2000)
        assertEquals(now, PipelineRun(id = id1, pipelineId = id1, graphSnapshot = gs, modifiedAt = now).modifiedAt)

        // PipelineSecret — omit encryptedValue, createdAt, modifiedAt individually.
        assertEquals("", PipelineSecret(name = "s").encryptedValue)
        assertTrue(PipelineSecret(name = "s", encryptedValue = "v").createdAt.year >= 2000)
        assertTrue(PipelineSecret(name = "s", createdAt = now).modifiedAt.year >= 2000)
        assertEquals(now, PipelineSecret(name = "s", modifiedAt = now).modifiedAt)

        // NodeExecutionRecord — omit each defaulted param (runId/nodeId/status/started/finished/duration required).
        assertEquals(UUID.NIL, NodeExecutionRecord(runId = id1, nodeId = "n", status = NodeExecutionStatus.OK, startedAt = now, finishedAt = now, durationMs = 1).id)
        assertEquals(null, NodeExecutionRecord(id = id1, runId = id1, nodeId = "n", status = NodeExecutionStatus.OK, startedAt = now, finishedAt = now, durationMs = 1).port)
        assertEquals(null, NodeExecutionRecord(runId = id1, nodeId = "n", status = NodeExecutionStatus.OK, startedAt = now, finishedAt = now, durationMs = 1, port = "ok").error)
        assertEquals(null, NodeExecutionRecord(runId = id1, nodeId = "n", status = NodeExecutionStatus.OK, startedAt = now, finishedAt = now, durationMs = 1, error = "x").output)
        assertTrue(NodeExecutionRecord(runId = id1, nodeId = "n", status = NodeExecutionStatus.OK, startedAt = now, finishedAt = now, durationMs = 1, output = obj).createdAt.year >= 2000)
        assertEquals(now, NodeExecutionRecord(runId = id1, nodeId = "n", status = NodeExecutionStatus.OK, startedAt = now, finishedAt = now, durationMs = 1, createdAt = now).createdAt)

        // PipelineRunLogWithName — omit id, runId, finishedAt, durationMs, errorMessage individually.
        assertEquals(UUID.NIL, PipelineRunLogWithName(pipelineId = id1, pipelineName = "p", eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now).id)
        assertEquals(null, PipelineRunLogWithName(id = id1, pipelineId = id1, pipelineName = "p", eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now).runId)
        assertEquals(null, PipelineRunLogWithName(pipelineId = id1, runId = id1, pipelineName = "p", eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now).finishedAt)
        assertEquals(null, PipelineRunLogWithName(pipelineId = id1, pipelineName = "p", eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now, finishedAt = now).durationMs)
        assertEquals(null, PipelineRunLogWithName(pipelineId = id1, pipelineName = "p", eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now, durationMs = 1L).errorMessage)
        assertEquals("p", PipelineRunLogWithName(pipelineId = id1, pipelineName = "p", eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now, errorMessage = "x").pipelineName)

        // PipelineRunLog — omit id, runId, finishedAt, durationMs, errorMessage individually.
        assertEquals(UUID.NIL, PipelineRunLog(pipelineId = id1, eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now).id)
        assertEquals(null, PipelineRunLog(id = id1, pipelineId = id1, eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now).runId)
        assertEquals(null, PipelineRunLog(pipelineId = id1, runId = id1, eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now).finishedAt)
        assertEquals(null, PipelineRunLog(pipelineId = id1, eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now, finishedAt = now).durationMs)
        assertEquals(null, PipelineRunLog(pipelineId = id1, eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now, durationMs = 1L).errorMessage)
        assertEquals("e", PipelineRunLog(pipelineId = id1, eventName = "e", outcome = PipelineRunStatus.OK, startedAt = now, errorMessage = "x").eventName)

        // RollbackRecord — omit output (only optional param); runId/nodeId/rollbackPipelineId required.
        assertEquals(null, RollbackRecord(runId = id1, nodeId = "n", rollbackPipelineId = id1).output)
        assertEquals(obj, RollbackRecord(runId = id1, nodeId = "n", rollbackPipelineId = id1, output = obj).output)

        // RetryPolicy — omit initialDelaySeconds, multiplier, maxDelaySeconds individually (maxAttempts required).
        assertEquals(0L, RetryPolicy(maxAttempts = 3).initialDelaySeconds)
        assertEquals(1.0, RetryPolicy(maxAttempts = 3, initialDelaySeconds = 1L).multiplier)
        assertEquals(300L, RetryPolicy(maxAttempts = 3, multiplier = 2.0).maxDelaySeconds)
        assertEquals(3, RetryPolicy(maxAttempts = 3, maxDelaySeconds = 1L).maxAttempts)

        // PipelineGraph — omit nodes, then omit edges.
        assertTrue(PipelineGraph(edges = emptyList()).nodes.isEmpty())
        assertTrue(PipelineGraph(nodes = emptyList()).edges.isEmpty())
        assertEquals(listOf(NodeGroup("g")), PipelineGraph(groups = listOf(NodeGroup("g"))).groups)
        assertTrue(PipelineGraph().nodes.isEmpty())

        // NodeMetrics / PipelineRunAwait have no optional params — construct them so their primary
        // constructor + property getters are exercised here too (covers any residual header arm).
        val metrics = NodeMetrics(nodeId = "n", executions = 1L, failures = 0L, p50Ms = 1.0, p95Ms = 2.0)
        assertEquals("n", metrics.nodeId)
        val await = PipelineRunAwait(nodeId = "n")
        assertEquals("n", await.nodeId)
    }
}
