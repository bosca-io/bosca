@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.trigger

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.configuration.PipelinesJobQueueNames
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.queue.annotations.IJobDefinition
import bosca.queue.annotations.JobDefinition
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.AbstractJobExecutor
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.modules.SerializersModule
import kotlin.uuid.ExperimentalUuidApi

/**
 * The generic backing work for any node that suspends via the default [bosca.pipelines.node.PipelineNode.doSuspend]:
 * re-run node [nodeId] of run [runId]'s `execute()` — durably, inside a
 * child of the run job — and stage its output for the resume. The node's captured inbound values ride
 * in [inputs] (encoded to JSON at suspend), so the work runs without re-evaluating upstream nodes.
 *
 * This is what lets a node opt into durability with `willSuspend = true` alone: it never authors its
 * own job. Because the work runs in a job, the job system's retry/timeout apply, and a thrown
 * `execute()` becomes a job failure that the run resume routes to the node's `error` port (or fails
 * the run if unwired) — exactly the contract a purpose-built backing job (e.g. a HubSpot write) gives.
 */
@Serializable
data class ExecuteNodeInJob(
    @Contextual val runId: UUID,
    val nodeId: String,
    val inputs: Map<String, JsonElement>,
) : IJobDefinition

@JobDefinition(
    definition = ExecuteNodeInJob::class,
    // The queue arg is the JobQueue PROVIDER name (the pipelines queue the run job and its children run on).
    queue = PipelinesJobQueueNames.jobQueue,
    name = "pipeline-execute-node",
)
class ExecuteNodeInJobExecutor(
    private val runService: PipelineRunService,
    private val pipelineService: PipelineService,
    private val resultStore: PipelineRunResultStore,
    private val securityService: SecurityService,
    private val config: PipelinesRuntimeConfiguration
) : AbstractJobExecutor<ExecuteNodeInJob>(ExecuteNodeInJob.serializer()) {

    @Volatile
    private var cachedJson: Json? = null

    @OptIn(InternalDI::class)
    private suspend fun graphJson(): Json {
        cachedJson?.let { return it }
        val global = provide<Json>()
        val nodeModules = ProviderRegistry.findAll(PipelineNodeSerializers::class)
            .filter { it.exists }
            .map { it.get().module }
        val combined = SerializersModule {
            include(global.serializersModule)
            nodeModules.forEach { include(it) }
        }
        return Json(global) {
            serializersModule = combined
            allowStructuredMapKeys = true
        }.also { cachedJson = it }
    }

    override suspend fun execute() {
        val jobDef = getJobDefinition()
        val run = runService.get(jobDef.runId)
            ?: error("Run ${jobDef.runId} not found for suspended node ${jobDef.nodeId}")
        val node = pipelineService.decodeGraph(run.graphSnapshot).nodes.firstOrNull { it.id == jobDef.nodeId }
            ?: error("Node ${jobDef.nodeId} not found in run ${jobDef.runId}")
        val inputs = NodeInputs(jobDef.inputs.mapValues { PipelineValue.ofJson(it.value) })

        // A non-durable context (runId = null) makes the node's own run() route straight to execute():
        // the work runs HERE, in this durable child job, and never re-suspends. The backing work runs
        // under the run's originating principal (an on-demand run's caller, captured on the run row) so
        // the caller's security context traverses the whole run, falling back to the service account for
        // a triggered/scheduled run — matching how the run's drive and resumes are authenticated.
        val context = PipelineContext(
            run.principalId?.let { securityService.impersonate(it) }
                ?: securityService.impersonate(config.serviceAccount),
            graphJson(),
            inputCreated = run.createdAt,
        )
        val value = (node.run(context, inputs) as? NodeResult.Output)?.value
        // Stage the output (JSON null when the node has none) on its emitted port, exactly where the
        // run's resume reads it (PipelineRunServiceImpl.resume → resultStore.get). A thrown execute()
        // never reaches here: the job fails and the resume routes the failure to the error port.
        resultStore.put(jobDef.runId, jobDef.nodeId, value?.encode(graphJson()) ?: JsonNull, port = value?.port)
    }
}
