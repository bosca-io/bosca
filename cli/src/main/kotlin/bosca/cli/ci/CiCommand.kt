package bosca.cli.ci

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context

class CiCommand : BoscaCliCommand(name = "ci") {
    override fun help(context: Context) = "CI/CD pipeline operations — agents, runs, and secrets"
    override fun run() = Unit
}

class CiAgentCommand : BoscaCliCommand(name = "agent") {
    override fun help(context: Context) = "Manage CI/CD build agents"
    override fun run() = Unit
}

class CiRunCommand : BoscaCliCommand(name = "run") {
    override fun help(context: Context) = "Manage pipeline runs"
    override fun run() = Unit
}

class CiSecretCommand : BoscaCliCommand(name = "secret") {
    override fun help(context: Context) = "Manage pipeline secrets"
    override fun run() = Unit
}
