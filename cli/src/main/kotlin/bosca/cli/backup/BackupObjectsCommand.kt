package bosca.cli.backup

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/** Object inventory run and checkpoint administration queries. */
class BackupObjectsCommand : BoscaCliCommand(name = "objects") {
    init {
        subcommands(BackupObjectsListCommand(), BackupObjectsGetCommand(), BackupObjectsItemsCommand())
    }

    override fun help(context: Context) = "Read object backup and restore runs and per-item checkpoints"

    override fun run() = Unit
}
