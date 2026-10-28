package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** Operator commands for backup request state and object recovery. */
class BackupCommand : BoscaCliCommand(name = "backup") {
    init {
        subcommands(BackupPostgresCommand(), BackupObjectsCommand())
    }

    override fun help(context: Context) = "Inspect backup requests and control object recovery"

    override fun run() = Unit
}
