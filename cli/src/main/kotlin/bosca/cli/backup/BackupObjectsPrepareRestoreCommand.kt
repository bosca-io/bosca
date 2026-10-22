package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.ObjectBackupStoreMappingInput
import bosca.graphql.gen.backup.PrepareConfiguredObjectRestoreData
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json

/** Prepares retained object inventory and fixed targets without dispatching a transfer. */
class BackupObjectsPrepareRestoreCommand : BackupRemoteCommand(name = "prepare-restore") {
    private val requestKey by option("--request", help = "Stable request key; reuse only for the same restore intent").required()
        .check("Request key must not be blank") { it.isNotBlank() }
    private val destination by option("--destination", help = "Restore destination identity").required()
        .check("Destination identity must not be blank") { it.isNotBlank() }
    private val backup by option("--backup", help = "Retained storage ID in the destination's backup.objectStores catalog").required()
        .check("Backup storage ID must not be blank") { it.isNotBlank() }
    private val manifest by option("--manifest", help = "Relative retained object manifest path").required()
        .check("Manifest path must not be blank") { it.isNotBlank() }
    private val stores by option("--store", help = "Logical store=target catalog ID; repeat for each manifest store")
        .convert { value ->
            val parts = value.split('=', limit = 2)
            require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Store mapping must be logical-store=target-storage-ID" }
            ObjectBackupStoreMappingInput(parts[0], parts[1])
        }.multiple()
        .check("Duplicate logical object store mapping") { values -> values.map { it.store }.toSet().size == values.size }

    override fun help(context: Context) = "Prepare a retained object restore without copying assets; dispatch it separately"

    override suspend fun execute(api: BackupApi) {
        val run = api.prepareObjectRestore(requestKey, destination, backup, manifest, stores)
        if (jsonOutput) {
            echo(Json.encodeToString(PrepareConfiguredObjectRestoreData.Backups.ObjectRuns.PrepareRestoreConfigured.serializer(), run))
        } else {
            echo("Run: ${run.key}")
            echo("Status: ${run.status}")
            echo("Version: ${run.version}")
            echo("Manifest: ${run.path}")
            echo("Dispatch separately using a pending run's returned version.")
        }
    }
}
