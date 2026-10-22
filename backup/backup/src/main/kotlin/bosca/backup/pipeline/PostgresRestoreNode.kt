package bosca.backup.pipeline

import bosca.backup.PostgresBackupManifest
import bosca.backup.PostgresBackupService
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Restores a retained native database artifact through the configured recovery service. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Restore PostgreSQL",
    description = "Restore a retained native artifact into an empty database. Requires a separate recovery journal.",
    group = "Backups",
    subgroup = "PostgreSQL",
    inputs = [InputSlot(
        name = "path", kind = SlotKind.STRING, typeLabel = "Native backup directory",
        description = "Relative retained database directory from the backup receipt's path.",
    )],
    outputs = [OutputSlot(
        name = "out", kind = SlotKind.OBJECT, type = PostgresBackupManifest::class,
        typeLabel = "Restored database manifest", description = "Emitted only after database restoration returns successfully. Object assets are restored separately.",
    )],
    settings = [
        SettingSlot(name = "backupKey", control = SettingControl.TEXT, label = "Backup storage ID", required = true),
    ],
)
@Serializable
@SerialName("postgresBackup.restore")
class PostgresRestoreNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val backupKey: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        provide<GroupEvaluator>().verifyHasSaGroup(context.authentication)
        val input = PostgresRestoreNodeSerializer.deserialize(context, inputs)
        require(input.path.isNotBlank()) { "Native backup path is required" }
        val restored = provide<PostgresBackupService>().restoreConfigured(input.path, backupKey)
        return PostgresRestoreNodeSerializer.serialize(restored)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = PostgresRestoreNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "postgresBackup.restore")
            input.path?.let { put("path", it) }
            put("backupKey", backupKey)
        })
        return null
    }
}
