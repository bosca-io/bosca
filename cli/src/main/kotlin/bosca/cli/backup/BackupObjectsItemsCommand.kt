package bosca.cli.backup

import bosca.cli.api.BackupApi
import bosca.graphql.gen.backup.GetObjectBackupRunItemsData
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Reads a bounded checkpoint page in the run's declared item order. */
class BackupObjectsItemsCommand : BackupRemoteCommand(name = "items") {
    private val key by option("--key", help = "Exact object run key").required()
        .check("Run key must not be blank") { it.isNotBlank() }
    private val offset by option("--offset", help = "Zero-based checkpoint offset").int().default(0)
        .check("Offset must not be negative") { it >= 0 }
    private val limit by option("--limit", help = "Page size, from 1 to 100").int().default(20)
        .check("Limit must be between 1 and 100") { it in 1..100 }

    override fun help(context: Context) = "Read saved per-object checkpoints with offset pagination"

    override suspend fun execute(api: BackupApi) {
        val run = api.objectRunItems(key, offset, limit)
        if (run == null) {
            if (jsonOutput) echo("null")
            throw CliktError("Object run not found: $key")
        }
        if (jsonOutput) {
            val serializer = ListSerializer(GetObjectBackupRunItemsData.Backups.ObjectRuns.Run.Items.serializer())
            echo(Json.encodeToString(serializer, run.items))
        } else if (run.items.isEmpty()) {
            echo("No checkpoints in this page.")
        } else {
            echo("STATUS\tVERIFIED BYTES\tATTEMPTS\tSTORE\tKEY\tERROR")
            for (item in run.items) {
                echo(
                    "${item.status}\t${item.verifiedBytes}\t${item.attempts}\t" +
                        "${item.payload.store}\t${item.payload.key}\t${item.error ?: "-"}",
                )
            }
        }
    }
}
