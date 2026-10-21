package bosca.cli.backup

import bosca.backup.PostgresBackupContentReport
import bosca.backup.PostgresBackupContentSelectionStatus
import bosca.backup.PostgresBackupContentStatus
import bosca.cli.BoscaCliCommand
import bosca.storage.service.StorageDispatcher
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Reads recorded recovery evidence without contacting an instance or storage provider. */
class BackupInspectCommand : BoscaCliCommand(name = "inspect") {
    private val report by option("--report", help = "Downloaded content-report.json file").path().required()
    private val jsonOutput by option("--json", help = "Emit the complete portable report as JSON").flag()

    override fun help(context: Context) = "Inspect a local recovery report; does not verify stored bytes or restore readiness"

    override fun run() = runBlocking {
        val evidence = readReport()
        val references = requireNotNull(evidence.database.contentInventory).count
        val gaps = references - evidence.inspection.counts.getValue(PostgresBackupContentStatus.VERIFIED)
        echo("Recorded evidence only. Stored bytes and recovery readiness have not been checked.", err = true)
        if (jsonOutput) {
            echo(Json.encodeToString(PostgresBackupContentReport.serializer(), evidence))
            echo("Recorded reference gaps: $gaps", err = true)
        } else {
            echo("Capture: ${evidence.database.id}")
            echo("Source: ${evidence.database.sourceId}")
            echo("Database: ${evidence.database.database}")
            echo("PostgreSQL: ${evidence.database.serverVersion}")
            echo("Encoding: ${evidence.database.encoding}")
            echo("Database artifact: ${evidence.databasePath}")
            echo("Dump: ${evidence.database.payloadPath}")
            echo("Dump bytes: ${evidence.database.length}")
            echo("SHA-256: ${evidence.database.sha256}")
            echo("Store: ${evidence.inspection.store}")
            echo("Scope: ${evidence.objects.scope}")
            echo("References: $references")
            echo("Declared assets: ${evidence.objects.objects.size}")
            echo("Recorded reference gaps: $gaps")
            echo("\nRecorded selection:")
            for (status in PostgresBackupContentSelectionStatus.entries) {
                echo("  ${status.name}: ${evidence.selectionCounts.getValue(status)}")
            }
            echo("\nRecorded inspection:")
            for (status in PostgresBackupContentStatus.entries) {
                echo("  ${status.name}: ${evidence.inspection.counts.getValue(status)}")
            }
        }
        if (gaps > 0) throw ProgramResult(2)
    }

    private suspend fun readReport(): PostgresBackupContentReport = withContext(StorageDispatcher) {
        try {
            Files.newBufferedReader(report).use {
                Json.decodeFromString(PostgresBackupContentReport.serializer(), it.readText())
            }.also { it.validate() }
        } catch (failure: IOException) {
            throw CliktError("Cannot inspect recovery report '$report': ${failure.message}")
        } catch (failure: SerializationException) {
            throw CliktError("Cannot inspect recovery report '$report': ${failure.message}")
        } catch (failure: IllegalArgumentException) {
            throw CliktError("Cannot inspect recovery report '$report': ${failure.message}")
        }
    }
}
