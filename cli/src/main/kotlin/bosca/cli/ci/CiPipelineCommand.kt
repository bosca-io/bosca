package bosca.cli.ci

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

class CiPipelineCommand : BoscaCliCommand(name = "pipeline") {
    override fun help(context: Context) = "Browse CI/CD pipelines"
    override fun run() = Unit
}
