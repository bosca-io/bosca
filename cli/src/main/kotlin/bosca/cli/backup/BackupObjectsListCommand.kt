package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.ListObjectBackupRunsData
import bosca.graphql.gen.backup.ObjectBackupOperation
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Reads one exact object inventory operation and scope. */
class BackupObjectsListCommand : BackupRemoteCommand(name = "list") {
    private val operation by option("--operation", help = "Object inventory operation")
        .choice(ObjectBackupOperation.entries.associateBy { it.name }, ignoreCase = true).required()
    private val source by option("--source", help = "Exact source identity").required()
        .check("Source identity must not be blank") { it.isNotBlank() }
    private val destination by option("--destination", help = "Exact destination identity").required()
        .check("Destination identity must not be blank") { it.isNotBlank() }
    private val scope by option("--scope", help = "Exact backup scope").required()
        .check("Scope must not be blank") { it.isNotBlank() }
    private val offset by option("--offset", help = "Zero-based page offset").int().default(0)
        .check("Offset must not be negative") { it >= 0 }
    private val limit by option("--limit", help = "Page size, from 1 to 100").int().default(20)
        .check("Limit must be between 1 and 100") { it in 1..100 }

    override fun help(context: Context) = "List object inventory runs with offset pagination"

    override suspend fun execute(api: BackupApi) {
        val runs = api.objectRuns(operation, source, destination, scope, offset, limit)
        if (jsonOutput) {
            echo(Json.encodeToString(ListSerializer(ListObjectBackupRunsData.Backups.ObjectRuns.Runs.serializer()), runs))
        } else if (runs.isEmpty()) {
            echo("No object backup or restore runs found.")
        } else {
            echo("RUN\tOPERATION\tSTATUS\tATTEMPTS\tMANIFEST")
            for (run in runs) {
                echo("${run.key}\t${run.operation}\t${run.status}\t${run.attempts}\t${run.path}")
            }
        }
    }
}
