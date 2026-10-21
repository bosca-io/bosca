package bosca.backup.pipeline

import bosca.backup.ObjectBackupOperation
import bosca.backup.PostgresBackupContentSource
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

/** Captures a database artifact and optional referenced assets per backing job and node. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Back Up PostgreSQL",
    description = "Retain a native database snapshot and optionally copy its referenced content to backup storage.",
    group = "Backups",
    subgroup = "PostgreSQL",
    outputs = [OutputSlot(name = "out", kind = SlotKind.OBJECT, type = PostgresBackupReceipt::class, typeLabel = "Database backup receipt")],
    settings = [
        SettingSlot(name = "sourceId", control = SettingControl.TEXT, label = "Source identity", required = true),
        SettingSlot(name = "storageKey", control = SettingControl.TEXT, label = "Backup storage ID", required = true),
        SettingSlot(name = "prefix", control = SettingControl.TEXT, label = "Backup prefix", default = "postgresql"),
        SettingSlot(
            name = "includeContentReferences", control = SettingControl.BOOLEAN,
            label = "Include content references", default = "false",
            description = "Included automatically when a content source is set.",
        ),
        SettingSlot(
            name = "contentSource", control = SettingControl.CODE, language = "json", label = "Content source",
            description = "Optional JSON with store, storageKey and scope to copy referenced assets. Leave empty for database-only capture.",
        ),
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
    val includeContentReferences: Boolean = false,
    val contentSource: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend = true
    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        provide<GroupEvaluator>().verifyHasSaGroup(context.authentication)
        val source = contentSource.takeIf { it.isNotBlank() }?.let {
            context.json.decodeFromString(PostgresBackupContentSource.serializer(), it)
        }
        val queuedInvocation = jobOrNull()?.getId()
        val invocation = queuedInvocation ?: UUID.random()
        val key = objectBackupIdentity(ObjectBackupOperation.BACKUP, listOf("postgresql", invocation.toString(), id))
        val service = provide<PostgresBackupRunService>()
        val checkpoint = key.takeIf { queuedInvocation != null }
        val receipt = if (source == null) {
            service.backupConfigured(key, sourceId, storageKey, prefix, checkpoint, includeContentReferences)
        } else {
            service.backupContentConfigured(key, sourceId, source, storageKey, prefix, checkpoint)
        }
        return PostgresBackupNodeSerializer.serialize(receipt)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "postgresBackup.backup")
            put("sourceId", sourceId)
            put("storageKey", storageKey)
            put("prefix", prefix)
            put("includeContentReferences", includeContentReferences)
            put("contentSource", contentSource)
        })
        return null
    }
}
