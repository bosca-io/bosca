package bosca.cli.harness

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class HarnessCommand : CliktCommand(name = "harness") {
    override fun help(context: Context) = "Run the managed harness process and its MCP connections"
    override fun run() = Unit
}
