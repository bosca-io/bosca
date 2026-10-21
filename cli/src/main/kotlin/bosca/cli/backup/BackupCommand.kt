package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** Operator commands for portable backup artifacts. */
class BackupCommand : BoscaCliCommand(name = "backup") {
    init {
        subcommands(BackupInspectCommand())
    }

    override fun help(context: Context) = "Inspect portable backup artifacts"

    override fun run() = Unit
}
