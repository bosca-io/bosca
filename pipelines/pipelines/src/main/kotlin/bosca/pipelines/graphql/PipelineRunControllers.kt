@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.graphql

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.NodeMetrics
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineRunAwait
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.service.PipelineRunService
import bosca.serialization.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi

/** Field wiring for the `PipelineRunStep` GraphQL type — one step-oriented progress row. */
@TypeController(type = "PipelineRunStep")
class PipelineRunStepController : GraphQLController<bosca.pipelines.model.RunStep> {
    @Field fun nodeId(source: bosca.pipelines.model.RunStep): String = source.nodeId
    @Field fun title(source: bosca.pipelines.model.RunStep): String = source.title
    @Field fun kind(source: bosca.pipelines.model.RunStep) = source.kind
    @Field fun status(source: bosca.pipelines.model.RunStep) = source.status
    @Field fun depth(source: bosca.pipelines.model.RunStep): Int = source.depth
    @Field fun item(source: bosca.pipelines.model.RunStep): String? = source.item
    @Field fun runId(source: bosca.pipelines.model.RunStep): UUID? = source.runId
    @Field fun type(source: bosca.pipelines.model.RunStep): String? = source.type
    @Field fun channelType(source: bosca.pipelines.model.RunStep): String? = source.channelType
}

/** A parked node resolved from the run's graph snapshot (the `PipelineAwaitingNode` GraphQL type). */
data class AwaitingNodeInfo(val nodeId: String, val type: String, val name: String, val runId: UUID? = null)

/** Field wiring for the `PipelineAwaitingNode` GraphQL type. */
@TypeController(type = "PipelineAwaitingNode")
class PipelineAwaitingNodeController : GraphQLController<AwaitingNodeInfo> {
    @Field fun nodeId(source: AwaitingNodeInfo): String = source.nodeId
    @Field fun type(source: AwaitingNodeInfo): String = source.type
    @Field fun name(source: AwaitingNodeInfo): String = source.name
    @Field fun runId(source: AwaitingNodeInfo): UUID? = source.runId
}

/** Field wiring for the `PipelineHistoryRun` GraphQL type (run-history rows); source is [PipelineRunLogWithName]. */
@TypeController(type = "PipelineHistoryRun")
class PipelineHistoryRunController : GraphQLController<PipelineRunLogWithName> {

    @Field
    fun id(source: PipelineRunLogWithName): UUID = source.id

    @Field
    fun pipelineId(source: PipelineRunLogWithName): UUID = source.pipelineId

    @Field
    fun runId(source: PipelineRunLogWithName): UUID? = source.runId

    @Field
    fun pipelineName(source: PipelineRunLogWithName): String = source.pipelineName

    @Field
    fun eventName(source: PipelineRunLogWithName): String = source.eventName

    @Field
    fun outcome(source: PipelineRunLogWithName): PipelineRunStatus = source.outcome

    @Field
    fun startedAt(source: PipelineRunLogWithName): bosca.serialization.OffsetDateTime = source.startedAt

    @Field
    fun finishedAt(source: PipelineRunLogWithName): bosca.serialization.OffsetDateTime? = source.finishedAt

    @Field
    fun durationMs(source: PipelineRunLogWithName): Long? = source.durationMs

    @Field
    fun errorMessage(source: PipelineRunLogWithName): String? = source.errorMessage
}

/**
 * Field wiring for the `PipelineRunState` GraphQL type — the live, durable run state; source is the
 * [PipelineRun] model (distinct from [PipelineRunLogWithName], the append-only history).
 */
@TypeController(type = "PipelineRunState")
class PipelineRunStateController(
    private val json: Json,
    private val runService: PipelineRunService,
) : GraphQLController<PipelineRun> {

    /** The per-node execution timeline for this run, in execution order. */
    @Field
    suspend fun nodes(source: PipelineRun): List<NodeExecutionRecord> =
        runService.nodeTimeline(source.id)

    /** The step-oriented progress view — Status milestones + human waits, per fan-out item. */
    @Field
    suspend fun steps(source: PipelineRun): List<bosca.pipelines.model.RunStep> =
        runService.steps(source.id)

    /** The child runs a For Each / Run Pipeline node spawned under this run, in item order. */
    @Field
    suspend fun childRuns(source: PipelineRun, nodeId: String): List<PipelineRun> =
        runService.listChildren(source.id, nodeId)

    @Field
    fun itemIndex(source: PipelineRun): Int? = source.itemIndex

    @Field
    fun id(source: PipelineRun): UUID = source.id

    @Field
    fun pipelineId(source: PipelineRun): UUID = source.pipelineId

    @Field
    fun status(source: PipelineRun): PipelineRunStatus = source.status

    @Field
    fun eventName(source: PipelineRun): String = source.eventName

    @Field
    fun awaitingNodeIds(source: PipelineRun): List<String> = awaits(source).map { it.nodeId }

    /** The nodes the run is ACTUALLY parked on — drilled through fan-outs into child runs, labelled
     *  per item, so UIs can say "waiting on Wait for build (item 2)" and tell a HUMAN wait
     *  (gate.approval / waitForInput) from external work honestly. */
    @Field
    suspend fun awaitingNodes(source: PipelineRun): List<AwaitingNodeInfo> =
        runService.awaitingNodes(source.id).map { AwaitingNodeInfo(it.nodeId, it.type, it.name, it.runId) }

    @Field
    fun completedNodeIds(source: PipelineRun): List<String> =
        (source.nodeOutputs as? JsonObject)?.let { it.keys.toList() } ?: emptyList()

    @Field
    fun error(source: PipelineRun): String? = source.error

    @Field
    fun createdAt(source: PipelineRun): bosca.serialization.OffsetDateTime = source.createdAt

    @Field
    fun modifiedAt(source: PipelineRun): bosca.serialization.OffsetDateTime = source.modifiedAt

    private fun awaits(source: PipelineRun): List<PipelineRunAwait> =
        (source.awaiting as? JsonArray)
            ?.let { json.decodeFromJsonElement(ListSerializer(PipelineRunAwait.serializer()), it) }
            ?: emptyList()
}

/** Field wiring for the `PipelineNodeExecution` GraphQL type — one node-timeline event; source is [NodeExecutionRecord]. */
@TypeController(type = "PipelineNodeExecution")
class PipelineNodeExecutionController : GraphQLController<NodeExecutionRecord> {

    @Field
    fun nodeId(source: NodeExecutionRecord): String = source.nodeId

    @Field
    fun status(source: NodeExecutionRecord): NodeExecutionStatus = source.status

    @Field
    fun startedAt(source: NodeExecutionRecord): bosca.serialization.OffsetDateTime = source.startedAt

    @Field
    fun finishedAt(source: NodeExecutionRecord): bosca.serialization.OffsetDateTime = source.finishedAt

    @Field
    fun durationMs(source: NodeExecutionRecord): Long = source.durationMs

    @Field
    fun port(source: NodeExecutionRecord): String? = source.port

    @Field
    fun error(source: NodeExecutionRecord): String? = source.error

    @Field
    fun output(source: NodeExecutionRecord): JsonElement? = source.output
}

/** Field wiring for the `PipelineNodeMetrics` GraphQL type — per-node aggregate stats; source is [NodeMetrics]. */
@TypeController(type = "PipelineNodeMetrics")
class PipelineNodeMetricsController : GraphQLController<NodeMetrics> {

    @Field
    fun nodeId(source: NodeMetrics): String = source.nodeId

    @Field
    fun executions(source: NodeMetrics): Long = source.executions

    @Field
    fun failures(source: NodeMetrics): Long = source.failures

    /** Fraction of completions that failed, 0..1. */
    @Field
    fun failureRate(source: NodeMetrics): Double =
        if (source.executions == 0L) 0.0 else source.failures.toDouble() / source.executions

    @Field
    fun p50DurationMs(source: NodeMetrics): Double = source.p50Ms

    @Field
    fun p95DurationMs(source: NodeMetrics): Double = source.p95Ms
}

/**
 * Field wiring for the `PipelineRunUpdate` GraphQL type — a live run / per-node status change streamed
 * over the `pipelineRun` subscription; source is [PipelineRunUpdate].
 */
@TypeController(type = "PipelineRunUpdate")
class PipelineRunUpdateController : GraphQLController<PipelineRunUpdate> {

    @Field
    fun runId(source: PipelineRunUpdate): UUID = source.runId

    @Field
    fun runStatus(source: PipelineRunUpdate): PipelineRunStatus? = source.runStatus

    @Field
    fun nodeId(source: PipelineRunUpdate): String? = source.nodeId

    @Field
    fun nodeStatus(source: PipelineRunUpdate): NodeExecutionStatus? = source.nodeStatus

    @Field
    fun port(source: PipelineRunUpdate): String? = source.port

    @Field
    fun error(source: PipelineRunUpdate): String? = source.error

    @Field
    fun at(source: PipelineRunUpdate): bosca.serialization.OffsetDateTime = source.at
}
