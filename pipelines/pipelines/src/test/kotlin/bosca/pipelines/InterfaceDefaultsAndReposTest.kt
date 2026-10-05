@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines

import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineSecret
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.PipelineResumeCorrelation
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.NodeResult
import bosca.pipelines.repository.PipelinePermission
import bosca.pipelines.repository.PipelineRecord
import bosca.pipelines.repository.PipelineRunIteration
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineService
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTimeSerializer
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Closes the last small Kover gaps in the pipelines value/contract surface:
 *
 *  - **Interface synthetic default-arg bridges** — calling a `@JvmStatic`-style `foo$default` body by
 *    invoking an interface overload with optional args omitted runs the generated dispatcher even on a
 *    relaxed mock ([PipelineExecutor.execute] `state`, [PipelineRunResultStore.put] `port`,
 *    [PipelineService.save] tail defaults).
 *  - **Real interface default-method bodies** — [PipelineSecretService.resolveForExecution] (both
 *    arms) and [PipelineNodeSerializers.descriptors] need a *real* impl (a mock would intercept and
 *    skip the body), so a tiny anonymous implementation is used.
 *  - **Companion constants** — reading [PipelineRunService]'s and [PipelineSecretService]'s consts
 *    forces their companion `<clinit>`.
 *  - **Value/row data classes** — [PipelineRunIteration], [PipelinePermission], [PipelineResumeCorrelation]
 *    and [PipelineRecord]'s synthetic default-args constructor mask branches are exercised by partial
 *    construction, and their getters are read in assertions.
 */
class InterfaceDefaultsAndReposTest {

    private val serializers = SerializersModule {
        contextual(UUID::class, UUIDSerializer())
        contextual(OffsetDateTime::class, OffsetDateTimeSerializer())
    }
    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = serializers
    }

    private val id = UUID.random()
    private val obj = buildJsonObject { put("k", "v") }

    // ---------------------------------------------------------------------------------------------
    // (1) Interface synthetic default-arg bridges (run even through a relaxed mock)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun executorExecuteDefaultStateArg() = runTest {
        val executor = mockk<PipelineExecutor>(relaxed = true)
        val pipeline = Pipeline(id = id, name = "p", acceptedInputType = "JSON")
        val context = PipelineContext(AuthenticationContext(null, null), json)
        val input = PipelineValue.ofJson(obj)
        // Omitting `state` drives the `execute$default` synthetic bridge (PipelineExecutor.kt:16).
        executor.execute(pipeline, input, context)
        coVerify { executor.execute(pipeline, input, context, null) }
    }

    @Test
    fun runResultStorePutDefaultPortArg() = runTest {
        val store = mockk<PipelineRunResultStore>(relaxed = true)
        // Omitting `port` drives the `put$default` synthetic bridge (PipelineRunResultStore.kt:24).
        store.put(id, "node", obj)
        coVerify { store.put(id, "node", obj, null) }
    }

    @Test
    fun pipelineServiceSaveDefaultTailArgs() = runTest {
        val service = mockk<PipelineService>(relaxed = true)
        val expected = Pipeline(id = id, name = "p", acceptedInputType = "JSON")
        coEvery { service.save(any(), any(), any(), any(), any(), any(), any()) } returns expected
        // Omitting key/api/public/schedule/maxConcurrentRuns/maxRunsPerMinute drives the `save$default`
        // synthetic bridge (PipelineService.kt:21) that fills the tail defaults.
        val saved = service.save(
            id = id,
            name = "p",
            description = "d",
            acceptedInputType = "JSON",
            triggered = false,
            version = 0,
            graph = obj,
        )
        assertEquals(expected, saved)
    }

    // ---------------------------------------------------------------------------------------------
    // (1) Real interface default-method bodies (a mock would skip these — use a real impl)
    // ---------------------------------------------------------------------------------------------

    /** A real [PipelineSecretService] overriding only the abstract members, so the default
     * [PipelineSecretService.resolveForExecution] body (PipelineSecretService.kt:31-33) executes. */
    private class FakeSecretService(private val stored: Map<String, String>) : PipelineSecretService {
        override suspend fun setSecret(name: String, value: String): PipelineSecret =
            PipelineSecret(name = name)

        override suspend fun listSecrets(): List<PipelineSecret> = emptyList()

        override suspend fun deleteSecret(name: String) {}

        override suspend fun resolve(name: String): String? = stored[name]
    }

    @Test
    fun secretServiceResolveForExecutionDryRunArm() = runTest {
        val svc = FakeSecretService(mapOf("api" to "real"))
        // dryRun=true → the SECRET_MASK arm (PipelineSecretService.kt:31/32).
        assertEquals(PipelineSecretService.SECRET_MASK, svc.resolveForExecution("api", dryRun = true))
        // The const itself (companion <clinit>, line 12 region).
        assertEquals("***", PipelineSecretService.SECRET_MASK)
    }

    @Test
    fun secretServiceResolveForExecutionLiveArmAndMissing() = runTest {
        val svc = FakeSecretService(mapOf("api" to "real"))
        // dryRun=false, present → the `resolve(name)` arm (PipelineSecretService.kt:33, left of ?:).
        assertEquals("real", svc.resolveForExecution("api", dryRun = false))
        // dryRun=false, absent → the `error(...)` arm (PipelineSecretService.kt:33, right of ?:).
        val ex = assertFailsWith<IllegalStateException> { svc.resolveForExecution("missing", dryRun = false) }
        assertTrue(ex.message!!.contains("missing"))
    }

    /** A real [PipelineNodeSerializers] overriding only `module`, so the default `descriptors`
     * body (PipelineNodeSerializers.kt:24 — `emptyList()`) executes. */
    private class FakeNodeSerializers(override val module: SerializersModule) : PipelineNodeSerializers

    @Test
    fun nodeSerializersDefaultDescriptorsEmpty() {
        val contrib: PipelineNodeSerializers = FakeNodeSerializers(SerializersModule {})
        // Default body returns emptyList() — covers PipelineNodeSerializers.kt:24.
        assertTrue(contrib.descriptors.isEmpty())
    }

    @Test
    fun nodeResultDefaultPropertiesDistinguishOutputFromSuspend() {
        val value = PipelineValue.ofJson(obj)
        val output: NodeResult = NodeResult.Output(value)
        val suspended: NodeResult = NodeResult.Suspend { }

        assertTrue(output.isOutput)
        assertEquals(value, output.result)
        assertFalse(suspended.isOutput)
        assertNull(suspended.result)
    }

    // ---------------------------------------------------------------------------------------------
    // (2)+(4) value/row data classes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun pipelineRunIterationRoundTripAndDefaults() {
        // Partial construction: leave `results` default to hit the synthetic default-args mask branch.
        val partial = PipelineRunIteration(parentRunId = id, nodeId = "n", total = 3, continueOnError = true)
        // Read the getters (PipelineRunIterationRepository.kt:24/26 + the rest).
        assertEquals(id, partial.parentRunId)
        assertEquals("n", partial.nodeId)
        assertEquals(3, partial.total)
        assertTrue(partial.continueOnError)
        assertEquals(JsonObject(emptyMap()), partial.results)

        val full = partial.copy(results = obj)
        val decoded = json.decodeFromJsonElement(
            PipelineRunIteration.serializer(),
            json.encodeToJsonElement(PipelineRunIteration.serializer(), full),
        )
        assertEquals(full, decoded)
        assertEquals(full.hashCode(), decoded.hashCode())
        assertNotEquals(full, partial)
        assertTrue(full.toString().isNotEmpty())
    }

    @Test
    fun pipelinePermissionRoundTripAndEntityId() {
        val perm = PipelinePermission(pipelineId = id, groupId = UUID.random(), action = PermissionAction.EXECUTE)
        // entityId getter delegates to pipelineId — covers PipelinePermissionRepository.kt:31.
        assertEquals(perm.pipelineId, perm.entityId)
        assertEquals(PermissionAction.EXECUTE, perm.action)
        val decoded = json.decodeFromJsonElement(
            PipelinePermission.serializer(),
            json.encodeToJsonElement(PipelinePermission.serializer(), perm),
        )
        assertEquals(perm, decoded)
        assertEquals(perm.entityId, decoded.entityId)
        assertNotEquals(perm, perm.copy(action = PermissionAction.VIEW))
        assertTrue(perm.toString().isNotEmpty())
    }

    @Test
    fun resumeCorrelationRoundTrip() {
        val corr = PipelineResumeCorrelation(runId = id, nodeId = "n")
        assertEquals(id, corr.runId)
        assertEquals("n", corr.nodeId)
        val decoded = json.decodeFromJsonElement(
            PipelineResumeCorrelation.serializer(),
            json.encodeToJsonElement(PipelineResumeCorrelation.serializer(), corr),
        )
        assertEquals(corr, decoded)
        assertEquals(corr.hashCode(), decoded.hashCode())
        assertNotEquals(corr, corr.copy(nodeId = "m"))
        assertTrue(corr.toString().isNotEmpty())
    }

    @Test
    fun pipelineRecordPartialConstruction() {
        // Partial construction: only the two required fields with no default + graph; everything else
        // defaulted, exercising the synthetic default-args constructor mask branch (PipelineRecord.kt:18).
        val record = PipelineRecord(name = "p", acceptedInputType = "JSON", graph = obj)
        assertEquals(UUID.NIL, record.id)
        assertEquals("", record.description)
        assertEquals(false, record.triggered)
        assertEquals("", record.key)
        assertEquals(false, record.api)
        assertEquals(false, record.public)
        assertNull(record.schedule)
        assertNull(record.maxConcurrentRuns)
        assertNull(record.maxRunsPerMinute)
        assertEquals(0L, record.version)
        assertNull(record.gitRepositoryId)
        assertNull(record.gitPath)
        assertNull(record.lastSyncError)
        assertNull(record.deletedAt)
        assertEquals(obj, record.graph)
    }
}
