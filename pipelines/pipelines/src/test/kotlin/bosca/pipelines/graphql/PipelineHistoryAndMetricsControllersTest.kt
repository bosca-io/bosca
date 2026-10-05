@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.NodeMetrics
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.pipelines.model.PipelineRunStatus
import bosca.serialization.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.ExperimentalUuidApi

/**
 * Field-wiring projections for the read-only GraphQL types in [bosca.pipelines.graphql] that have no
 * service dependencies (they only re-project their source model): the `PipelineHistoryRun` history row,
 * the `PipelineNodeExecution` timeline event, and the `PipelineNodeMetrics` aggregate. Each controller
 * is a pure GraphQL `@Field` adapter, so the tests
 * construct the source model and assert every accessor reads the right property — including the
 * present/absent arms of the nullable fields and both branches of the metrics `failureRate` divide.
 */
class PipelineHistoryAndMetricsControllersTest {

    // === PipelineHistoryRunController ============================================================

    private val historyController = PipelineHistoryRunController()

    private fun historyRow(
        runId: UUID? = null,
        finishedAt: java.time.OffsetDateTime? = null,
        durationMs: Long? = null,
        errorMessage: String? = null,
        outcome: PipelineRunStatus = PipelineRunStatus.OK,
    ): PipelineRunLogWithName = PipelineRunLogWithName(
        id = UUID.random(),
        pipelineId = UUID.random(),
        runId = runId,
        pipelineName = "Greeter",
        eventName = "manual",
        outcome = outcome,
        startedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        finishedAt = finishedAt,
        durationMs = durationMs,
        errorMessage = errorMessage,
    )

    @Test
    fun `history row projects all scalar fields with the nullable fields present`() {
        val runId = UUID.random()
        val finished = java.time.OffsetDateTime.parse("2026-06-18T10:05:00Z")
        val source = historyRow(
            runId = runId,
            finishedAt = finished,
            durationMs = 1234L,
            errorMessage = "boom",
            outcome = PipelineRunStatus.FAILED,
        )

        assertEquals(source.id, historyController.id(source))
        assertEquals(source.pipelineId, historyController.pipelineId(source))
        assertEquals(runId, historyController.runId(source))
        assertEquals("Greeter", historyController.pipelineName(source))
        assertEquals("manual", historyController.eventName(source))
        assertEquals(PipelineRunStatus.FAILED, historyController.outcome(source))
        assertEquals(source.startedAt, historyController.startedAt(source))
        assertEquals(finished, historyController.finishedAt(source))
        assertEquals(1234L, historyController.durationMs(source))
        assertEquals("boom", historyController.errorMessage(source))
    }

    @Test
    fun `history row projects the nullable fields as null when absent`() {
        val source = historyRow(runId = null, finishedAt = null, durationMs = null, errorMessage = null)

        assertNull(historyController.runId(source))
        assertNull(historyController.finishedAt(source))
        assertNull(historyController.durationMs(source))
        assertNull(historyController.errorMessage(source))
        // The non-null scalars still project on the all-null variant.
        assertEquals(PipelineRunStatus.OK, historyController.outcome(source))
        assertEquals(source.startedAt, historyController.startedAt(source))
    }

    // === PipelineNodeExecutionController =========================================================

    private val executionController = PipelineNodeExecutionController()

    private fun executionRecord(
        port: String? = null,
        error: String? = null,
        output: JsonObject? = null,
        status: NodeExecutionStatus = NodeExecutionStatus.OK,
    ): NodeExecutionRecord = NodeExecutionRecord(
        runId = UUID.random(),
        nodeId = "n1",
        status = status,
        startedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:00Z"),
        finishedAt = java.time.OffsetDateTime.parse("2026-06-18T10:00:05Z"),
        durationMs = 5000L,
        port = port,
        error = error,
        output = output,
    )

    @Test
    fun `node execution projects all fields with the nullable fields present`() {
        val output = JsonObject(mapOf("done" to JsonPrimitive(true)))
        val source = executionRecord(
            port = "approved",
            error = "no error really",
            output = output,
            status = NodeExecutionStatus.FAILED,
        )

        assertEquals("n1", executionController.nodeId(source))
        assertEquals(NodeExecutionStatus.FAILED, executionController.status(source))
        assertEquals(source.startedAt, executionController.startedAt(source))
        assertEquals(source.finishedAt, executionController.finishedAt(source))
        assertEquals(5000L, executionController.durationMs(source))
        assertEquals("approved", executionController.port(source))
        assertEquals("no error really", executionController.error(source))
        assertEquals(output, executionController.output(source))
    }

    @Test
    fun `node execution projects the nullable fields as null when absent`() {
        val source = executionRecord(port = null, error = null, output = null)

        assertNull(executionController.port(source))
        assertNull(executionController.error(source))
        assertNull(executionController.output(source))
        // A JsonNull output is a present (non-Kotlin-null) element and projects through verbatim.
        val withJsonNull = executionRecord(output = JsonObject(mapOf("x" to JsonNull)))
        assertEquals(JsonObject(mapOf("x" to JsonNull)), executionController.output(withJsonNull))
    }

    // === PipelineNodeMetricsController ===========================================================

    private val metricsController = PipelineNodeMetricsController()

    private fun metrics(
        executions: Long,
        failures: Long,
        p50: Double = 10.0,
        p95: Double = 99.0,
    ): NodeMetrics = NodeMetrics(
        nodeId = "n1",
        executions = executions,
        failures = failures,
        p50Ms = p50,
        p95Ms = p95,
    )

    @Test
    fun `metrics projects counts and percentiles`() {
        val source = metrics(executions = 8, failures = 2, p50 = 12.5, p95 = 88.0)

        assertEquals("n1", metricsController.nodeId(source))
        assertEquals(8L, metricsController.executions(source))
        assertEquals(2L, metricsController.failures(source))
        assertEquals(12.5, metricsController.p50DurationMs(source))
        assertEquals(88.0, metricsController.p95DurationMs(source))
    }

    @Test
    fun `failureRate divides failures by executions when there are executions`() {
        // executions != 0 -> the divide arm.
        assertEquals(0.25, metricsController.failureRate(metrics(executions = 8, failures = 2)))
        assertEquals(1.0, metricsController.failureRate(metrics(executions = 4, failures = 4)))
    }

    @Test
    fun `failureRate is zero when there are no executions`() {
        // executions == 0L -> the guard arm returns 0.0 (no divide-by-zero).
        assertEquals(0.0, metricsController.failureRate(metrics(executions = 0, failures = 0)))
    }
}
