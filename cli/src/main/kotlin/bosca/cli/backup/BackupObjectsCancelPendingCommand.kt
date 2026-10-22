package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.CancelPendingObjectBackupRunData
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.serialization.json.Json

/** Cancels only the explicitly observed pending version, without stopping active writers. */
class BackupObjectsCancelPendingCommand : BackupRemoteCommand(name = "cancel-pending") {
    private val key by option("--key", help = "Exact object run key").required()
        .check("Run key must not be blank") { it.isNotBlank() }
    private val expectedVersion by option("--expected-version", help = "Observed pending run version").long().required()
        .check("Expected version must not be negative") { it >= 0 }
    private val reason by option("--reason", help = "Reason recorded on the cancelled run").required()
        .check("Cancellation reason must not be blank") { it.isNotBlank() }

    override fun help(context: Context) = "Cancel an observed pending run version; active writers are not stopped"

    override suspend fun execute(api: BackupApi) {
        val run = api.cancelPendingObjectRun(key, expectedVersion, reason)
        if (jsonOutput) {
            echo(Json.encodeToString(CancelPendingObjectBackupRunData.Backups.ObjectRuns.CancelPending.serializer(), run))
        } else {
            echo("Run: ${run.key}")
            echo("Status: ${run.status}")
            echo("Version: ${run.version}")
            echo("Reason: ${run.error ?: "-"}")
        }
    }
}
