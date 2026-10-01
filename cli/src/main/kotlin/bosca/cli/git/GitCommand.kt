package bosca.cli.git

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

class GitCommand : BoscaCliCommand(name = "git") {
    override fun help(context: Context) = "Manage git server access"
    override fun run() = Unit
}
