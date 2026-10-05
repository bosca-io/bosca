package bosca.pipelines

import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.pipelines.model.NodeExecutionStatus
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.RetryPolicy
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeDescriptor
import bosca.pipelines.node.RollbackEvent
import bosca.pipelines.node.NodeExecutionEvent
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.node.SlotValidator
import bosca.pipelines.service.ExecutionResult
import bosca.pipelines.service.ExecutionState
import bosca.pipelines.service.Parked
import bosca.pipelines.service.PipelineExecutor
import bosca.serialization.OffsetDateTime
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Topological graph evaluator. Evaluates in topological **rounds**: each round runs every node whose
 * inbound source nodes have all produced an output, concurrently (fan-out); a multi-input node (e.g.
 * `ObjectsToMap`) gathers several inbound edges by target port (fan-in). One shared mutable
 * [PipelineContext] threads through every node.
 *
 * Re-entrant for durable runs: [evaluate] starts from a supplied set of node
 * outputs — just the seeded `InputNode` for a fresh run, or a rehydrated checkpoint for a resume —
 * and runs until the graph [ExecutionResult.Completed] or a node parks it
 * ([ExecutionResult.Suspended]). The executor is **domain-pure and repository-free**: it never
 * checkpoints or marks anything; on a suspend it hands the caller the checkpoint + the deferred
 * enqueue thunk and lets the durable run service persist before scheduling. Nodes already present in
 * the supplied outputs are never re-run — the basis of idempotent resume (action nodes don't re-fire).
 *
 * Cross-pipeline recursion (a `RunPipeline` node invoking a pipeline already running above it) is
 * guarded **here**, before any node executes: every run appends its pipeline's id to the context's
 * [PipelineContext.invocationStack], so a cycle or a chain deeper than [MAX_DEPTH] aborts without
 * re-executing a single node of the re-entered pipeline.
 *
 * Reflection-free: nodes carry their own behavior and their values carry their own serializers.
 */
@ServiceImplementation
class PipelineExecutorImpl : PipelineExecutor {

    override suspend fun execute(
        pipeline: Pipeline,
        input: PipelineValue?,
        context: PipelineContext,
        state: ExecutionState?,
    ): ExecutionResult {
        check(pipeline.id !in context.invocationStack) {
            "Pipeline run would re-enter pipeline '${pipeline.name}' (${pipeline.id}) — pipelines may " +
                "not invoke each other in a cycle (chain: " +
                "${(context.invocationStack + pipeline.id).joinToString(" → ")})"
        }
        check(context.invocationStack.size < MAX_DEPTH) {
            "Pipeline '${pipeline.name}' (${pipeline.id}) exceeds the maximum pipeline nesting depth of $MAX_DEPTH"
        }
        val ctx = PipelineContext(
            context.authentication,
            context.json,
            context.dryRun,
            context.trace,
            context.inputCreated,
            context.invocationStack + pipeline.id,
            context.runId,
            context.runJobId,
            context.runJob,
            context.nodeSink,
            context.rollbackSink,
        )
        val inboundByTarget: Map<String, List<PipelineEdge>> = pipeline.edges.groupBy { it.target }

        // A nodeId present in `current` means it has been evaluated; the value may be null (no output).
        // Resume from the checkpoint, or start fresh by seeding the InputNode.
        val current: HashMap<String, PipelineValue?> = if (state != null) {
            HashMap(state.outputs)
        } else {
            val inputNode = pipeline.nodes.firstOrNull { it is InputNode }
                ?: error("Pipeline ${pipeline.id} has no InputNode")
            val seed = requireNotNull(input) { "A fresh pipeline run requires an input value" }
            HashMap<String, PipelineValue?>().apply {
                put(inputNode.id, seed)
                ctx.trace?.recordOutput(inputNode.id, seed.encode(ctx.json))
            }
        }
        val meta = nodeMeta(ctx.json)
        val incomingAwaiting = state?.awaiting ?: emptySet()
        // Nodes that were SKIPPED (not run) — restored from the checkpoint so the skip cascade (a node
        // whose required input came from a skipped node skips too) is stable across a suspend/resume.
        val skipped = ConcurrentHashMap.newKeySet<String>().apply { addAll(state?.skipped ?: emptySet()) }
        // nodeId -> the suspend it returned this evaluation (the nodes newly parked here).
        val newlyParked = LinkedHashMap<String, NodeResult.Suspend>()
        // A node already parked (from a prior suspend) is neither re-run nor ready until its resume
        // promotes it into `current` — so it never re-fires its backing job.
        val remaining = pipeline.nodes.filter { it.id !in current && it.id !in incomingAwaiting }.toMutableList()
        while (true) {
            val ready = remaining.filter { node ->
                inboundByTarget[node.id].orEmpty().all { current.containsKey(it.source) }
            }
            if (ready.isEmpty()) break
            val results: List<Pair<String, NodeResult>> = coroutineScope {
                ready.map { node ->
                    async {
                        // Per-node timeline event: timed from here, emitted to the
                        // run's sink on each outcome. The executor only OBSERVES — the sink buffers and
                        // the run service persists after this drive, so no DB write happens mid-fan-out.
                        val startedAt = OffsetDateTime.now()
                        // Each timeline event carries the start of the work it describes (a single
                        // pre-run check, or one run attempt) so the timeline orders correctly even when
                        // retries produce several events for one node.
                        fun emit(status: NodeExecutionStatus, start: OffsetDateTime, port: String? = null, error: String? = null, output: kotlinx.serialization.json.JsonElement? = null) {
                            ctx.nodeSink?.record(NodeExecutionEvent(node.id, status, start, OffsetDateTime.now(), port, error, output))
                        }
                        val inbound = inboundByTarget[node.id].orEmpty()
                        val inputs = gatherInputs(inbound, current)
                        // The node type's declared slot constraints (null when nothing is loaded).
                        val descriptor = if (meta.descriptors.isEmpty()) null
                            else node.typeKey(meta.json)?.let { meta.descriptors[it] }
                        // A wired node is SKIPPED when no value reached it at all (every inbound edge
                        // yielded nothing — upstream produced no output, or a routing node took the other
                        // branch), OR when it is wired EXCLUSIVELY to a routing branch the upstream did NOT
                        // take, OR when an input port was left undefined by a node that was itself SKIPPED —
                        // so the skip cascades to every node that depends on a skipped one. The skip
                        // propagates through this node's own (absent) output.
                        val gatedBranch = branchNotTaken(inbound, current)
                        val lostDependency = dependsOnSkipped(descriptor, inbound, inputs, skipped)
                        if (inbound.isNotEmpty() && (inputs.isEmpty || gatedBranch || lostDependency)) {
                            val reason = when {
                                lostDependency -> "skipped — an input came from a skipped node"
                                gatedBranch -> "branch not taken — a routed input's branch was not taken"
                                else -> "branch not taken — no value reached this node"
                            }
                            ctx.trace?.recordSkip(node.id, reason)
                            emit(NodeExecutionStatus.SKIPPED, startedAt)
                            skipped.add(node.id)
                            return@async node.id to NodeResult.Output(null)
                        }
                        // Enforce the input-slot constraints against what actually arrived, before the
                        // node runs. A violation fails the run with a clear, per-port message.
                        val slots = descriptor?.inputs.orEmpty()
                        if (slots.isNotEmpty()) {
                            val violations = SlotValidator.validate(slots, inputs, ctx.json)
                            if (violations.isNotEmpty()) {
                                val message = "Node '${node.name.ifBlank { node.id }}' rejected its input — " +
                                    violations.joinToString("; ")
                                ctx.trace?.recordError(node.id, message)
                                emit(NodeExecutionStatus.FAILED, startedAt, error = message)
                                throw IllegalArgumentException(message)
                            }
                        }
                        // Run the node under its per-node reliability config: a
                        // timeout bounds each attempt, and a retry policy re-runs it on failure. Each
                        // failed attempt records its own FAILED timeline event; on exhaustion the
                        // failure propagates (and fails the run, or is routed by a wired error port
                        // for a node that emits one — that routing happens below, not here).
                        val outcome = try {
                            runNodeWithReliability(node, ctx, inputs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ctx.trace?.recordError(node.id, e.message ?: e.toString())
                            throw e
                        }
                        val result = outcome.result
                        when (result) {
                            is NodeResult.Output -> {
                                val out = result.value
                                // A value emitted on a declared ERROR port that nothing consumes fails the
                                // run — an unhandled error never reports success. (A wired error port routes
                                // to its branch; an unwired NON-error port simply ends that branch.)
                                out?.port?.let { port ->
                                    val isErrorPort = descriptor?.let { d -> d.outputs.any { it.name == port && it.error } } == true
                                    val wired = pipeline.edges.any { it.source == node.id && it.sourcePort == port }
                                    if (isErrorPort && !wired) {
                                        val message = "Node '${node.name.ifBlank { node.id }}' emitted on unhandled error " +
                                            "port '$port': ${out.encode(ctx.json)}"
                                        ctx.trace?.recordError(node.id, message)
                                        emit(NodeExecutionStatus.FAILED, outcome.startedAt, port = port, error = message)
                                        throw IllegalStateException(message)
                                    }
                                }
                                // Encode the output once when either the dry-run trace or the record sink wants it.
                                val encoded = if (out != null && (ctx.trace != null || ctx.nodeSink != null || node.rollbackPipeline != null)) out.encode(ctx.json) else null
                                encoded?.let { ctx.trace?.recordOutput(node.id, it) }
                                emit(NodeExecutionStatus.OK, outcome.startedAt, port = out?.port, output = encoded)
                                // Record a rollback-enabled completion: on a later
                                // terminal failure the run service runs this node's rollback pipeline,
                                // in reverse-completion order, to undo its side effect.
                                node.rollbackPipeline?.let { comp ->
                                    ctx.rollbackSink?.record(RollbackEvent(node.id, comp, encoded))
                                }
                            }
                            is NodeResult.Suspend -> emit(NodeExecutionStatus.SUSPENDED, outcome.startedAt)
                        }
                        node.id to result
                    }
                }.awaitAll()
            }
            // Apply completed outputs; park any nodes that suspended this round. A parked node is
            // intentionally left out of `current` (its output arrives on resume) and out of
            // `remaining` (it has run), so it is neither re-run nor treated as ready.
            for ((id, result) in results) {
                when (result) {
                    is NodeResult.Output -> current[id] = result.value
                    is NodeResult.Suspend -> newlyParked[id] = result
                }
            }
            remaining.removeAll(ready)
        }

        // Nothing is ready. If anything is parked (newly, or still outstanding from a prior suspend)
        // the run stays suspended; otherwise it has completed — or, with work left and nothing ready
        // and nothing parked, the graph has a cycle or a dangling edge.
        val stillAwaiting = incomingAwaiting + newlyParked.keys
        if (stillAwaiting.isEmpty()) {
            if (remaining.isNotEmpty()) {
                error("Pipeline ${pipeline.id} has a cycle or an edge to a missing node")
            }
            val outputNode = pipeline.nodes.firstOrNull { it is OutputNode }
                ?: return ExecutionResult.Completed(null)
            return ExecutionResult.Completed(current[outputNode.id])
        }
        return ExecutionResult.Suspended(
            ExecutionState(HashMap(current), stillAwaiting, skipped.toSet()),
            newlyParked.map { (id, suspend) -> Parked(id, suspend.enqueue) },
        )
    }

    /**
     * Runs a node under its per-node reliability config: each attempt is bounded by
     * [PipelineNode.timeoutSeconds] (a timeout is a failure), and a [PipelineNode.retry] policy re-runs
     * it up to `maxAttempts` with backoff. Every failed attempt is recorded as its own FAILED timeline
     * event (so retries are observable); the successful result returns, or the final failure is thrown.
     *
     * Only thrown exceptions and timeouts are retried — a node that returns a value on a declared error
     * port has *succeeded* (its routing is handled by the caller), and real cancellation always
     * propagates. The backoff wait is in-line; the timeout never kills a node that merely parked
     * (its `run` returns the suspend descriptor immediately).
     */
    private suspend fun runNodeWithReliability(
        node: PipelineNode,
        ctx: PipelineContext,
        inputs: NodeInputs,
    ): NodeOutcome {
        val policy = node.retry
        val maxAttempts = policy?.maxAttempts?.coerceAtLeast(1) ?: 1
        val timeoutMillis = node.timeoutSeconds?.takeIf { it > 0 }?.let { it * 1_000 }
        var attempt = 0
        while (true) {
            attempt++
            val attemptStart = OffsetDateTime.now()
            val failure: Exception = try {
                val result = if (timeoutMillis != null) {
                    withTimeout(timeoutMillis.milliseconds) { node.run(ctx, inputs) }
                } else {
                    node.run(ctx, inputs)
                }
                return NodeOutcome(result, attemptStart)
            } catch (e: TimeoutCancellationException) {
                // A timeout is a node failure, not a run cancellation — surface it as a real error.
                IllegalStateException("Node '${node.name.ifBlank { node.id }}' timed out after ${node.timeoutSeconds}s")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            ctx.nodeSink?.record(
                NodeExecutionEvent(node.id, NodeExecutionStatus.FAILED, attemptStart, OffsetDateTime.now(), error = failure.message ?: failure.toString()),
            )
            if (attempt >= maxAttempts || policy == null) throw failure
            delay(backoffMillis(policy, attempt).milliseconds)
        }
    }

    /** A node's successful run [result] plus the start of the attempt that produced it (for the timeline). */
    private class NodeOutcome(val result: NodeResult, val startedAt: OffsetDateTime)

    /** Backoff before the retry after failed attempt [attempt] (1-based): `min(initial * mult^(n-1), max)`. */
    private fun backoffMillis(policy: RetryPolicy, attempt: Int): Long {
        if (policy.initialDelaySeconds <= 0) return 0
        val seconds = policy.initialDelaySeconds.toDouble() * policy.multiplier.pow(attempt - 1)
        return (minOf(seconds, policy.maxDelaySeconds.toDouble()) * 1_000).toLong()
    }

    /**
     * Collects a node's inbound values, keyed by target port. A null-output source contributes
     * nothing (the port is simply absent), and a routed value (a [PipelineValue.port] set by e.g. a
     * Condition node) only flows along edges whose `sourcePort` matches the taken branch.
     * Single-input nodes ignore the key and read `first`; multi-input nodes read each
     * operator-named [PipelineEdge.targetPort].
     */
    private fun gatherInputs(
        inbound: List<PipelineEdge>,
        outputs: Map<String, PipelineValue?>,
    ): NodeInputs {
        val byPort = LinkedHashMap<String, PipelineValue>()
        for (edge in inbound) {
            val value = outputs[edge.source] ?: continue
            if (value.port != null && edge.sourcePort != null && edge.sourcePort != value.port) continue
            byPort[edge.targetPort ?: edge.source] = value
        }
        return NodeInputs(byPort)
    }

    /**
     * Whether a node is gated off because it is wired EXCLUSIVELY to a routing branch the upstream did
     * not take: some inbound source produced a value on a specific port, yet EVERY edge this node has
     * from that source names a DIFFERENT (non-null) port. The node was wired only to branch(es) that
     * weren't taken, so it must not fire — even if an unrelated, always-on input reached it on another
     * edge (the bug this guards: a multi-input action firing because one of its inputs is always-on).
     *
     * This is deliberately distinct from a source that produced NOTHING (a skipped upstream, or the
     * other arm of a fan-in): that contributes no value via [gatherInputs] but does not gate, so a
     * fan-in node still runs on whichever arm delivered. A source that took ONE of several branches
     * this node is wired to (a branch-merge) is not gated either, because one of its edges matches.
     */
    private fun branchNotTaken(
        inbound: List<PipelineEdge>,
        outputs: Map<String, PipelineValue?>,
    ): Boolean = inbound.groupBy { it.source }.any { (source, edges) ->
        val port = outputs[source]?.port ?: return@any false
        // A null sourcePort accepts any branch, so an edge with one is never a gate.
        edges.none { it.sourcePort == null || it.sourcePort == port }
    }

    /**
     * Whether a node can't run because an input slot was left UNDEFINED by an upstream that was itself
     * SKIPPED — its dependency is gone, so the skip cascades here. This mirrors how a not-taken routing
     * branch already gates a node ([branchNotTaken]): any port wired to a skipped node and left without
     * a value gates, regardless of whether the slot was marked required. A slot that still got a value
     * from another edge is satisfied and does not gate; an unwired slot has no inbound edge here. Only
     * multi-input nodes — a single-input node with no value is already caught by the "no value reached
     * this node" rule.
     */
    private fun dependsOnSkipped(
        descriptor: NodeDescriptor?,
        inbound: List<PipelineEdge>,
        inputs: NodeInputs,
        skipped: Set<String>,
    ): Boolean {
        val slots = descriptor?.inputs ?: return false
        if (slots.size < 2 || skipped.isEmpty()) return false
        return slots.any { slot ->
            val slotEdges = inbound.filter { it.targetPort == slot.name }
            slotEdges.isNotEmpty() && inputs[slot.name] == null && slotEdges.any { it.source in skipped }
        }
    }

    /**
     * Node-type metadata aggregated across every loaded module — the [NodeDescriptor]s (slot
     * constraints + palette data) keyed by `@SerialName`, plus a node-aware [Json]. The global
     * `Json` carried on the context does **not** know the polymorphic node subclasses (the storage
     * layer folds them in only for graph (de)serialization), so reading a node's discriminator needs
     * a [Json] that includes the same module set. Built once from the registry and cached; a benign
     * double-build under contention is harmless (idempotent). Empty when no modules are loaded
     * (e.g. a bare unit test), in which case slot enforcement is simply skipped.
     */
    @OptIn(InternalDI::class)
    private suspend fun nodeMeta(base: Json): NodeMeta {
        cachedMeta?.let { return it }
        val serializers = ProviderRegistry.findAll(PipelineNodeSerializers::class)
            .filter { it.exists }
            .map { it.get() }
        val descriptors = serializers.flatMap { it.descriptors }.associateBy { it.key }
        val json = if (serializers.isEmpty()) base else Json(base) {
            serializersModule = SerializersModule {
                include(base.serializersModule)
                serializers.forEach { include(it.module) }
            }
        }
        return NodeMeta(descriptors, json).also { cachedMeta = it }
    }

    /**
     * This node's type key — its polymorphic `@SerialName` discriminator — read by encoding it with
     * the node-aware [json] (no reflection). Used to look up the node type's declared input slots.
     */
    private fun PipelineNode.typeKey(json: Json): String? =
        ((json.encodeToJsonElement(PipelineNode.serializer(), this) as? JsonObject)?.get("type") as? JsonPrimitive)?.content

    private class NodeMeta(val descriptors: Map<String, NodeDescriptor>, val json: Json)

    @Volatile
    private var cachedMeta: NodeMeta? = null

    companion object {
        /** Hard cap on pipeline-in-pipeline nesting — generous for composition, fatal for runaways. */
        const val MAX_DEPTH = 10
    }
}
