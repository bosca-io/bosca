package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** Object inventory run and checkpoint administration commands. */
class BackupObjectsCommand : BoscaCliCommand(name = "objects") {
    init {
        subcommands(
            BackupObjectsListCommand(), BackupObjectsGetCommand(), BackupObjectsItemsCommand(),
            BackupObjectsPrepareRestoreCommand(),
            BackupObjectsRetryCommand(), BackupObjectsDispatchCommand(), BackupObjectsCancelPendingCommand(),
        )
    }

    override fun help(context: Context) = "Inspect and control object backup and restore runs"

    override fun run() = Unit
}
