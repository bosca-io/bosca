package bosca.backup.pipeline

import bosca.backup.ObjectBackupOperation
import bosca.backup.PostgresBackupReceipt
import bosca.backup.PostgresBackupRunService
import bosca.backup.objectBackupIdentity
import bosca.di.provide
import bosca.pipelines.PipelineContext
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Captures and independently retains one database artifact per backing job and node. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Back Up PostgreSQL",
    description = "Capture a native database snapshot and retain its verified artifact in configured backup storage.",
    group = "Backups",
    subgroup = "PostgreSQL",
    outputs = [OutputSlot(name = "out", kind = SlotKind.OBJECT, type = PostgresBackupReceipt::class, typeLabel = "Database backup receipt")],
    settings = [
        SettingSlot(name = "sourceId", control = SettingControl.TEXT, label = "Source identity", required = true),
        SettingSlot(name = "storageKey", control = SettingControl.TEXT, label = "Backup storage ID", required = true),
        SettingSlot(name = "prefix", control = SettingControl.TEXT, label = "Backup prefix", default = "postgresql"),
    ],
)
@Serializable
@SerialName("postgresBackup.backup")
class PostgresBackupNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val sourceId: String = "",
    val storageKey: String = "",
    val prefix: String = "postgresql",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend = true
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        provide<GroupEvaluator>().verifyHasSaGroup(context.authentication)
        val queuedInvocation = jobOrNull()?.getId()
        val invocation = queuedInvocation ?: UUID.random()
        val key = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf("postgresql", invocation.toString(), id))
        val receipt = provide<PostgresBackupRunService>().backupConfigured(
            key, sourceId, storageKey, prefix, key.takeIf { queuedInvocation != null },
        )
        return PostgresBackupNodeSerializer.serialize(receipt)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "postgresBackup.backup")
            put("sourceId", sourceId)
            put("storageKey", storageKey)
            put("prefix", prefix)
        })
        return null
    }
}
