package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.GetPostgresBackupRunData
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json

/** Reads one native backup request and its optional retained receipt. */
class BackupPostgresGetCommand : BackupRemoteCommand(name = "get") {
    private val key by option("--key", help = "Exact native backup request key").required()
        .check("Request key must not be blank") { it.isNotBlank() }

    override fun help(context: Context) = "Read a native backup request by key"

    override suspend fun execute(api: BackupApi) {
        val run = api.postgresRun(key)
        if (run == null) {
            if (jsonOutput) echo("null")
            throw CliktError("Native backup request not found: $key")
        }
        if (jsonOutput) {
            echo(Json.encodeToString(GetPostgresBackupRunData.Backups.PostgresRun.serializer(), run))
            return
        }
        echo("Request: ${run.key}")
        echo("Source: ${run.sourceId}")
        echo("Backup storage: ${run.storageKey}")
        echo("Prefix: ${run.prefix}")
        echo("Status: ${run.status}")
        echo("Attempts: ${run.attempts}")
        echo("Writer token: ${run.writerToken ?: "-"}")
        echo("Version: ${run.version}")
        echo("Created: ${run.created}")
        echo("Modified: ${run.modified}")
        echo("Error: ${run.error ?: "-"}")
        echo("Reference inventory requested: ${run.includeContentReferences}")
        echo("Content source: ${run.contentSource?.storageKey ?: "-"}")
        echo("Logical store: ${run.contentSource?.store ?: "-"}")
        echo("Scope: ${run.contentSource?.scope ?: "-"}")
        echo("Database artifact: ${run.result?.path ?: "-"}")
        echo("Content report: ${run.result?.contentReportPath ?: "-"}")
        run.result?.manifest?.let { manifest ->
            echo("Database: ${manifest.database}")
            echo("PostgreSQL: ${manifest.serverVersion}")
            echo("Dump bytes: ${manifest.length}")
            echo("SHA-256: ${manifest.sha256}")
            echo("References: ${manifest.contentInventory?.count ?: "-"}")
        }
    }
}
