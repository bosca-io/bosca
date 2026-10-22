package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.GetObjectBackupRunData
import bosca.graphql.gen.backup.IObjectBackupRunSummaryFragment
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json

/** Reads a run's recorded outcome and current shared checkpoint summary. */
class BackupObjectsGetCommand : BackupRemoteCommand(name = "get") {
    private val key by option("--key", help = "Exact object run key").required()
        .check("Run key must not be blank") { it.isNotBlank() }

    override fun help(context: Context) = "Read an object run and its current saved checkpoint totals"

    override suspend fun execute(api: BackupApi) {
        val run = api.objectRun(key)
        if (run == null) {
            if (jsonOutput) echo("null")
            throw CliktError("Object run not found: $key")
        }
        if (jsonOutput) {
            echo(Json.encodeToString(GetObjectBackupRunData.Backups.ObjectRuns.Run.serializer(), run))
            return
        }
        echo("Run: ${run.key}")
        echo("Operation: ${run.operation}")
        echo("Status: ${run.status}")
        echo("Source: ${run.sourceId}")
        echo("Destination: ${run.destinationId}")
        echo("Scope: ${run.scope}")
        echo("Attempts: ${run.attempts}")
        echo("Version: ${run.version}")
        echo("Modified: ${run.modified}")
        echo("Error: ${run.error ?: "-"}")
        echo("Inventory: ${run.inventory.id}")
        echo("Manifest: ${run.path}")
        echo("Backup storage: ${run.storage?.backupKey ?: "-"}")
        run.storage?.stores?.forEach { echo("  ${it.store}: ${it.storageKey}") }
        echo("Recorded outcome: ${counts(run.result)}")
        run.result?.let { echo("Recorded verified bytes: ${it.verifiedBytes} / ${it.totalBytes}") }
        echo("Current checkpoints: ${counts(run.summary)}")
        echo("Current verified bytes: ${run.summary.verifiedBytes} / ${run.summary.totalBytes}")
    }

    private fun counts(summary: IObjectBackupRunSummaryFragment?): String = summary?.let {
        "total=${it.total} pending=${it.pending} running=${it.running} completed=${it.completed} failed=${it.failed}"
    } ?: "-"
}
