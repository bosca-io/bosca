package bosca.cli.artifacts

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

/**
 * Parent command grouping artifact repository operations such as
 * Docker registry authentication.
 */
class ArtifactsCommand : BoscaCliCommand(name = "artifacts") {
    override fun help(context: Context) = "Manage artifact repository access"
    override fun run() = Unit
}
