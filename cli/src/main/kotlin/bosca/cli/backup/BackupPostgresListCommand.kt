package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.ListPostgresBackupRunsData
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Lists one exact native backup source/storage scope. */
class BackupPostgresListCommand : BackupRemoteCommand(name = "list") {
    private val source by option("--source", help = "Exact source instance identity").required()
        .check("Source identity must not be blank") { it.isNotBlank() }
    private val storage by option("--storage", help = "Exact backup storage ID").required()
        .check("Backup storage identity must not be blank") { it.isNotBlank() }
    private val offset by option("--offset", help = "Zero-based page offset").int().default(0)
        .check("Offset must not be negative") { it >= 0 }
    private val limit by option("--limit", help = "Page size, from 1 to 100").int().default(20)
        .check("Limit must be between 1 and 100") { it in 1..100 }

    override fun help(context: Context) = "List native backup requests with offset pagination"

    override suspend fun execute(api: BackupApi) {
        val runs = api.postgresRuns(source, storage, offset, limit)
        if (jsonOutput) {
            echo(Json.encodeToString(ListSerializer(ListPostgresBackupRunsData.Backups.PostgresRuns.serializer()), runs))
        } else if (runs.isEmpty()) {
            echo("No native backup requests found.")
        } else {
            echo("REQUEST\tSTATUS\tATTEMPTS\tMODIFIED\tCONTENT REPORT")
            for (run in runs) {
                echo("${run.key}\t${run.status}\t${run.attempts}\t${run.modified}\t${run.result?.contentReportPath ?: "-"}")
            }
        }
    }
}
