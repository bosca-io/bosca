package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.RecoverPostgresBackupRunData
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.Json

/** Recovers only the exact native backup execution the operator has established is stopped. */
class BackupPostgresRecoverCommand : BackupRemoteCommand(name = "recover") {
    private val key by option("--key", help = "Exact native backup request key").required()
        .check("Request key must not be blank") { it.isNotBlank() }
    private val expectedToken by option("--expected-token", help = "Exact observed writer token returned by get").required()
        .check("Observed writer token must not be blank") { it.isNotBlank() }

    override fun help(context: Context) = "Recover an observed native backup writer only after its process and storage writer have stopped"

    override suspend fun execute(api: BackupApi) {
        val run = api.recoverPostgresRun(key, expectedToken)
        if (jsonOutput) {
            echo(Json.encodeToString(RecoverPostgresBackupRunData.Backups.RecoverPostgresRun.serializer(), run))
        } else {
            echo("Request: ${run.key}")
            echo("Status: ${run.status}")
            echo("Attempts: ${run.attempts}")
            echo("Version: ${run.version}")
            echo("Error: ${run.error ?: "-"}")
            echo("Recovery does not start another backup. Retry separately through the existing Pipeline controls.")
        }
    }
}
