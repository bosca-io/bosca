package bosca.cli.mcp

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice

/**
 * Registers the Bosca CLI as an MCP server with Claude Code by
 * running `claude mcp add --transport stdio bosca -- <exe> mcp-server`.
 */
class McpInstallCommand : BoscaCliCommand(name = "mcp-install") {
    override fun help(context: Context) = "Register this CLI as an MCP server with Claude Code"

    private val scope by option(
        "--scope", "-s",
        help = "Claude Code scope: user or project (default: user)"
    ).choice("user", "project").default("user")

    override fun run() {
        val boscaPath = resolveBoscaExecutable()
        val args = mutableListOf("claude", "mcp", "add", "--transport", "stdio")
        if (scope == "project") {
            args += "--scope"
            args += "project"
        }
        args += "bosca"
        args += "--"
        args += boscaPath
        args += "mcp-server"

        echo("Running: ${args.joinToString(" ")}")
        val process = ProcessBuilder(args)
            .inheritIO()
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            echo("claude mcp add exited with code $exitCode", err = true)
            throw ProgramResult(exitCode)
        }
        echo("Bosca MCP server registered with Claude Code (scope: $scope)")
    }

    private fun resolveBoscaExecutable(): String {
        val command = ProcessHandle.current().info().command().orElse(null)
        if (command != null) return command
        val which = ProcessBuilder("which", "bosca")
            .redirectErrorStream(true)
            .start()
        val path = which.inputStream.bufferedReader().readText().trim()
        if (which.waitFor() == 0 && path.isNotEmpty()) return path
        throw CliktError("Cannot locate the bosca executable. Ensure it is on your PATH.")
    }
}
