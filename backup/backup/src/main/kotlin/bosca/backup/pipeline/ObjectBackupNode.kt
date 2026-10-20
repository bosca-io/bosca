package bosca.backup.pipeline

import bosca.backup.ObjectBackupCaptureService
import bosca.backup.ObjectBackupDiscoveryRequest
import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupSource
import bosca.backup.objectBackupIdentity
import bosca.di.provide
import bosca.pipelines.PipelineContext
import bosca.pipelines.annotation.InputSlot
import bosca.pipelines.annotation.NodeCategory
import bosca.pipelines.annotation.OutputSlot
import bosca.pipelines.annotation.PipelineNodeType
import bosca.pipelines.annotation.SettingControl
import bosca.pipelines.annotation.SettingSlot
import bosca.pipelines.annotation.SlotKind
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.PipelineValue
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.jobOrNull
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Discovers and captures one incremental object inventory per Pipeline run and node. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Back Up Object Storage",
    description = "Copy new and changed objects from configured source prefixes to backup storage.",
    group = "Backups",
    subgroup = "Objects",
    inputs = [
        InputSlot(
            name = "verifiedSince", kind = SlotKind.STRING, required = false, typeLabel = "Verification cutoff",
            description = "ISO offset timestamp overriding the checksum recheck interval. Omit to use the setting.",
        ),
    ],
    outputs = [
        OutputSlot(name = "out", kind = SlotKind.OBJECT, type = ObjectBackupManifest::class, typeLabel = "Object backup manifest"),
    ],
    settings = [
        SettingSlot(name = "sourceId", control = SettingControl.TEXT, label = "Source identity", required = true),
        SettingSlot(name = "scope", control = SettingControl.TEXT, label = "Backup scope", required = true),
        SettingSlot(name = "destinationId", control = SettingControl.TEXT, label = "Backup storage ID", required = true),
        SettingSlot(
            name = "sources", control = SettingControl.CODE, language = "json", label = "Source stores", required = true,
            description = "Map each logical store to its configured storageKey and optional literal prefix.",
        ),
        SettingSlot(name = "payloadPrefix", control = SettingControl.TEXT, label = "Payload prefix", default = "payloads"),
        SettingSlot(name = "manifestPrefix", control = SettingControl.TEXT, label = "Manifest prefix", default = "manifests"),
        SettingSlot(
            name = "verificationMaxAgeDays", control = SettingControl.INTEGER, label = "Checksum recheck interval (days)",
            description = "Read retained bytes again after this many days. Omit to use metadata checks until an explicit integrity check. The cutoff input overrides this setting.",
        ),
    ],
)
@Serializable
@SerialName("objectBackup.backup")
class ObjectBackupNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val sourceId: String = "",
    val scope: String = "",
    val destinationId: String = "",
    val sources: String = "{}",
    val payloadPrefix: String = "payloads",
    val manifestPrefix: String = "manifests",
    val verificationMaxAgeDays: Int? = null,
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        provide<GroupEvaluator>().verifyHasSaGroup(context.authentication)
        val input = ObjectBackupNodeSerializer.deserialize(context, inputs)
        require(verificationMaxAgeDays == null || verificationMaxAgeDays > 0) {
            "Checksum recheck interval must be positive"
        }
        val verifiedSince = input.verifiedSince?.let(OffsetDateTime::parse)
            ?: verificationMaxAgeDays?.let { context.inputCreated.minusDays(it.toLong()) }
            ?: OffsetDateTime.MIN
        val stores = context.json.decodeFromString(MapSerializer(String.serializer(), ObjectBackupSource.serializer()), sources)
        // Backing jobs execute with a non-suspending context; their durable job ID survives retries.
        val invocation = jobOrNull()?.getId() ?: UUID.random()
        val selectionId = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf(invocation.toString(), id))
        val service = provide<ObjectBackupCaptureService>()
        val plan = service.discoverConfigured(ObjectBackupDiscoveryRequest(
            selectionId, sourceId, scope, destinationId, stores, payloadPrefix, "$manifestPrefix/$selectionId.json",
        ))
        return ObjectBackupNodeSerializer.serialize(service.captureConfigured(plan.key, verifiedSince))
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = ObjectBackupNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "objectBackup.backup")
            put("sourceId", sourceId)
            put("scope", scope)
            put("destinationId", destinationId)
            put("sources", sources)
            put("payloadPrefix", payloadPrefix)
            put("manifestPrefix", manifestPrefix)
            put("verificationMaxAgeDays", verificationMaxAgeDays)
            put("verifiedSince", input.verifiedSince)
        })
        return null
    }
}
