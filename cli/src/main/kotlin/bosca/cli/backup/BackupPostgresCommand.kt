package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** Administration controls for native database backup requests. */
class BackupPostgresCommand : BoscaCliCommand(name = "postgres") {
    init {
        subcommands(BackupPostgresListCommand(), BackupPostgresGetCommand(), BackupPostgresRecoverCommand())
    }

    override fun help(context: Context) = "Inspect native database backup requests and recover stopped writers"

    override fun run() = Unit
}
