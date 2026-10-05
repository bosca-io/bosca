package bosca.segmentation.pipeline

import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.ReferenceSource
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.service.PipelineService
import bosca.segmentation.jobs.RunPipelineForSegmentJob
import bosca.segmentation.jobs.enqueue
import bosca.segmentation.service.SegmentService
import bosca.serialization.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Starts one durable run of [pipelineId] for each profile in [segmentId].
 *
 * The actual audience traversal is delegated to [RunPipelineForSegmentJob], which pages membership.
 * Each body run receives `{ segmentId, profileId, context }`, where `context` is this node's optional
 * inbound value encoded to JSON. The requesting principal is captured in the job so every child run
 * executes under the same identity.
 *
 * This is a fire-and-forget action: reaching the node validates the references, enqueues the fan-out,
 * and produces no output.
 */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Run Pipeline for Segment",
    description = "Starts the selected pipeline once per segment profile and forwards optional context.",
    group = "Segmentation",
    subgroup = "Flow",
    inputs = [
        InputSlot(
            name = "in",
            typeLabel = "Context",
            description = "Optional additional context copied into every per-profile body-pipeline input.",
            required = false,
        ),
    ],
    settings = [
        SettingSlot(
            name = "segmentId",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.SEGMENT,
            label = "Segment",
            required = true,
            placeholder = "Pick a segment…",
            description = "Current members are read in stable pages and processed one profile per run.",
        ),
        SettingSlot(
            name = "pipelineId",
            control = SettingControl.REFERENCE,
            reference = ReferenceSource.PIPELINE,
            label = "Pipeline",
            required = true,
            placeholder = "Pick the pipeline to run per profile…",
            description = "Receives a JSON object containing segmentId, profileId, and optional context.",
        ),
    ],
)
@Serializable
@SerialName("segmentation.runPipelineForSegment")
class RunPipelineForSegmentNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    /** Segment whose current audience is traversed by the fan-out job. */
    val segmentId: UUID? = null,
    /** Body pipeline started once per segment profile. */
    val pipelineId: UUID? = null,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {

    private fun additionalContext(context: PipelineContext, inputs: NodeInputs) =
        inputs.first?.encode(context.json)

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "runPipelineForSegment")
            put("segmentId", segmentId?.toString().orEmpty())
            put("pipelineId", pipelineId?.toString().orEmpty())
            put("context", additionalContext(context, inputs) ?: JsonNull)
        })
        return null
    }

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val label = name.ifBlank { id }
        val principalId = context.authentication.principal()?.id
            ?: error("Run Pipeline for Segment node '$label' requires an authenticated principal")
        val segmentId = segmentId
            ?: error("Run Pipeline for Segment node '$label' has no segment selected")
        val pipelineId = pipelineId
            ?: error("Run Pipeline for Segment node '$label' has no pipeline selected")
        provide<SegmentService>().getById(segmentId)
            ?: error("Run Pipeline for Segment node '$label' segment not found: $segmentId")
        provide<PipelineService>().get(pipelineId)
            ?: error("Run Pipeline for Segment node '$label' pipeline not found: $pipelineId")

        RunPipelineForSegmentJob(
            segmentId = segmentId,
            pipelineId = pipelineId,
            principalId = principalId,
            context = additionalContext(context, inputs),
        ).enqueue()
        return null
    }
}
