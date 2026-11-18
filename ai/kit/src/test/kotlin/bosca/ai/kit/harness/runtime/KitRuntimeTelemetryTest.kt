package bosca.ai.kit.harness.runtime

import bosca.ai.kit.harness.*
import bosca.counter.Counter
import bosca.serialization.*
import bosca.sharedqueue.jobs.DelayException
import bosca.telemetry.Tracing
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.slf4j.Logger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class KitRuntimeTelemetryTest {
    private val events = mutableListOf<String>()
    private val values = mutableMapOf<String, Long>()
    private val logger = mockk<Logger>(relaxed = true)
    private val counter = mockk<Counter>()
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer()); contextual(OffsetDateTimeSerializer())
    } }
    private lateinit var telemetry: KitRuntimeTelemetry

    @BeforeTest fun setup() {
        every { logger.info("{}", any<Any>()) } answers { events += secondArg<String>() }
        coEvery { counter.increment(any(), any()) } answers { values.merge(firstArg(), secondArg(), Long::plus) ?: 0L }
        telemetry = KitRuntimeTelemetry(counter, json, logger)
    }

    @Test fun `events correlate identities and revisions while excluding bodies credentials and metric identities`(): Unit = runBlocking {
        val secret = "PRIVATE-CONTENT-MUST-NOT-APPEAR"
        val config = KitHarnessConfiguration("fixture", "http://$secret", "http://$secret", scopes = listOf("content:view"),
            image = "image@sha256:" + "a".repeat(64), imageId = "sha256:" + "b".repeat(64), versions = RuntimeVersions("test", "test", "test", "test"))
        val resolution = KitHarnessResolution(UUID.random(), "policy-revision", "SYSTEM", config, KitRuntimeAuthority(listOf("content:view"), emptyList()))
        val context = KitContextManifest("context-revision", "instruction-revision", null, null,
            listOf(KitContextSource("memory", "memory-id", "7", "source-revision", true, ref = secret, path = secret, metadataId = secret)),
            omissions = listOf(secret), budgetCharacters = 4096, usedCharacters = 1)
        val session = KitRuntimeSession(UUID.random(), UUID.random(), JsonPrimitive(secret), JsonPrimitive(secret),
            resolvedSettings = json.encodeToJsonElement(KitHarnessResolution.serializer(), resolution),
            contextManifest = json.encodeToJsonElement(KitContextManifest.serializer(), context),
            secrets = JsonPrimitive(secret), failure = secret, idleAt = OffsetDateTime.now(), generation = 3, epoch = 9, dispatchId = UUID.random())
        val turn = KitRuntimeTurn(UUID.random(), session.sessionId, message = JsonPrimitive(secret), result = JsonPrimitive(secret))
        val approval = KitRuntimeApproval(UUID.random(), session.sessionId, turn.id, secret, JsonPrimitive(secret), secret, OffsetDateTime.now())
        Tracing.withTrace("trace-identity") {
            telemetry.record(KitRuntimeOperation.TURN, "success", session, turn, approval, durationMillis = 45, bytes = 99)
        }
        val event = Json.parseToJsonElement(events.single()).jsonObject
        assertEquals(session.sessionId.toString(), event["sessionId"]?.jsonPrimitive?.content)
        assertEquals(session.principalId.toString(), event["principalId"]?.jsonPrimitive?.content)
        assertEquals(turn.id.toString(), event["turnId"]?.jsonPrimitive?.content)
        assertEquals(approval.id.toString(), event["approvalId"]?.jsonPrimitive?.content)
        assertEquals("trace-identity", event["traceId"]?.jsonPrimitive?.content)
        assertEquals("policy-revision", event["policyVersion"]?.jsonPrimitive?.content)
        assertEquals("context-revision", event["contextRevision"]?.jsonPrimitive?.content)
        assertEquals("7", event.getValue("sources").jsonArray.single().jsonObject["version"]?.jsonPrimitive?.content)
        assertFalse(events.joinToString().contains(secret))
        assertEquals(5, values.size)
        assertTrue(values.keys.all { it.matches(Regex("kit\\.runtime\\.turn\\.success\\.(count|milliseconds|milliseconds_samples|bytes|bytes_samples)\\.[0-9]+")) })
        assertFalse(values.keys.any { it.contains(session.sessionId.toString()) })
        assertFailsWith<IllegalArgumentException> { telemetry.record(KitRuntimeOperation.TURN, secret) }
    }

    @Test fun `counter failure cannot change success failure or cancellation semantics`(): Unit = runBlocking {
        coEvery { counter.increment(any(), any()) } throws IllegalStateException("private counter details")
        assertEquals(7, telemetry.observe(KitRuntimeOperation.RESTORE) { 7 })
        val failure = IllegalStateException("private prompt details")
        assertSame(failure, assertFailsWith<IllegalStateException> { telemetry.observe(KitRuntimeOperation.RESTORE) { throw failure } })
        val pending = DelayException(1.seconds)
        assertSame(pending, assertFailsWith<DelayException> { telemetry.observe(KitRuntimeOperation.RESTORE) { throw pending } })
        val cancelled = CancellationException("private cancellation details")
        assertSame(cancelled, assertFailsWith<CancellationException> { telemetry.observe(KitRuntimeOperation.RESTORE) { throw cancelled } })
        assertEquals(listOf("success", "failure", "pending", "cancelled"), events.map { Json.parseToJsonElement(it).jsonObject.getValue("outcome").jsonPrimitive.content })
        assertFalse(events.joinToString().contains("private"))
        coVerify(exactly = 3) { counter.increment(any(), any()) }
    }

    @Test fun `stalled counter is bounded while parent cancellation propagates`(): Unit = runBlocking {
        coEvery { counter.increment(any(), any()) } coAnswers { awaitCancellation() }
        assertEquals(7, withTimeout(3000) { telemetry.observe(KitRuntimeOperation.RESTORE) { 7 } })
        val recorded = CompletableDeferred<Unit>()
        coEvery { counter.increment(any(), any()) } coAnswers { recorded.complete(Unit); awaitCancellation() }
        val job = launch { telemetry.record(KitRuntimeOperation.RESTORE, "success") }
        recorded.await(); job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun `sample totals remain additive across independent runner snapshots`(): Unit = runBlocking {
        telemetry.sample(3, 1); telemetry.sample(5, 1)
        assertEquals(2L, values.filterKeys { it.startsWith("kit.runtime.snapshot.samples.") }.values.sum())
        assertEquals(8L, values.filterKeys { it.startsWith("kit.runtime.snapshot.active.") }.values.sum())
        assertEquals(2L, values.filterKeys { it.startsWith("kit.runtime.snapshot.failed.") }.values.sum())
    }
}
