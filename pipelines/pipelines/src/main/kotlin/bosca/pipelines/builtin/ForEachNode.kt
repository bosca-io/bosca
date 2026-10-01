package bosca.pipelines.builtin

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineRunService
import bosca.pipelines.service.PipelineService
import bosca.pipelines.service.requireCompleted
import bosca.serialization.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Iteration: runs a body pipeline ([pipelineId]) **once per item** of the inbound
 * collection and emits the per-item outputs as an ordered JSON array — `map` over a collection, where
 * each element is mapped by a composed pipeline (ForEach = RunPipeline fanned across a collection).
 *
 * The items come from the inbound value: the value itself when [itemsField] is blank, else the array
 * at that dot-path within it (e.g. `order.lineItems`). An empty/absent collection yields `[]` and runs
 * nothing.
 *
 * Concurrency is bounded by [maxConcurrency] (default 1 = sequential); results are returned in **input
 * order** regardless of completion order. [continueOnError] chooses the failure policy: fail-fast (one
 * bad item fails the run, the default) or collect-and-continue (a failed item contributes an
 * `{ error, index }` marker so the failure is attributable to its position).
 *
 * Each item runs the body inline under an **isolated** child context (fresh scratch attributes, no
 * shared timeline), exactly like [RunPipelineNode] — so, as with RunPipeline today, a body that would
 * durably *suspend* (a timer) degrades to synchronous within the item. True per-item
 * suspend/resume is the durable-iteration extension.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "For Each",
    description = "Runs a pipeline once per item of the inbound collection and outputs the per-item results as an array.",
    group = "Core",
    subgroup = "Flow",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Collection",
            description = "A JSON array to iterate — or, when 'items field' is set, an object containing that array at the dot-path.",
        ),
    ],
    outputs = [
        // Always an array (one entry per item, in input order) — declared so a downstream typed ARRAY
        // slot (e.g. Flatten) accepts the wire; the element type depends on the body pipeline.
        OutputSlot(
            name = "out", kind = SlotKind.ARRAY, typeLabel = "Results",
            description = "The per-item results, in input order.",
        ),
    ],
    settings = [
        SettingSlot(
            name = "pipelineId", control = SettingControl.REFERENCE, reference = ReferenceSource.PIPELINE,
            label = "Body pipeline", placeholder = "Pick the pipeline to run per item…",
            description = "Runs once per item of the inbound collection (each item is the body's input) and outputs the per-item results as an array, in input order.",
        ),
        SettingSlot(
            name = "itemsField", control = SettingControl.TEXT, label = "Items field (dot-path)", mono = true,
            placeholder = "blank = the inbound value is the array; e.g. order.lines",
        ),
        SettingSlot(
            name = "maxConcurrency", control = SettingControl.INTEGER, label = "Max concurrency",
            default = "1", placeholder = "1 (sequential)",
        ),
        SettingSlot(
            name = "continueOnError", control = SettingControl.BOOLEAN, label = "Continue on item error", default = "false",
            description = "Off = fail-fast; on = a failed item becomes an {error, index} marker in its place.",
        ),
    ],
)
@Serializable
@SerialName("forEach")
class ForEach(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** The body pipeline run once per item; its Output value becomes that item's result. */
    val pipelineId: UUID? = null,
    /** Dot-path to the array within the inbound value; blank = the inbound value itself is the array. */
    val itemsField: String = "",
    /** Max items run concurrently (>=1); 1 = sequential. */
    val maxConcurrency: Int = 1,
    /** When true, a failed item yields an `{error, index}` marker instead of failing the whole run. */
    val continueOnError: Boolean = false,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    /**
     * Durable run (a real `runId`, not dry) → fan each item out into a **child durable run** of the
     * body so per-item suspend/resume survives: park the parent on one
     * await and let [PipelineRunService.startIteration] start the children; the run resumes with the
     * joined array once every child finishes. Dry and non-durable runs take the synchronous in-line
     * path in [execute] (an inline body can't suspend, exactly like Run Pipeline).
     */
    override suspend fun run(context: PipelineContext, inputs: NodeInputs): NodeResult {
        val runId = context.runId
        if (context.dryRun || runId == null) return NodeResult.Output(execute(context, inputs))

        val input = inputs.first ?: error("For Each node '${name.ifBlank { id }}' requires an input")
        val pid = pipelineId ?: error("For Each node '${name.ifBlank { id }}' has no body pipeline selected")
        val items = resolveItems(input, context)
        if (items.isEmpty()) return NodeResult.Output(PipelineValue.ofJson(JsonArray(emptyList())))

        val continueOnError = continueOnError
        val runJob = context.runJob
        val runJobId = context.runJobId
        return NodeResult.Suspend {
            provide<PipelineRunService>().startIteration(
                runId, id, pid, items, continueOnError, context.inputCreated, runJob, runJobId,
                maxConcurrency = maxConcurrency.coerceAtLeast(1),
            )
        }
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        val input = inputs.first ?: error("For Each node '${name.ifBlank { id }}' requires an input")
        val pid = pipelineId ?: error("For Each node '${name.ifBlank { id }}' has no body pipeline selected")
        val pipeline = provide<PipelineService>().get(pid)
            ?: error("For Each node '${name.ifBlank { id }}': body pipeline not found: $pid")

        val items = resolveItems(input, context)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "forEach")
            put("pipelineName", pipeline.name)
            put("items", items.size)
            put("maxConcurrency", maxConcurrency.coerceAtLeast(1))
        })
        if (items.isEmpty()) return PipelineValue.ofJson(JsonArray(emptyList()))

        val executor = provide<PipelineExecutor>()
        val gate = Semaphore(maxConcurrency.coerceAtLeast(1))
        // mapIndexed + awaitAll preserves input order regardless of completion order.
        val results = coroutineScope {
            items.mapIndexed { index, item ->
                async { gate.withPermit { runItem(executor, pipeline, item, context, index) } }
            }.awaitAll()
        }
        return PipelineValue.ofJson(JsonArray(results))
    }

    /** Run the body for one item; return its output JSON, or (continueOnError) an error marker. */
    private suspend fun runItem(
        executor: PipelineExecutor,
        pipeline: bosca.pipelines.model.Pipeline,
        item: JsonElement,
        context: PipelineContext,
        index: Int,
    ): JsonElement {
        // Isolated child context per item (fresh attributes; no shared timeline) — like RunPipeline.
        // No runId: the body runs inline and cannot suspend the parent (is the durable variant).
        val childContext = PipelineContext(
            context.authentication,
            context.json,
            context.dryRun,
            inputCreated = context.inputCreated,
            invocationStack = context.invocationStack,
        )
        return try {
            executor.execute(pipeline, PipelineValue.ofJson(item), childContext)
                .requireCompleted()?.encode(context.json) ?: JsonNull
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!continueOnError) {
                throw IllegalStateException("For Each node '${name.ifBlank { id }}' failed on item $index: ${e.message}", e)
            }
            buildJsonObject {
                put("error", e.message ?: e.toString())
                put("index", index)
            }
        }
    }

    /** The inbound array: the value itself ([itemsField] blank) or the array at that dot-path; absent → empty. */
    private fun resolveItems(input: PipelineValue, context: PipelineContext): List<JsonElement> {
        val json = input.encode(context.json)
        val resolved = if (itemsField.isBlank()) json else navigate(json, itemsField)
        return when {
            resolved is JsonArray -> resolved.toList()
            resolved == null || resolved is JsonNull -> emptyList()
            else -> error("For Each node '${name.ifBlank { id }}': '${itemsField.ifBlank { "input" }}' is not an array")
        }
    }

    private fun navigate(element: JsonElement, path: String): JsonElement? {
        var current: JsonElement? = element
        for (key in path.split('.')) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current
    }
}
