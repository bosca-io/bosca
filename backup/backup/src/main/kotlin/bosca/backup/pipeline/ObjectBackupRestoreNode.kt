package bosca.backup.pipeline

import bosca.backup.ObjectBackupManifest
import bosca.backup.ObjectBackupOperation
import bosca.backup.ObjectBackupRunService
import bosca.backup.ObjectBackupRunStatus
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Restores a retained object manifest through the existing configured run service. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Restore Object Backup",
    description = "Restore verified retained objects into configured target stores without contacting the source.",
    group = "Backups",
    subgroup = "Objects",
    inputs = [
        InputSlot(name = "manifestPath", kind = SlotKind.STRING, typeLabel = "Manifest path",
            description = "Relative path of the saved object manifest in backup storage."),
    ],
    outputs = [
        OutputSlot(name = "out", kind = SlotKind.OBJECT, type = ObjectBackupManifest::class,
            typeLabel = "Restored object manifest", description = "Emitted only after the restore run completes."),
    ],
    settings = [
        SettingSlot(name = "backupKey", control = SettingControl.TEXT, label = "Backup storage ID", required = true),
        SettingSlot(name = "destinationId", control = SettingControl.TEXT, label = "Restore destination identity", required = true),
        SettingSlot(name = "targets", control = SettingControl.CODE, language = "json", label = "Target stores", required = true,
            description = "Map each logical store in the manifest to a configured target storage ID."),
    ],
)
@Serializable
@SerialName("objectBackup.restore")
class ObjectBackupRestoreNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val backupKey: String = "",
    val destinationId: String = "",
    val targets: String = "{}",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        provide<GroupEvaluator>().verifyHasSaGroup(context.authentication)
        val input = ObjectBackupRestoreNodeSerializer.deserialize(context, inputs)
        require(input.manifestPath.isNotBlank()) { "Restore manifest path is required" }
        val stores = context.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), targets)
        val queuedInvocation = jobOrNull()?.getId()
        val invocation = queuedInvocation ?: UUID.random()
        val requestKey = objectBackupIdentity(ObjectBackupOperation.RESTORE, listOf(invocation.toString(), id))
        val service = provide<ObjectBackupRunService>()
        val prepared = service.prepareRestoreConfigured(requestKey, destinationId, backupKey, input.manifestPath, stores)
        check(prepared.status != ObjectBackupRunStatus.CANCELLED) { "Object restore was cancelled: ${prepared.key}" }
        val result = deferBusyBackupDestination {
            service.executeConfigured(prepared.key, requestKey.takeIf { queuedInvocation != null })
        }
        check(result.status == ObjectBackupRunStatus.COMPLETED) {
            "Object restore did not complete: ${result.key} (${result.status})"
        }
        return ObjectBackupRestoreNodeSerializer.serialize(result.inventory)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = ObjectBackupRestoreNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "objectBackup.restore")
            put("manifestPath", input.manifestPath)
            put("backupKey", backupKey)
            put("destinationId", destinationId)
            put("targets", targets)
        })
        return null
    }
}
