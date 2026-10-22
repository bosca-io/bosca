package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.DispatchObjectBackupRunData
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.long
import kotlinx.serialization.json.Json

/** Requests asynchronous publication of one observed, configured pending run version. */
class BackupObjectsDispatchCommand : BackupRemoteCommand(name = "dispatch") {
    private val key by option("--key", help = "Exact object run key").required()
        .check("Run key must not be blank") { it.isNotBlank() }
    private val expectedVersion by option("--expected-version", help = "Observed pending run version").long().required()
        .check("Expected version must not be negative") { it >= 0 }

    override fun help(context: Context) = "Request asynchronous execution of an observed pending run version"

    override suspend fun execute(api: BackupApi) {
        val dispatch = api.dispatchObjectRun(key, expectedVersion)
        if (jsonOutput) {
            echo(Json.encodeToString(DispatchObjectBackupRunData.Backups.ObjectRuns.Dispatch.serializer(), dispatch))
        } else {
            echo("Dispatch request: ${dispatch.key}")
            echo("Run: ${dispatch.runKey}")
            echo("Version: ${dispatch.version}")
            echo("Execution is asynchronous; inspect run and item status for progress.")
        }
    }
}
