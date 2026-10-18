package bosca.backup.pipeline

import bosca.backup.ObjectBackupCaptureService
import bosca.backup.ObjectBackupManifest
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.service.GroupEvaluator
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Executes an existing configured capture plan through the Pipeline's durable action path. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Capture Object Backup",
    description = "Copy a saved object selection to its configured backup storage and publish its manifest.",
    group = "Backups",
    subgroup = "Objects",
    inputs = [
        InputSlot(
            name = "planKey", kind = SlotKind.STRING, typeLabel = "Capture plan key",
            description = "The saved, configured capture plan to execute.",
        ),
        InputSlot(
            name = "verifiedSince", kind = SlotKind.STRING, typeLabel = "Verification cutoff", required = false,
            description = "ISO offset timestamp for reusing verified retained versions. Omit to verify payloads in full.",
        ),
    ],
    outputs = [
        OutputSlot(
            name = "out", kind = SlotKind.OBJECT, type = ObjectBackupManifest::class,
            typeLabel = "Object backup manifest", description = "The verified, published object inventory.",
        ),
    ],
)
@Serializable
@SerialName("objectBackup.capture")
class ObjectBackupCaptureNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        provide<GroupEvaluator>().verifyHasSaGroup(context.authentication)
        val input = ObjectBackupCaptureNodeSerializer.deserialize(context, inputs)
        require(input.planKey.isNotBlank()) { "Capture plan key is required" }
        val verifiedSince = input.verifiedSince?.let(OffsetDateTime::parse)
        val manifest = provide<ObjectBackupCaptureService>().captureConfigured(input.planKey, verifiedSince)
        return ObjectBackupCaptureNodeSerializer.serialize(manifest)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = ObjectBackupCaptureNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "objectBackup.capture")
            put("planKey", input.planKey)
            put("verifiedSince", input.verifiedSince)
        })
        return null
    }
}
