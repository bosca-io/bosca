package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.RetryObjectBackupRunData
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.serialization.json.Json

/** Prepares another attempt of an explicitly observed failed, partial or cancelled object run. */
class BackupObjectsRetryCommand : BackupRemoteCommand(name = "retry") {
    private val key by option("--key", help = "Exact object run key").required()
        .check("Run key must not be blank") { it.isNotBlank() }
    private val expectedVersion by option("--expected-version", help = "Observed run version").long().required()
        .check("Expected version must not be negative") { it >= 0 }

    override fun help(context: Context) = "Return an observed retryable run version to pending; dispatch it separately"

    override suspend fun execute(api: BackupApi) {
        val run = api.retryObjectRun(key, expectedVersion)
        if (jsonOutput) {
            echo(Json.encodeToString(RetryObjectBackupRunData.Backups.ObjectRuns.Retry.serializer(), run))
        } else {
            echo("Run: ${run.key}")
            echo("Status: ${run.status}")
            echo("Version: ${run.version}")
            echo("Dispatch separately using the returned pending version.")
        }
    }
}
