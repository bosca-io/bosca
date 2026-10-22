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

/** Restores retained database and content components through the configured recovery service. */
@PipelineNodeType(
    category = NodeCategory.ACTION,
    label = "Restore PostgreSQL and Content",
    description = "Restore a retained content report into isolated object storage and an empty database. Requires a separate recovery journal.",
    group = "Backups",
    subgroup = "PostgreSQL",
    inputs = [InputSlot(
        name = "reportPath", kind = SlotKind.STRING, typeLabel = "Content report directory",
        description = "Relative retained report directory from the backup receipt's contentReportPath.",
    )],
    outputs = [OutputSlot(
        name = "out", kind = SlotKind.OBJECT, type = PostgresBackupManifest::class,
        typeLabel = "Restored database manifest", description = "Emitted only after database and declared content restoration returns successfully.",
    )],
    settings = [
        SettingSlot(name = "backupKey", control = SettingControl.TEXT, label = "Backup storage ID", required = true),
        SettingSlot(
            name = "destinationKey", control = SettingControl.TEXT, label = "Target content storage ID", required = true,
            description = "Uses backup.postgres.restore for the empty database target. Keep the recovery journal outside that database.",
        ),
    ],
)
@Serializable
@SerialName("postgresBackup.restoreContent")
class PostgresContentRestoreNode(
    override val id: String,
    override val name: String = "",
    override val description: String = "",
    val backupKey: String = "",
    val destinationKey: String = "",
    override val position: NodePosition = NodePosition(),
) : ActionNode() {
    override val willSuspend: Boolean = true

    override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue {
        provide<GroupEvaluator>().verifyHasSaGroup(context.authentication)
        val input = PostgresContentRestoreNodeSerializer.deserialize(context, inputs)
        require(input.reportPath.isNotBlank()) { "Content report path is required" }
        val restored = provide<PostgresBackupService>().restoreContentConfigured(input.reportPath, backupKey, destinationKey)
        return PostgresContentRestoreNodeSerializer.serialize(restored)
    }

    override suspend fun dryRun(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
        val input = PostgresContentRestoreNodeSerializer.deserializePartial(context, inputs)
        context.trace?.recordAction(id, buildJsonObject {
            put("action", "postgresBackup.restoreContent")
            input.reportPath?.let { put("reportPath", it) }
            put("backupKey", backupKey)
            put("destinationKey", destinationKey)
        })
        return null
    }
}
