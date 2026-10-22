package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** Operator commands for backup request state and portable artifacts. */
class BackupCommand : BoscaCliCommand(name = "backup") {
    init {
        subcommands(BackupInspectCommand(), BackupPostgresCommand())
    }

    override fun help(context: Context) = "Inspect backup requests and portable artifacts"

    override fun run() = Unit
}
