package bosca.cli.data

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

/**
 * Data management subcommand group — tools for provisioning and
 * managing content data in a Bosca instance.
 */
class DataCommand : BoscaCliCommand(name = "data") {
    override fun help(context: Context) =
        "Data management — install content, manage templates, and provision Bosca instances."
    override fun run() = Unit
}
