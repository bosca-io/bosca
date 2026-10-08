@file:OptIn(ExperimentalUuidApi::class)

package bosca.pipelines.service

import bosca.db.afterCommit
import bosca.db.connectionOrNull
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provide
import bosca.events.catalog.EventCatalogRegistrar
import bosca.pipelines.PipelineContext
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.RollbackRecord
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineRun
import bosca.pipelines.model.PipelineAwaitingApproval
import bosca.pipelines.model.PipelineRunAwait
import bosca.pipelines.model.PipelineRunUpdate
import bosca.pipelines.model.NodeExecutionRecord
import bosca.pipelines.model.RunStep
import bosca.pipelines.model.RunStepKind
import bosca.pipelines.model.RunStepStatus
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.NodeMetrics
import bosca.pipelines.node.RollbackSink
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.NodeExecutionEvent
import bosca.pipelines.node.NodeExecutionSink
import bosca.pipelines.model.PipelineRunLog
import bosca.pipelines.model.PipelineRunLogWithName
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.model.inputNode
import bosca.pipelines.node.JsonSchemaValidator
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.repository.RollbackRepository
import bosca.pipelines.repository.NodeExecutionRepository
import bosca.pipelines.repository.PipelineRunIterationRepository
import bosca.pipelines.repository.PipelineRunLogRepository
import bosca.pipelines.repository.PipelineRunRepository
import bosca.pubsub.PubSubService
import bosca.security.service.AuthenticationContext
import bosca.security.service.SecurityService
import bosca.security.service.impersonate
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.sharedqueue.jobs.jobOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.modules.SerializersModule
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi

/**
 * Owns pipeline-run state and history. [start]/[complete]/[get] manage the durable
 * `pipelines.pipeline_run` row; [recordLog] appends to `pipelines.pipeline_run_log`. [execute] and
 * [resume] drive the [PipelineExecutor] for durable runs and persist the outcome — completion
 * (terminal status + history) or suspension (checkpoint + await, then schedule the backing work).
 *
 * The graph snapshot is taken/decoded through [PipelineService] so the polymorphic node graph rides
 * the same aggregated node `SerializersModule` the rest of the module uses (native-safe).
 */
@ServiceImplementation
class PipelineRunServiceImpl(
    private val runRepository: PipelineRunRepository,
    private val runLogRepository: PipelineRunLogRepository,
    private val resultStore: PipelineRunResultStore,
    private val nodeExecutionRepository: NodeExecutionRepository,
    private val iterationRepository: PipelineRunIterationRepository,
    private val rollbackRepository: RollbackRepository,
    private val pipelineService: PipelineService,
    private val executor: PipelineExecutor,
    private val securityService: SecurityService,
    private val config: PipelinesRuntimeConfiguration,
    private val pubSub: PubSubService
) : PipelineRunService {

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

    private val log = LoggerFactory.getLogger(PipelineRunServiceImpl::class.java)

    private val awaitListSerializer = ListSerializer(PipelineRunAwait.serializer())

    /**
     * Admission caps: shed (return false) when the pipeline is at its
     * concurrency or per-minute cap, before any run state is created. A soft cap — the count and the
     * insert are not one atomic step, so a burst may admit slightly over.
     */
    private suspend fun admit(pipeline: Pipeline): Boolean {
        pipeline.maxConcurrentRuns?.takeIf { it > 0 }?.let { cap ->
            val active = runRepository.countActive(pipeline.id)
            if (active >= cap) {
                log.warn("Pipeline {} run shed: at concurrency cap {} ({} in flight)", pipeline.id, cap, active)
                return false
            }
        }
        pipeline.maxRunsPerMinute?.takeIf { it > 0 }?.let { rate ->
            val recent = runRepository.countStartedSince(pipeline.id, java.time.OffsetDateTime.now().minusMinutes(1))
            if (recent >= rate) {
                log.warn("Pipeline {} run shed: at rate limit {}/min ({} in the last minute)", pipeline.id, rate, recent)
                return false
            }
        }
        return true
    }

    override suspend fun start(
        pipeline: Pipeline,
        input: PipelineValue,
        eventName: String,
        inputCreated: OffsetDateTime,
        authentication: AuthenticationContext?,
    ): PipelineRun? {
        if (!admit(pipeline)) return null
        // Create the durable run (snapshots the graph + seed). The driving job is the executor job we are
        // running inside (a triggered/scheduled run) so backing work attaches to it as children; null for
        // an on-demand run, which is driven by an enqueued run job instead.
        val run = runRepository.add(
            PipelineRun(
                pipelineId = pipeline.id,
                status = PipelineRunStatus.RUNNING,
                eventName = eventName,
                graphSnapshot = pipelineService.graphAsJsonElement(pipeline),
                input = input.encode(graphJson()),
                inputType = input.typeName,
                runJobId = jobOrNull()?.getId(),
                // Capture the originating caller of an on-demand run so the durable run (driven async by
                // its own run job, through backing work and resumes) executes under their security context;
                // null for a triggered/scheduled run, which drives under the service account.
                principalId = authentication?.principal()?.id,
            )
        )
        publishRunUpdate(run.id, runStatus = PipelineRunStatus.RUNNING)
        if (authentication != null) {
            // On-demand (manual / API): the initiating request thread is NOT a run job, so a node that
            // suspends would have no run job to attach its backing work to — driving inline here would
            // enqueue a parentless backing job whose completion can never resume the run, orphaning it
            // in SUSPENDED. Instead enqueue a run job to drive it durably, exactly as a triggered run is
            // driven; the brief block below lets a fast run settle so a request/response caller still
            // gets its Output. The run carries the caller's principal (captured above), so the run job —
            // and its backing work and resumes — execute under the caller's security context; the entry
            // point verified the caller's authorization before this call.
            enqueueManualRun(run.id)
        } else {
            // Triggered / scheduled: we ARE the run job (jobOrNull()), so drive inline and let a
            // suspending node attach its backing jobs to us while we are held locked.
            val sink = NodeExecutionBuffer()
            val comp = RollbackBuffer()
            drive(
                run, pipeline, input, null, emptyList(), sink, comp,
                runContext(run.id, inputCreated, sink, comp, run.runJobId, jobOrNull(), run.principalId),
            )
        }
        var current = runRepository.getById(run.id)
        // An on-demand caller (authentication present) is synchronous and wants a settled result: block
        // briefly for a run that parked on fast backing work to finish before handing back the handle, so
        // a request/response API pipeline returns its Output rather than a SUSPENDED handle. A triggered /
        // scheduled run (no authentication) never waits — it must not hold the worker while a child is in flight.
        // Transactional callers cannot observe completion until commit releases the driving job.
        if (authentication != null && connectionOrNull()?.inTransaction != true) {
            val deadlineNanos = System.nanoTime() + config.onDemandRunMaxBlockMillis * 1_000_000
            while (current != null && !current.status.isTerminal && System.nanoTime() < deadlineNanos) {
                delay(ON_DEMAND_POLL_MILLIS.milliseconds)
                current = runRepository.getById(run.id)
            }
        }
        return current
    }

    override suspend fun startIteration(
        parentRunId: UUID,
        nodeId: String,
        bodyPipelineId: UUID,
        items: List<JsonElement>,
        continueOnError: Boolean,
        inputCreated: OffsetDateTime,
        runJob: bosca.sharedqueue.jobs.Job?,
        runJobId: UUID?,
        maxConcurrency: Int,
    ) {
        // Bounded (> 0) → only the first N children start now; the rest start lazily from the stored
        // items as siblings report (maybeStartNextIterationChild). The bound counts in-flight CHILD
        // RUNS, so a child parked on a durable wait (a CI build, an approval gate) still holds its
        // slot — with 1 the items are truly sequential, which is what makes the release relay's
        // dependency-ordered build list an actual build ORDER.
        val bounded = maxConcurrency > 0 && maxConcurrency < items.size
        // Open the aggregation (idempotent) BEFORE starting children, so a child that completes always
        // finds it. The body pipeline is loaded once.
        iterationRepository.create(
            parentRunId, nodeId, items.size, continueOnError,
            maxConcurrency = if (bounded) maxConcurrency else 0,
            items = if (bounded) JsonArray(items) else null,
        )
        val body = pipelineService.get(bodyPipelineId)
            ?: error("ForEach iteration: body pipeline not found: $bodyPipelineId")
        val graphSnapshot = pipelineService.graphAsJsonElement(body)
        // Create each starting item's child run row, then drive it in its OWN child-run job attached to
        // the parent run job. A child reports back here (reportIterationResult) on completion; one that
        // suspends parks durably in its own run job and reports when it later resumes.
        val starting = if (bounded) items.take(maxConcurrency) else items
        starting.forEachIndexed { index, item ->
            startIterationChild(body.id, graphSnapshot, parentRunId, nodeId, index, item, runJob, runJobId)
        }
    }

    /**
     * Create + drive one item's child run, idempotently: (parent, node, item) is unique, so an insert
     * that conflicts (at-least-once redelivery of the suspend or of a sibling's completion report)
     * re-enqueues the already-created child instead — driving an already-running or terminal run is a
     * no-op, while a child that was created but never enqueued (a crash between the two) gets its job.
     */
    private suspend fun startIterationChild(
        bodyPipelineId: UUID,
        graphSnapshot: JsonElement,
        parentRunId: UUID,
        nodeId: String,
        index: Int,
        item: JsonElement,
        runJob: bosca.sharedqueue.jobs.Job?,
        runJobId: UUID?,
    ) {
        val itemValue = PipelineValue.ofJson(item)
        val child = try {
            runRepository.add(
                PipelineRun(
                    pipelineId = bodyPipelineId,
                    status = PipelineRunStatus.RUNNING,
                    eventName = ITERATION_EVENT,
                    graphSnapshot = graphSnapshot,
                    input = itemValue.encode(graphJson()),
                    inputType = itemValue.typeName,
                    parentRunId = parentRunId,
                    parentNodeId = nodeId,
                    itemIndex = index,
                ),
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            runRepository.getByParentItem(parentRunId, nodeId, index)
                ?: throw e // not the unique-child conflict — surface the real failure
        }
        enqueueChildRun(child.id, runJob, runJobId)
    }

    override suspend fun runChildPipeline(
        parentRunId: UUID,
        nodeId: String,
        bodyPipelineId: UUID,
        input: PipelineValue,
        inputCreated: OffsetDateTime,
        runJob: bosca.sharedqueue.jobs.Job?,
        runJobId: UUID?,
    ) {
        val body = pipelineService.get(bodyPipelineId)
            ?: error("Run Pipeline: body pipeline not found: $bodyPipelineId")
        body.inputNode?.schema?.let { schema ->
            val violations = JsonSchemaValidator.validate(input.encode(graphJson()), schema)
            check(violations.isEmpty()) {
                "Run Pipeline: input does not match pipeline '${body.name}' input schema — ${violations.joinToString("; ")}"
            }
        }
        // itemIndex = null marks a single-child (scalar passthrough) parent, vs ForEach's indexed children.
        val child = runRepository.add(
            PipelineRun(
                pipelineId = body.id,
                status = PipelineRunStatus.RUNNING,
                eventName = RUN_PIPELINE_EVENT,
                graphSnapshot = pipelineService.graphAsJsonElement(body),
                input = input.encode(graphJson()),
                inputType = input.typeName,
                parentRunId = parentRunId,
                parentNodeId = nodeId,
                itemIndex = null,
            ),
        )
        enqueueChildRun(child.id, runJob, runJobId)
    }

    /**
     * Drive a just-created child run ([childRunId]) in its own [PipelineChildRunJob], attached as a
     * child of the parent run job so the parent stays open while the child is in flight (and the child
     * can itself suspend durably). The child reports back via [reportIterationResult] on completion.
     * Attaches to the locked parent run job directly when driven inside its lock (re-`getJob` would
     * re-enter the held lock), else loads it by id; with neither (a non-durable-by-job run) the child
     * job is enqueued unparented.
     */
    private suspend fun enqueueChildRun(childRunId: UUID, runJob: bosca.sharedqueue.jobs.Job?, runJobId: UUID?) {
        val enqueuer = provide<bosca.sharedqueue.jobs.JobConfigurationEnqueuer>(name = bosca.pipelines.trigger.PipelineChildRunJobExecutor.NAME)
        val config = graphJson().encodeToJsonElement(
            bosca.pipelines.trigger.PipelineChildRunJob.serializer(),
            bosca.pipelines.trigger.PipelineChildRunJob(childRunId),
        )
        val childJob = enqueuer.prepare(config)
        val queue = enqueuer.queue()
        when {
            runJob != null -> runJob.addChild(childJob, runOnParentComplete = false)
            runJobId != null -> try {
                queue.getJob(runJobId) { parent ->
                    parent?.addChild(childJob, runOnParentComplete = false)
                    parent?.let { queue.setJob(it) }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: bosca.sharedqueue.jobs.LockAcquisitionException) {
                // The run job exists but is locked (a live cascade holds it) — failing here lets the
                // at-least-once redelivery retry the report with the join intact. Don't degrade.
                throw e
            } catch (e: Exception) {
                // The run job's state is gone (it fully completed before this item could attach). A
                // child RUN doesn't need the job-tree join for correctness — it reports its result
                // through reportIterationResult — so run it unparented rather than stranding the
                // iteration. Loud: the run job dying before its run is terminal is a bug.
                log.error(
                    "Run job {} not available to parent child run {} — enqueuing it unparented: {}",
                    runJobId, childRunId, e.message,
                )
            }
        }
        queue.enqueue(childJob)
    }

    /**
     * Enqueue an on-demand run's driving job ([bosca.pipelines.trigger.PipelineManualRunJob]). Unlike a
     * child run, this is a **top-level** run job (no parent to attach to) — its
     * [bosca.pipelines.trigger.PipelineManualRunJobExecutor] carries the drive listener and drives the
     * already-created run, so a suspending node parks durably and resumes the normal way.
     */
    private suspend fun enqueueManualRun(runId: UUID) {
        val enqueuer = provide<bosca.sharedqueue.jobs.JobConfigurationEnqueuer>(
            name = bosca.pipelines.trigger.PipelineManualRunJobExecutor.NAME,
        )
        val config = graphJson().encodeToJsonElement(
            bosca.pipelines.trigger.PipelineManualRunJob.serializer(),
            bosca.pipelines.trigger.PipelineManualRunJob(runId),
        )
        enqueuer.enqueue(config) {}
    }

    override suspend fun complete(runId: UUID, status: PipelineRunStatus, error: String?) {
        runRepository.complete(runId, status, null, error)
    }

    override suspend fun get(runId: UUID): PipelineRun? = runRepository.getById(runId)

    override suspend fun listActive(offset: Long, limit: Int): List<PipelineRun> =
        runRepository.listActive(offset, limit)

    override suspend fun listChildren(parentRunId: UUID, nodeId: String): List<PipelineRun> =
        runRepository.listByParentAndNode(parentRunId, nodeId)

    override suspend fun listDeadLetter(offset: Long, limit: Int): List<PipelineRun> =
        runRepository.listFailed(offset, limit)

    override suspend fun restart(runId: UUID): PipelineRun? {
        val source = runRepository.getById(runId) ?: return null
        // Restart against the CURRENT pipeline so a fix applies; fall back to the source's snapshot graph
        // if the pipeline was deleted since (so a dead-lettered run of a removed pipeline is still restartable).
        val pipeline = pipelineService.get(source.pipelineId) ?: pipelineService.decodeGraph(source.graphSnapshot).let { graph ->
            Pipeline(id = source.pipelineId, name = "restart $runId", acceptedInputType = "", nodes = graph.nodes, edges = graph.edges)
        }
        val input = reconstructInput(source)
        if (!admit(pipeline)) return null
        // A restart is operator-initiated, not running inside a run job, so — like a manual run — it is
        // driven by an enqueued run job rather than inline (inline would orphan the fresh run if it
        // suspended). It preserves the source run's principal so the replay executes under the same
        // security context the original did, and returns the RUNNING handle immediately (the run is
        // tracked asynchronously, not blocked on).
        val run = runRepository.add(
            PipelineRun(
                pipelineId = pipeline.id,
                status = PipelineRunStatus.RUNNING,
                eventName = RESTART_EVENT,
                graphSnapshot = pipelineService.graphAsJsonElement(pipeline),
                input = input.encode(graphJson()),
                inputType = input.typeName,
                principalId = source.principalId,
            )
        )
        publishRunUpdate(run.id, runStatus = PipelineRunStatus.RUNNING)
        enqueueManualRun(run.id)
        return runRepository.getById(run.id)
    }

    override suspend fun purgeExpiredRuns(): Int {
        val now = java.time.OffsetDateTime.now()
        val purgedRuns = runRepository.purgeTerminalBefore(now.minusDays(config.runStateRetentionDays))
        val purgedHistory = runLogRepository.deleteFinishedBefore(now.minusDays(config.runHistoryRetentionDays))
        if (purgedRuns > 0 || purgedHistory > 0) {
            log.info("pipeline retention sweep: soft-deleted {} run(s), deleted {} history row(s)", purgedRuns, purgedHistory)
        }
        return purgedRuns + purgedHistory
    }

    /**
     * Rebuild a run's seed [PipelineValue] from its stored encoded input + origin type (for [replay]).
     * Typed-event runs decode through the Event Catalog's compiled serializer (native-safe); JSON-input
     * runs (or any with no catalogued serializer) pass the stored JSON through.
     */
    @OptIn(InternalDI::class)
    private suspend fun reconstructInput(run: PipelineRun): PipelineValue {
        val inputJson = run.input ?: JsonNull
        val serializer = run.inputType?.let { typeName ->
            ProviderRegistry.findAll(EventCatalogRegistrar::class)
                .filter { it.exists }
                .firstNotNullOfOrNull { it.get().serializers[typeName] }
        }
        @Suppress("UNCHECKED_CAST")
        return if (serializer != null) {
            PipelineValue.of(graphJson().decodeFromJsonElement(serializer as KSerializer<Any>, inputJson), serializer)
        } else {
            PipelineValue.ofJson(inputJson)
        }
    }

    override suspend fun nodeTimeline(runId: UUID): List<NodeExecutionRecord> =
        nodeExecutionRepository.listForRun(runId)

    override suspend fun steps(runId: UUID): List<RunStep> {
        val run = runRepository.getById(runId) ?: return emptyList()
        return stepsOfRun(run, depth = 0, item = null, seen = mutableSetOf(run.id))
    }

    /** The step rows of one run's graph snapshot, recursing into its fan-out children. */
    private suspend fun stepsOfRun(run: PipelineRun, depth: Int, item: String?, seen: MutableSet<UUID>): List<RunStep> {
        if (depth > MAX_STEP_DEPTH) return emptyList()
        val graph = run.graphSnapshot as? JsonObject ?: return emptyList()
        // The durable per-node record is the truth: the run row's nodeOutputs are WORKING state and are
        // not retained once a run completes — a finished child's milestones must still read DONE.
        val recordByNode = nodeTimeline(run.id).associateBy({ it.nodeId }, { it.status })
        val completed = ((run.nodeOutputs as? JsonObject)?.keys ?: emptySet()) +
            recordByNode.filterValues { it == NodeExecutionStatus.OK }.keys
        val skippedNodes = recordByNode.filterValues { it == NodeExecutionStatus.SKIPPED }.keys
        val awaitingIds = (run.awaiting as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("nodeId") as? JsonPrimitive)?.contentOrNull }
            ?.toSet() ?: emptySet()
        val failed = run.status == PipelineRunStatus.FAILED || run.status == PipelineRunStatus.CANCELLED
        val active = !run.status.isTerminal

        // Channel attribution: rows downstream of ONE artifact.select belong to that select's channel.
        // A select whose output is recorded as null found NO artifact — nothing to deploy on that
        // channel, so its rows are omitted entirely rather than shown as never-arriving PENDING.
        val channels = channelAttribution(graph)
        val deadChannelNodes = channels.entries
            .filter { (selectId, _) -> (run.nodeOutputs as? JsonObject)?.get(selectId) is JsonNull }
            .flatMapTo(mutableSetOf()) { it.value.nodes }
        // A node the executor SKIPPED (branch not taken / empty channel) is not a pending milestone —
        // it will never arrive; its row is omitted, exactly like a dead channel's.
        deadChannelNodes += skippedNodes

        val rows = mutableListOf<RunStep>()
        // The first not-yet-passed row is where the run currently is (RUNNING/FAILED); later ones are PENDING.
        var reachedIncomplete = false
        for (node in orderedGraphNodes(graph)) {
            val nodeId = (node["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (nodeId in deadChannelNodes) continue
            val channelType = channels.entries.singleOrNull { nodeId in it.value.nodes }?.value?.artifactType
            val done = nodeId in completed
            when ((node["type"] as? JsonPrimitive)?.contentOrNull) {
                "status" -> {
                    val title = node.text("title") ?: node.text("name") ?: nodeId
                    val status = when {
                        done -> RunStepStatus.DONE
                        !reachedIncomplete && failed -> RunStepStatus.FAILED
                        !reachedIncomplete && active -> RunStepStatus.RUNNING
                        else -> RunStepStatus.PENDING
                    }
                    if (!done) reachedIncomplete = true
                    rows += RunStep(nodeId, title, RunStepKind.STATUS, status, depth, item, runId = run.id, type = "status", channelType = channelType)
                }
                "gate.approval", "waitForInput" -> {
                    val title = node.text("prompt") ?: node.text("name") ?: nodeId
                    val status = when {
                        done -> RunStepStatus.DONE
                        // Only a LIVE run waits: a rejected gate fails the run but leaves the stale
                        // awaiting entry behind — showing WAITING (with Approve/Reject) on a dead run
                        // invites decisions that can't take effect.
                        nodeId in awaitingIds && active -> RunStepStatus.WAITING
                        !reachedIncomplete && failed -> RunStepStatus.FAILED
                        else -> RunStepStatus.PENDING
                    }
                    if (!done) reachedIncomplete = true
                    rows += RunStep(
                        nodeId, title, RunStepKind.HUMAN, status, depth, item,
                        runId = run.id, type = (node["type"] as? JsonPrimitive)?.contentOrNull,
                        channelType = channelType,
                    )
                }
                "forEach", "runPipeline" -> {
                    val children = runRepository.listByParentAndNode(run.id, nodeId).filter { seen.add(it.id) }
                    if (children.isNotEmpty()) {
                        for (child in children) {
                            rows += stepsOfRun(child, depth + 1, childItemLabel(child, children.size, item), seen)
                        }
                    } else {
                        // Fan-out not reached yet — pre-show the child pipeline's declared steps.
                        node.text("pipelineId")?.let { childId ->
                            rows += stepsOfPipeline(UUID.parse(childId), depth + 1, visited = mutableSetOf())
                        }
                    }
                    if (!done) reachedIncomplete = true
                }
            }
        }
        return rows
    }

    /**
     * The per-item chip for a fan-out child's rows: nothing when the fan-out has a single child (no
     * disambiguation needed), else a human field from the child's own input — a RepositoryTag's `tag`,
     * or a `name`/`key`/`title` — falling back to "item N" only when the input names nothing.
     */
    private fun childItemLabel(child: PipelineRun, siblingCount: Int, inherited: String?): String? {
        val index = child.itemIndex ?: return inherited
        if (siblingCount <= 1) return inherited
        val input = child.input as? JsonObject
        val labelled = listOf("tag", "name", "key", "title").firstNotNullOfOrNull { field ->
            (input?.get(field) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return labelled ?: "item ${index + 1}"
    }

    /** One artifact-typed channel: the select's artifact type and every node downstream of it. */
    private data class Channel(val artifactType: String, val nodes: Set<String>)

    /**
     * Maps each `artifact.select` node id to its channel — the select's artifactType and the node ids
     * reachable downstream. A node fed by several selects belongs to none (can't be attributed).
     */
    private fun channelAttribution(graph: JsonObject): Map<String, Channel> {
        val nodes = (graph["nodes"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return emptyMap()
        val edges = (graph["edges"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return emptyMap()
        val outgoing = edges.groupBy(
            { (it["source"] as? JsonPrimitive)?.contentOrNull ?: "" },
            { (it["target"] as? JsonPrimitive)?.contentOrNull ?: "" },
        )
        val selects = nodes.filter { (it["type"] as? JsonPrimitive)?.contentOrNull == "artifact.select" }
        if (selects.isEmpty()) return emptyMap()
        val reach = mutableMapOf<String, MutableSet<String>>() // nodeId -> selects reaching it
        val bySelect = mutableMapOf<String, Pair<String, Set<String>>>()
        for (select in selects) {
            val selectId = (select["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            val artifactType = select.text("artifactType") ?: continue
            val visited = mutableSetOf<String>()
            val queue = ArrayDeque(outgoing[selectId].orEmpty())
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!visited.add(next)) continue
                reach.getOrPut(next) { mutableSetOf() } += selectId
                queue += outgoing[next].orEmpty()
            }
            bySelect[selectId] = artifactType to visited
        }
        // Exclusive attribution only: nodes reached by several selects belong to none.
        return bySelect.mapValues { (_, typed) ->
            Channel(typed.first, typed.second.filterTo(mutableSetOf()) { reach[it]?.size == 1 })
        }
    }

    override suspend fun stepsForTrigger(eventName: String): List<RunStep> =
        pipelineService.getAll()
            .filter { it.triggered && it.acceptedInputType == eventName && !it.isDeleted }
            .flatMap { stepsOfPipeline(it.id, depth = 0, visited = mutableSetOf()) }

    override suspend fun awaitingNodes(runId: UUID): List<bosca.pipelines.model.RunAwaitingNode> {
        val run = runRepository.getById(runId) ?: return emptyList()
        return awaitingNodesOf(run, item = null, seen = mutableSetOf(run.id), depth = 0)
    }

    /**
     * The parked-node rows of one run, drilled THROUGH fan-outs: a parked For Each / Run Pipeline is
     * never itself the answer — its non-terminal children's own parked nodes are, labelled per item.
     */
    private suspend fun awaitingNodesOf(
        run: PipelineRun,
        item: String?,
        seen: MutableSet<UUID>,
        depth: Int,
    ): List<bosca.pipelines.model.RunAwaitingNode> {
        if (depth > MAX_STEP_DEPTH) return emptyList()
        val nodesById = ((run.graphSnapshot as? JsonObject)?.get("nodes") as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.associateBy { (it["id"] as? JsonPrimitive)?.contentOrNull ?: "" }
            ?: emptyMap()
        val awaitingIds = (run.awaiting as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("nodeId") as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

        val rows = mutableListOf<bosca.pipelines.model.RunAwaitingNode>()
        for (nodeId in awaitingIds) {
            val node = nodesById[nodeId]
            val type = node?.text("type") ?: ""
            if (type == "forEach" || type == "runPipeline") {
                val siblings = runRepository.listByParentAndNode(run.id, nodeId)
                val children = siblings.filter { !it.status.isTerminal && seen.add(it.id) }
                val nested = children.flatMap { child ->
                    awaitingNodesOf(child, childItemLabel(child, siblings.size, item), seen, depth + 1)
                }
                if (nested.isNotEmpty()) {
                    rows += nested
                    continue
                }
            }
            val name = node?.text("name") ?: node?.text("title") ?: node?.text("prompt") ?: nodeId
            rows += bosca.pipelines.model.RunAwaitingNode(
                nodeId = nodeId,
                type = type,
                name = if (item != null) "$name ($item)" else name,
                runId = run.id,
            )
        }
        return rows
    }

    /** A not-yet-run pipeline's declared step rows, all PENDING — the pre-shown plan. */
    private suspend fun stepsOfPipeline(pipelineId: UUID, depth: Int, visited: MutableSet<UUID>): List<RunStep> {
        if (depth > MAX_STEP_DEPTH || !visited.add(pipelineId)) return emptyList()
        val pipeline = pipelineService.get(pipelineId) ?: return emptyList()
        val channels = channelAttribution(pipelineService.graphAsJsonElement(pipeline) as JsonObject)
        fun channelTypeOf(nodeId: String): String? =
            channels.entries.singleOrNull { nodeId in it.value.nodes }?.value?.artifactType
        val order = orderedNodeIds(pipeline.nodes.map { it.id }, pipeline.edges.map { it.source to it.target })
        val byId = pipeline.nodes.associateBy { it.id }
        val rows = mutableListOf<RunStep>()
        for (nodeId in order) {
            when (val node = byId[nodeId]) {
                is bosca.pipelines.builtin.StatusNode ->
                    rows += RunStep(node.id, node.title.ifBlank { node.name.ifBlank { node.id } }, RunStepKind.STATUS, RunStepStatus.PENDING, depth, type = "status", channelType = channelTypeOf(node.id))
                is bosca.pipelines.builtin.ApprovalGateNode ->
                    rows += RunStep(node.id, node.prompt.ifBlank { node.name.ifBlank { node.id } }, RunStepKind.HUMAN, RunStepStatus.PENDING, depth, type = "gate.approval", channelType = channelTypeOf(node.id))
                is bosca.pipelines.builtin.WaitForInputNode ->
                    rows += RunStep(node.id, node.prompt.ifBlank { node.name.ifBlank { node.id } }, RunStepKind.HUMAN, RunStepStatus.PENDING, depth, type = "waitForInput", channelType = channelTypeOf(node.id))
                is bosca.pipelines.builtin.ForEach ->
                    node.pipelineId?.let { rows += stepsOfPipeline(it, depth + 1, visited) }
                is bosca.pipelines.builtin.RunPipelineNode ->
                    node.pipelineId?.let { rows += stepsOfPipeline(it, depth + 1, visited) }
                else -> {}
            }
        }
        return rows
    }

    /** Graph-snapshot nodes in topological order (Kahn; declaration order breaks ties). */
    private fun orderedGraphNodes(graph: JsonObject): List<JsonObject> {
        val nodes = (graph["nodes"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return emptyList()
        val ids = nodes.mapNotNull { (it["id"] as? JsonPrimitive)?.contentOrNull }
        val edges = (graph["edges"] as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull { edge ->
            val source = (edge["source"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val target = (edge["target"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            source to target
        } ?: emptyList()
        val order = orderedNodeIds(ids, edges)
        val byId = nodes.associateBy { (it["id"] as? JsonPrimitive)?.contentOrNull }
        return order.mapNotNull { byId[it] }
    }

    /** Kahn's algorithm over [ids]/[edges]; declaration order breaks ties, cycles fall back to declaration order. */
    private fun orderedNodeIds(ids: List<String>, edges: List<Pair<String, String>>): List<String> {
        val inDegree = ids.associateWith { 0 }.toMutableMap()
        val outgoing = mutableMapOf<String, MutableList<String>>()
        for ((source, target) in edges) {
            if (source !in inDegree || target !in inDegree) continue
            inDegree[target] = (inDegree[target] ?: 0) + 1
            outgoing.getOrPut(source) { mutableListOf() }.add(target)
        }
        val queue = ArrayDeque(ids.filter { inDegree[it] == 0 })
        val order = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            order.add(id)
            for (next in outgoing[id].orEmpty()) {
                val remaining = (inDegree[next] ?: 0) - 1
                inDegree[next] = remaining
                if (remaining == 0) queue.add(next)
            }
        }
        // A cycle leaves nodes unvisited — append them in declaration order rather than dropping rows.
        return if (order.size == ids.size) order else order + ids.filter { it !in order.toSet() }
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    override suspend fun nodeMetrics(pipelineId: UUID): List<NodeMetrics> =
        nodeExecutionRepository.metricsForPipeline(pipelineId)

    override suspend fun recordLog(log: PipelineRunLog): PipelineRunLog = runLogRepository.add(log)

    override suspend fun listRunHistory(pipelineId: UUID, offset: Long, limit: Int): List<PipelineRunLogWithName> =
        runLogRepository.listForPipeline(pipelineId, offset, limit)

    override suspend fun listAllRunHistory(offset: Long, limit: Int): List<PipelineRunLogWithName> =
        runLogRepository.listAll(offset, limit)

    override suspend fun process(runId: UUID) {
        val run = runRepository.getById(runId) ?: return
        // Only the initial drive of a fresh child run: a duplicate child-run-job delivery (at-least-once)
        // finds it already RUNNING-then-suspended or terminal and no-ops; a suspended child continues via
        // its own resume, never a re-drive from the seed.
        if (run.status != PipelineRunStatus.RUNNING) return
        val graph = pipelineService.decodeGraph(run.graphSnapshot)
        val pipeline = Pipeline(
            id = run.pipelineId,
            name = "run ${run.id}",
            acceptedInputType = "",
            nodes = graph.nodes,
            edges = graph.edges,
        )
        // Drive under the service account, threading the run job so suspendable nodes attach backing jobs.
        // Reconstruct the seed through the Event Catalog (native-safe) so a typed-event input — an
        // on-demand run of an event-triggered pipeline — keeps its type; a child run's uncatalogued item
        // type falls back to plain JSON, unchanged from before.
        val sink = NodeExecutionBuffer()
        val comp = RollbackBuffer()
        drive(
            run, pipeline, reconstructInput(run), null, emptyList(), sink, comp,
            runContext(run.id, run.createdAt, sink, comp, run.runJobId, jobOrNull(), run.principalId),
        )
    }

    override suspend fun resume(
        runId: UUID,
        nodeId: String,
        succeeded: Boolean,
        error: String?,
        runJob: bosca.sharedqueue.jobs.Job?,
    ) {
        val run = runRepository.getById(runId) ?: return
        if (run.status.isTerminal) return
        val outputs = run.nodeOutputs as? JsonObject ?: JsonObject(emptyMap())
        // Idempotency under at-least-once redelivery: the node's output is already recorded, or the
        // run is no longer awaiting this node — either way a duplicate resume is a no-op.
        if (outputs.containsKey(nodeId)) return
        val awaits = decodeAwaits(run.awaiting)
        if (awaits.none { it.nodeId == nodeId }) return

        // Reconstruct the snapshot graph + this node's descriptor once — used both to route the
        // outcome and to enforce error-port wiring on the resumed node.
        val graph = pipelineService.decodeGraph(run.graphSnapshot)
        val node = graph.nodes.firstOrNull { it.id == nodeId }
        val descriptor = node?.let { pipelineService.descriptorFor(it) }

        // The node's resolved output + the port it emits on. Success: the staged value on its stored
        // port (a plain gate stores none; a classifying node like a HubSpot write stores
        // out/alreadyExists). Failure: route to the node's first wired error port, or fail the run.
        val resolved: PipelineValue = if (succeeded) {
            val outcome = resultStore.get(runId, nodeId)
            val value = PipelineValue.ofJson(outcome?.value ?: JsonNull)
            val outcomePort = outcome?.port
            if (outcomePort != null) value.onPort(outcomePort) else value
        } else {
            val errorPort = firstWiredErrorPort(descriptor, graph.edges, nodeId)
            if (errorPort == null) {
                resultStore.remove(runId, nodeId)
                recordResumedNode(runId, nodeId, NodeExecutionStatus.FAILED, null, error ?: "backing work for node '$nodeId' failed", null)
                finish(run, PipelineRunStatus.FAILED, null, error ?: "backing work for node '$nodeId' failed")
                return
            }
            PipelineValue.ofJson(buildJsonObject { put("error", error ?: "backing work failed") }).onPort(errorPort)
        }

        // No silent success: a value emitted on a DECLARED error port that nothing consumes fails the
        // run — the same rule the executor enforces for in-line nodes, applied to a resumed node
        // (whose output is supplied here, not produced by node.run).
        resolved.port?.let { port ->
            val isErrorPort = descriptor?.let { d -> d.outputs.any { it.name == port && it.error } } == true
            val wired = graph.edges.any { it.source == nodeId && it.sourcePort == port }
            if (isErrorPort && !wired) {
                resultStore.remove(runId, nodeId)
                val message = "node '$nodeId' emitted on unhandled error port '$port'" + (error?.let { ": $it" } ?: "")
                recordResumedNode(runId, nodeId, NodeExecutionStatus.FAILED, port, message, null)
                finish(run, PipelineRunStatus.FAILED, null, message)
                return
            }
        }

        val remaining = awaits.filterNot { it.nodeId == nodeId }
        // Persist the resolved value into the checkpoint and optimistic-lock the transition back to
        // RUNNING; null means another resume already won.
        val merged = JsonObject(outputs + (nodeId to resolved.encode(graphJson())))
        val running = runRepository.updateState(
            runId, PipelineRunStatus.RUNNING, merged, encodeAwaits(remaining), null, run.version,
        ) ?: return
        publishRunUpdate(runId, runStatus = PipelineRunStatus.RUNNING)
        resultStore.remove(runId, nodeId)
        // The resumed node's terminal event closes its timeline (it parked SUSPENDED earlier, here it
        // completes) — recorded after the optimistic-lock winner advances, so a lost race adds nothing.
        recordResumedNode(runId, nodeId, NodeExecutionStatus.OK, resolved.port, null, resolved.encode(graphJson()))

        val pipeline = Pipeline(
            id = running.pipelineId,
            name = "run ${running.id}",
            acceptedInputType = "",
            nodes = graph.nodes,
            edges = graph.edges,
        )
        // Re-drive from the merged checkpoint, still parked on the OTHER outstanding awaits (not
        // re-run). The resolved value carries its port ONLY in memory for this activating re-drive
        // (so an outcome/error branch routes); the persisted checkpoint drops the port — fine, the
        // routed branch will have run by the time of any later re-drive.
        val inMemory = rehydrate(running.nodeOutputs).toMutableMap().apply { put(nodeId, resolved) }
        val state = ExecutionState(inMemory, remaining.mapTo(mutableSetOf()) { it.nodeId })
        val sink = NodeExecutionBuffer()
        val comp = RollbackBuffer()
        drive(running, pipeline, null, state, remaining, sink, comp, runContext(runId, running.createdAt, sink, comp, running.runJobId, runJob, running.principalId))
    }

    override suspend fun cancel(runId: UUID, reason: String?) {
        val run = runRepository.getById(runId) ?: return
        if (run.status.isTerminal) return
        finish(run, PipelineRunStatus.CANCELLED, null, reason)
    }

    // The repository update is itself gated on a terminal status, so an in-flight run yields 0 rows
    // (false) rather than being deleted out from under its drive — the caller cancels it first.
    override suspend fun delete(runId: UUID): Boolean =
        runRepository.softDeleteById(runId) > 0

    override suspend fun sweepStuckSuspended(): Int {
        val cutoff = java.time.OffsetDateTime.now().minusMinutes(config.suspendedRunMaxLifetimeMinutes)
        // A run parked on an Approval Gate waits for a PERSON — indefinitely, by design. The sweep only
        // reaps runs stuck on machine waits (jobs, timers, Wait for Input) that will never resume.
        val stuck = runRepository.findStuckSuspended(cutoff, SWEEP_BATCH)
            .filterNot { awaitingApprovalGate(it) }
        stuck.forEach { run ->
            finish(
                run,
                PipelineRunStatus.FAILED,
                null,
                "suspended longer than ${config.suspendedRunMaxLifetimeMinutes} minutes without resuming",
            )
        }
        return stuck.size
    }

    /** Whether any node the run is awaiting is an Approval Gate (matched by type in the graph snapshot). */
    private fun awaitingApprovalGate(run: PipelineRun): Boolean {
        // `awaiting` is a list of PipelineRunAwait objects — pull each entry's nodeId.
        val awaiting = (run.awaiting as? JsonArray)
            ?.mapNotNull { ((it as? JsonObject)?.get("nodeId") as? JsonPrimitive)?.contentOrNull }
            ?.toSet() ?: return false
        if (awaiting.isEmpty()) return false
        val nodes = (run.graphSnapshot as? JsonObject)?.get("nodes") as? JsonArray ?: return false
        return nodes.any { node ->
            val obj = node as? JsonObject ?: return@any false
            (obj["id"] as? JsonPrimitive)?.content in awaiting &&
                (obj["type"] as? JsonPrimitive)?.content == APPROVAL_GATE_TYPE
        }
    }

    /**
     * The error output port a genuine backing-job failure routes to: [nodeId]'s wired declared error
     * ports, preferring the conventionally-named `"error"` (a classifying node like a HubSpot write
     * also declares `alreadyExists`, but that outcome arrives via the success path with its own port —
     * a real failure belongs on the generic `error` branch). `null` when no error port is wired.
     */
    private fun firstWiredErrorPort(
        descriptor: NodeDescriptor?,
        edges: List<PipelineEdge>,
        nodeId: String,
    ): String? {
        val wired = descriptor?.outputs.orEmpty().filter { slot ->
            slot.error && edges.any { it.source == nodeId && it.sourcePort == slot.name }
        }.map { it.name }
        return wired.firstOrNull { it == ERROR_PORT } ?: wired.firstOrNull()
    }

    /**
     * Evaluate, then persist the outcome: completion (terminal + history) or suspension. [baseAwaits]
     * are the awaits already outstanding for this run (empty for a fresh run; the still-unresolved
     * ones on a resume) — the run stays parked on those plus any newly parked this evaluation.
     */
    private suspend fun drive(
        run: PipelineRun,
        pipeline: Pipeline,
        input: PipelineValue?,
        state: ExecutionState?,
        baseAwaits: List<PipelineRunAwait>,
        sink: NodeExecutionBuffer,
        rollback: RollbackBuffer,
        context: PipelineContext,
    ) {
        try {
            val result = executor.execute(pipeline, input, context, state)
            // Flush this drive's per-node events AFTER the concurrent fan-out has returned, so the
            // timeline is written sequentially on the run's connection. The saga log
            // is flushed the same way so a later failure can roll back.
            flushNodeRecords(run.id, sink)
            flushRollback(run.id, rollback)
            when (result) {
                is ExecutionResult.Completed -> {
                    finish(run, PipelineRunStatus.OK, result.output, null)
                    // A ForEach/RunPipeline child reports its output to the parent on completion.
                    reportIterationResult(run, result.output, null)
                }
                is ExecutionResult.Suspended -> suspendRun(run, result, baseAwaits)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flushNodeRecords(run.id, sink) // persist the failing node's event too
            flushRollback(run.id, rollback) // and any rollback-enabled completions before we roll back
            log.error("Pipeline run {} (pipeline {}) failed", run.id, run.pipelineId, e)
            finish(run, PipelineRunStatus.FAILED, null, e.message ?: e.toString())
        }
    }

    private suspend fun suspendRun(
        run: PipelineRun,
        result: ExecutionResult.Suspended,
        baseAwaits: List<PipelineRunAwait>,
    ) {
        val allAwaits = baseAwaits + result.newlyParked.map { PipelineRunAwait(it.nodeId) }
        // Persist the suspended checkpoint BEFORE scheduling the backing work: a fast-completing job
        // must never try to resume a run whose suspended state has not yet been written.
        runRepository.updateState(
            run.id, PipelineRunStatus.SUSPENDED, encodeOutputs(result.state.outputs), encodeAwaits(allAwaits), null, run.version,
        ) ?: error("Optimistic-lock conflict suspending pipeline run ${run.id}")
        publishRunUpdate(run.id, runStatus = PipelineRunStatus.SUSPENDED)
        // A park on an Approval Gate means a PERSON must now decide — announce it so notification
        // surfaces can reach the approvers. Announce-only and best-effort: a notify failure must never
        // break the suspend.
        announceApprovalGates(run, result.newlyParked.map { it.nodeId })
        result.newlyParked.forEach { it.enqueue() }
    }

    /** Publishes a [PipelineAwaitingApproval] for each NEWLY parked node that is an Approval Gate. */
    private suspend fun announceApprovalGates(run: PipelineRun, newlyParked: List<String>) {
        if (newlyParked.isEmpty()) return
        val nodes = (run.graphSnapshot as? JsonObject)?.get("nodes") as? JsonArray ?: return
        for (node in nodes) {
            val obj = node as? JsonObject ?: continue
            val nodeId = (obj["id"] as? JsonPrimitive)?.content ?: continue
            if (nodeId !in newlyParked) continue
            if ((obj["type"] as? JsonPrimitive)?.content != APPROVAL_GATE_TYPE) continue
            val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull
                ?: (obj["name"] as? JsonPrimitive)?.contentOrNull ?: ""
            try {
                pubSub.publish(
                    PipelineAwaitingApproval.CHANNEL,
                    PipelineAwaitingApproval.serializer(),
                    PipelineAwaitingApproval(runId = run.id, pipelineId = run.pipelineId, nodeId = nodeId, prompt = prompt),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to announce approval gate {} of run {}: {}", nodeId, run.id, e.message, e)
            }
        }
    }

    /**
     * Report a just-finished child run's outcome to its parent node (ForEach and
     * RunPipeline). No-op for a non-child run, or once the parent is terminal / no longer
     * parked on the node. Two shapes, by [PipelineRun.itemIndex]:
     *  - **null → single child** (RunPipeline): on success signal the parent with the child's output
     *    scalar; on failure fail the parent's await.
     *  - **indexed → ForEach iteration**: record the output (or an `{error,index}` marker) at the item's
     *    index; fail-fast (a child error with `continueOnError = false`) fails the parent now, otherwise
     *    once every item has reported, join in index order and signal the array.
     * Called from [drive] on success (it carries the [output]) and from [finish] on every non-success
     * terminal path ([error] set). Best-effort + idempotent (the merge overwrites a re-reported index;
     * a signal/resume on an already-terminal/unparked parent is a no-op), so at-least-once child
     * redelivery converges to one parent outcome.
     */
    private suspend fun reportIterationResult(run: PipelineRun, output: PipelineValue?, error: String?) {
        val parentRunId = run.parentRunId ?: return
        val parentNodeId = run.parentNodeId ?: return
        val parent = runRepository.getById(parentRunId) ?: return
        if (parent.status.isTerminal) return
        // The parent must still be parked on this node — if it already resumed/cleared this is a no-op.
        if (decodeAwaits(parent.awaiting).none { it.nodeId == parentNodeId }) return

        val index = run.itemIndex
        if (index == null) {
            // Single child (RunPipeline): the child's output IS the node's output.
            if (error != null) {
                resume(parentRunId, parentNodeId, succeeded = false, error = "sub-pipeline failed: $error")
            } else {
                deliverToParentAwait(parentRunId, parentNodeId, output?.encode(graphJson()) ?: JsonNull)
            }
            return
        }

        // ForEach iteration: aggregate by index.
        val resultJson = if (error != null) {
            buildJsonObject { put("error", error); put("index", index) }
        } else {
            output?.encode(graphJson()) ?: JsonNull
        }
        val iteration = iterationRepository.recordResult(parentRunId, parentNodeId, index.toString(), resultJson) ?: return
        if (error != null && !iteration.continueOnError) {
            // Fail-fast: one bad item fails the whole iteration (the ForEach node has no error port,
            // so this fails the parent run). Other children become harmless no-ops (parent terminal)
            // and a bounded iteration's not-yet-started items simply never start.
            resume(parentRunId, parentNodeId, succeeded = false, error = "item $index failed: $error")
            return
        }
        maybeStartNextIterationChild(parent, parentNodeId, iteration, index, run)
        val results = iteration.results as? JsonObject ?: return
        if (results.size >= iteration.total) {
            val joined = JsonArray((0 until iteration.total).map { results[it.toString()] ?: JsonNull })
            deliverToParentAwait(parentRunId, parentNodeId, joined)
        }
    }

    /**
     * Bounded-iteration handoff: completed item [completedIndex] starts item
     * `completedIndex + maxConcurrency` from the iteration's stored items. The mapping is
     * deterministic per completing child (no shared cursor to race on), and [startIterationChild]'s
     * unique-child guard absorbs at-least-once redelivery of the same report. The next child reuses
     * the completing child's graph snapshot, so every item of one iteration runs the same body even
     * if the pipeline is edited mid-run.
     */
    private suspend fun maybeStartNextIterationChild(
        parent: PipelineRun,
        nodeId: String,
        iteration: bosca.pipelines.repository.PipelineRunIteration,
        completedIndex: Int,
        completedChild: PipelineRun,
    ) {
        if (iteration.maxConcurrency <= 0) return
        val items = iteration.items as? JsonArray ?: return
        val nextIndex = completedIndex + iteration.maxConcurrency
        if (nextIndex >= iteration.total || nextIndex >= items.size) return
        try {
            startIterationChild(
                bodyPipelineId = completedChild.pipelineId,
                graphSnapshot = completedChild.graphSnapshot,
                parentRunId = parent.id,
                nodeId = nodeId,
                index = nextIndex,
                item = items[nextIndex],
                runJob = null,
                runJobId = parent.runJobId,
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A next item that cannot start would strand the iteration (nobody else will start it) —
            // fail the parent's await loudly rather than letting the run hang forever.
            log.error("Bounded iteration: failed to start item {} of run {} node {}: {}", nextIndex, parent.id, nodeId, e.message, e)
            resume(parent.id, nodeId, succeeded = false, error = "item $nextIndex failed to start: ${e.message}")
        }
    }

    /**
     * Deliver a child run's [payload] to the parent run's [nodeId] await: stage it as the node's output
     * and resume the parent successfully, so the value flows out the node's port and the run continues.
     * The parent here is always a control-flow node (RunPipeline / ForEach), so the payload is staged
     * unrouted. Idempotent via [resume]'s guards — a duplicate or already-resumed parent is a no-op.
     */
    private suspend fun deliverToParentAwait(runId: UUID, nodeId: String, payload: JsonElement) {
        resultStore.put(runId, nodeId, payload)
        resume(runId, nodeId, succeeded = true, error = null)
    }

    private suspend fun finish(run: PipelineRun, status: PipelineRunStatus, output: PipelineValue?, error: String?) {
        runRepository.complete(run.id, status, output?.encode(graphJson()), error)
        val finishedAt = java.time.OffsetDateTime.now()
        runLogRepository.add(
            PipelineRunLog(
                pipelineId = run.pipelineId,
                runId = run.id,
                eventName = run.eventName,
                outcome = status,
                startedAt = run.createdAt,
                finishedAt = finishedAt,
                durationMs = java.time.Duration.between(run.createdAt, finishedAt).toMillis(),
                errorMessage = error,
            )
        )
        publishRunUpdate(run.id, runStatus = status, error = error, at = finishedAt)
        // A non-OK terminal child (failed/cancelled/swept) reports its failure to the parent here —
        // finish() is the single writer for every non-success terminal path (resume's no-error-port
        // exit, the sweeper, cancel), which all bypass drive(). The success report stays in drive()
        // because only that path carries the child's output.
        if (status != PipelineRunStatus.OK) {
            reportIterationResult(run, null, error ?: status.name)
            // Saga rollback: undo the side effects of rollback-enabled nodes
            // that completed before this run failed/cancelled, in reverse order.
            runRollbacks(run.id)
        }
    }

    /**
     * Run the saga rollbacks for a terminated run: replay each
     * rollback-enabled node's rollback pipeline in reverse-completion order, feeding it that node's
     * output. Best-effort and isolated — a rollback that itself fails (or suspends, which a
     * rollback pipeline must not do) is logged and the rollback continues, so one bad rollback
     * never masks the original failure or blocks the others. Rollbacks run under the service account.
     */
    private suspend fun runRollbacks(runId: UUID) {
        val records = rollbackRepository.listForRunReversed(runId)
        if (records.isEmpty()) return
        val context = PipelineContext(securityService.impersonate(config.serviceAccount), graphJson())
        for (record in records) {
            try {
                val pipeline = pipelineService.get(record.rollbackPipelineId)
                if (pipeline == null) {
                    log.warn("Rollback pipeline {} for run {} node {} not found", record.rollbackPipelineId, runId, record.nodeId)
                    continue
                }
                executor.execute(pipeline, PipelineValue.ofJson(record.output ?: JsonNull), context).requireCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Rollback for run {} node {} failed", runId, record.nodeId, e)
            }
        }
    }

    private suspend fun runContext(
        runId: UUID,
        inputCreated: OffsetDateTime,
        sink: NodeExecutionSink?,
        rollbackSink: RollbackSink? = null,
        runJobId: UUID? = null,
        runJob: bosca.sharedqueue.jobs.Job? = null,
        // The run's originating principal — an on-demand run's caller, captured on the run row at start;
        // null for a triggered/scheduled run. The whole durable run (first pass, backing work, every
        // resume) is driven under this principal so the caller's security context traverses end to end,
        // falling back to the pipelines service account when absent.
        principalId: UUID? = null,
    ) = PipelineContext(
        principalId?.let { securityService.impersonate(it) } ?: securityService.impersonate(config.serviceAccount),
        graphJson(),
        inputCreated = inputCreated,
        runId = runId,
        runJobId = runJobId,
        runJob = runJob,
        nodeSink = sink,
        rollbackSink = rollbackSink,
    )

    /** Flush a drive's buffered per-node events to the run timeline (best-effort — see [persistNodeEvent]). */
    private suspend fun flushNodeRecords(runId: UUID, sink: NodeExecutionBuffer) {
        sink.drain().forEach { persistNodeEvent(runId, it) }
    }

    /** Flush a drive's buffered rollback-enabled completions to the saga log. */
    private suspend fun flushRollback(runId: UUID, buffer: RollbackBuffer) {
        buffer.drain().forEach { event ->
            try {
                rollbackRepository.add(RollbackRecord(runId, event.nodeId, event.rollbackPipelineId, event.output))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to record rollback for run {} node {}", runId, event.nodeId, e)
            }
        }
    }

    /**
     * Record the terminal event for a node the run service resumed (the executor does not re-run a
     * resumed node, so it never emits this) — closing the timeline it opened with a SUSPENDED event.
     */
    override suspend fun recordNodeAttemptFailure(runId: UUID, nodeId: String, error: String) {
        recordResumedNode(runId, nodeId, NodeExecutionStatus.FAILED, null, error, null)
    }

    private suspend fun recordResumedNode(
        runId: UUID,
        nodeId: String,
        status: NodeExecutionStatus,
        port: String?,
        error: String?,
        output: JsonElement?,
    ) {
        val now = java.time.OffsetDateTime.now()
        persistNodeEvent(runId, NodeExecutionEvent(nodeId, status, now, now, port, error, output))
    }

    /**
     * Persist one per-node timeline event. **Best-effort**: observability must never fail a run, so a
     * write error is logged and swallowed (cancellation always propagates). The output snapshot and
     * error are size-bounded so a huge value can't bloat the timeline table.
     */
    private suspend fun persistNodeEvent(runId: UUID, event: NodeExecutionEvent) {
        try {
            nodeExecutionRepository.add(
                NodeExecutionRecord(
                    runId = runId,
                    nodeId = event.nodeId,
                    status = event.status,
                    startedAt = event.startedAt,
                    finishedAt = event.finishedAt,
                    durationMs = java.time.Duration.between(event.startedAt, event.finishedAt).toMillis(),
                    port = event.port,
                    error = event.error?.take(MAX_ERROR_CHARS),
                    output = boundOutput(event.output),
                )
            )
            // Broadcast the per-node transition live, after the timeline
            // row is written. publishRunUpdate is itself best-effort + commit-deferred.
            publishRunUpdate(
                runId,
                nodeId = event.nodeId,
                nodeStatus = event.status,
                port = event.port,
                error = event.error,
                at = event.finishedAt,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to record node execution '{}' for run {}", event.nodeId, runId, e)
        }
    }

    /**
     * Broadcast a live run/node status change to the run's per-run channel,
     * deferred via [afterCommit] so a subscriber never observes a status the gating DB write rolled back.
     * **Best-effort**: observability must never fail a run, so a publish error is logged and swallowed
     * (cancellation always propagates). The error message is size-bounded like the timeline's.
     */
    private suspend fun publishRunUpdate(
        runId: UUID,
        runStatus: PipelineRunStatus? = null,
        nodeId: String? = null,
        nodeStatus: NodeExecutionStatus? = null,
        port: String? = null,
        error: String? = null,
        at: OffsetDateTime = java.time.OffsetDateTime.now(),
    ) {
        val update = PipelineRunUpdate(runId, runStatus, nodeId, nodeStatus, port, error?.take(MAX_ERROR_CHARS), at)
        afterCommit {
            try {
                pubSub.publish(PipelineRunService.runEventChannel(runId), PipelineRunUpdate.serializer(), update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to publish run update for run {}", runId, e)
            }
        }
    }

    /** Cap the stored output snapshot; an over-cap value is replaced with a small truncation marker. */
    private fun boundOutput(output: JsonElement?): JsonElement? {
        if (output == null) return null
        val encoded = output.toString()
        return if (encoded.length <= MAX_OUTPUT_CHARS) output
        else buildJsonObject {
            put("_truncated", true)
            put("chars", encoded.length)
        }
    }

    private suspend fun encodeOutputs(outputs: Map<String, PipelineValue?>): JsonObject =
        JsonObject(outputs.mapValues { (_, value) -> value?.encode(graphJson()) ?: JsonNull })

    /** Checkpointed values rehydrate as plain JSON (the typed origin is not preserved across suspend). */
    private fun rehydrate(nodeOutputs: JsonElement): Map<String, PipelineValue?> {
        val obj = nodeOutputs as? JsonObject ?: JsonObject(emptyMap())
        return obj.mapValues { (_, element) -> if (element is JsonNull) null else PipelineValue.ofJson(element) }
    }

    private suspend fun encodeAwaits(awaits: List<PipelineRunAwait>): JsonElement =
        graphJson().encodeToJsonElement(awaitListSerializer, awaits)

    private suspend fun decodeAwaits(element: JsonElement): List<PipelineRunAwait> =
        (element as? JsonArray)?.let { graphJson().decodeFromJsonElement(awaitListSerializer, it) } ?: emptyList()

    companion object {
        /** Run-history `eventName` for a ForEach child run. */
        const val ITERATION_EVENT = "foreach-item"

        /** Run-history `eventName` for a RunPipeline child run. */
        const val RUN_PIPELINE_EVENT = "run-pipeline-child"

        /** Poll cadence while an on-demand run blocks for a suspended run to settle. */
        private const val ON_DEMAND_POLL_MILLIS = 100L

        /** Run-history `eventName` for a restarted run. */
        const val RESTART_EVENT = "restart"

        /** Max runs failed per sweeper invocation — bounds one sweep; the next tick handles the rest. */
        private const val SWEEP_BATCH = 500

        /** The Approval Gate node type key — runs awaiting one are exempt from the stuck-suspended sweep. */
        private const val APPROVAL_GATE_TYPE = "gate.approval"

        /** Conventional generic error port a genuine backing-job failure prefers when routing. */
        private const val ERROR_PORT = "error"

        /** Cap on a stored per-node output snapshot (chars of its JSON); over-cap → a truncation marker. */
        private const val MAX_OUTPUT_CHARS = 16_384

        /** Cap on a stored per-node error message. */
        private const val MAX_ERROR_CHARS = 4_096

        /** Child-pipeline recursion cap for the step-oriented view. */
        private const val MAX_STEP_DEPTH = 5
    }
}
