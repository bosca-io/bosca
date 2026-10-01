package bosca.cli.ci

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

class RunLocalCommand : BoscaCliCommand(name = "run-local") {
    override fun help(context: Context) = "Run a pipeline YAML file locally without server orchestration"

    private val file by argument(help = "Path to the pipeline YAML file")

    private val job by option("--job", "-j", help = "Run only this job (and its dependencies)")

    private val serverUrl by option(
        "--server-url",
        envvar = "BOSCA_SERVER_URL",
        help = "Bosca server URL (defaults to agent config)",
    )

    private val agentToken by option(
        "--agent-token",
        envvar = "BOSCA_AGENT_TOKEN",
        help = "Agent token (defaults to agent config)",
    )

    private val registryUrl by option(
        "--registry-url",
        envvar = "BOSCA_REGISTRY_URL",
        help = "Artifact registry URL (defaults to agent config)",
    )

    private val ref by option(
        "--ref",
        help = "Git ref override (e.g. refs/tags/v1.0.0)",
    )

    private val env by option("--env", "-e", help = "Set environment variable (KEY=VALUE)").multiple()

    private val secretsFile by option(
        "--secrets-file",
        help = "Path to a .env file containing secrets (KEY=VALUE per line)",
    )

    override fun run() {
        val pipelineFile = File(file)
        if (!pipelineFile.exists()) {
            echo("Error: pipeline file not found: $file", err = true)
            throw ProgramResult(1)
        }

        val projectDir = detectProjectRoot(pipelineFile)
        val agentConfig = AgentConfig.load()
        val profile = findProfile()

        val effectiveServerUrl = serverUrl ?: agentConfig?.serverUrl ?: ""
        val effectiveToken = agentToken
            ?: agentConfig?.token?.ifEmpty { null }
            ?: profile?.profile?.auth?.token
            ?: ""
        val effectiveRegistryUrl = registryUrl ?: agentConfig?.registryUrl ?: ""

        val envOverrides = env.associate { entry ->
            val (key, value) = entry.split("=", limit = 2)
            key to value
        }

        val runner = LocalPipelineRunner(
            projectDir = projectDir,
            serverUrl = effectiveServerUrl,
            agentToken = effectiveToken,
            registryUrl = effectiveRegistryUrl,
            envOverrides = envOverrides,
            secretValues = loadSecrets(),
        )

        val success = runner.run(pipelineFile, job, ref) { msg -> echo(msg) }
        if (!success) {
            throw ProgramResult(1)
        }
    }

    private fun detectProjectRoot(pipelineFile: File): File {
        var dir = pipelineFile.absoluteFile.parentFile
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir
            dir = dir.parentFile
        }
        return File(System.getProperty("user.dir"))
    }

    private fun loadSecrets(): Map<String, String> {
        val file = secretsFile ?: return emptyMap()
        val secretFile = File(file)
        if (!secretFile.exists()) {
            echo("Warning: secrets file not found: $file", err = true)
            return emptyMap()
        }
        return secretFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate { line ->
                val (key, value) = line.split("=", limit = 2)
                key.trim() to value.trim()
            }
    }
}
