package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** Administration queries for native database backup requests. */
class BackupPostgresCommand : BoscaCliCommand(name = "postgres") {
    init {
        subcommands(BackupPostgresListCommand(), BackupPostgresGetCommand())
    }

    override fun help(context: Context) = "Read native database backup request state and retained artifact locations"

    override fun run() = Unit
}
