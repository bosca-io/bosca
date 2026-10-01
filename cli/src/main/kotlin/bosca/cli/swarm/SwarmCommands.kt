package bosca.cli.swarm

import bosca.cli.BoscaCliCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

/** Local Docker Swarm deployment commands. */
class SwarmCommand : BoscaCliCommand(name = "swarm") {
    override fun help(context: Context) = "Generate and install a multi-site Docker Swarm deployment"
    override fun run() = Unit
}

abstract class SwarmAction(name: String) : BoscaCliCommand(name = name) {
    protected val configPath by option("--config", help = "Reusable local Swarm configuration JSON file").required()

    protected fun sourcePath(): Path = Path.of(configPath).toAbsolutePath().normalize()

    protected fun configuration(deploy: Boolean = false): Pair<Path, SwarmConfig> {
        val path = sourcePath()
        return path to try {
            loadConfig(path, deploy)
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not load Swarm configuration")
        }
    }

    protected fun bundle(config: SwarmConfig, configFile: Path, output: String?): Path {
        val destination = (output?.let(Path::of) ?: configFile.parent.resolve("bosca-swarm-generated"))
            .toAbsolutePath().normalize()
        if (destination == configFile || configFile.startsWith(destination)) {
            throw CliktError("Output directory must not contain the configuration file")
        }
        try {
            renderSwarm(config, destination)
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not render Swarm files")
        }
        echo("Generated deployment files in $destination")
        echo("Generated files contain plaintext secrets; keep this directory private.")
        return destination
    }

    protected fun <T> withDeploymentBundle(config: SwarmConfig, path: Path, output: String?, work: (Path) -> T): T {
        val encrypted = SwarmSecretEncryption.hasEncryptedSecrets(swarmJson.parseToJsonElement(Files.readString(path)).jsonObject)
        if (output != null || !encrypted) return work(bundle(config, path, output))
        val temporary = Files.createTempDirectory("bosca-swarm-deploy-")
        try {
            if ("posix" in temporary.fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(temporary, setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                ))
            }
            return work(bundle(config, path, temporary.resolve("generated").toString()))
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}

class SwarmEncryptCommand : SwarmAction(name = "encrypt") {
    override fun help(context: Context) = "Encrypt only secret values in a Swarm JSON config"
    private val output by option("--output", help = "Write a new JSON file instead of replacing the source")

    override fun run() {
        val source = sourcePath()
        val destination = output?.let(Path::of)?.toAbsolutePath()?.normalize() ?: source
        val passphrase = try { swarmConfigPassphrase(confirm = true) }
        catch (e: Exception) { throw CliktError(e.message ?: "Could not read passphrase") }
        try {
            transformSwarmConfig(source, destination, encrypt = true, passphrase = passphrase)
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not encrypt Swarm config")
        } finally {
            passphrase.fill('\u0000')
        }
        echo("Encrypted Swarm secrets in $destination; non-secret JSON remains readable.")
        if (destination != source) echo("Original plaintext remains at $source.")
    }
}

class SwarmDecryptCommand : SwarmAction(name = "decrypt") {
    override fun help(context: Context) = "Decrypt secret values in a Swarm JSON config"
    private val output by option("--output", help = "Write a new JSON file instead of replacing the source")

    override fun run() {
        val source = sourcePath()
        val destination = output?.let(Path::of)?.toAbsolutePath()?.normalize() ?: source
        val passphrase = try { swarmConfigPassphrase(confirm = false) }
        catch (e: Exception) { throw CliktError(e.message ?: "Could not read passphrase") }
        try {
            transformSwarmConfig(source, destination, encrypt = false, passphrase = passphrase)
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not decrypt Swarm config")
        } finally {
            passphrase.fill('\u0000')
        }
        echo("Decrypted Swarm secrets in $destination (contains plaintext credentials)")
    }
}

class SwarmInitCommand : SwarmAction(name = "init") {
    override fun help(context: Context) = "Create a reusable local Swarm configuration"
    override fun run() {
        val path = Path.of(configPath).toAbsolutePath().normalize()
        if (Files.exists(path)) throw CliktError("Configuration already exists: $path")
        try {
            saveConfig(path, SwarmConfig().withSecrets())
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not create Swarm configuration")
        }
        echo("Created $path. Edit the VM, domain, image, and Artifacts settings before deployment.")
    }
}

class SwarmRenderCommand : SwarmAction(name = "render") {
    override fun help(context: Context) = "Generate stack and service configuration files locally"
    private val output by option("--output", help = "Generated bundle directory")
    override fun run() {
        val (path, config) = configuration()
        bundle(config, path, output)
    }
}

class SwarmBootstrapCommand : SwarmAction(name = "bootstrap") {
    override fun help(context: Context) = "Install Docker and initialize or join the configured Swarm nodes"
    override fun run() {
        val (_, config) = configuration()
        try {
            bootstrapNodes(config)
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Swarm bootstrap failed")
        }
        echo("Swarm nodes are ready for deployment")
    }
}

class SwarmDeployCommand : SwarmAction(name = "deploy") {
    override fun help(context: Context) = "Deploy generated stacks to an initialized Swarm over SSH"
    private val output by option("--output", help = "Generated bundle directory")
    private val skipPublicCheck by option("--skip-public-check", help = "Skip public HTTPS readiness checks").flag()
    override fun run() {
        val (path, config) = configuration(deploy = true)
        try {
            withDeploymentBundle(config, path, output) { rendered ->
                deploySwarm(config, rendered, skipPublicCheck)
            }
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Swarm deployment failed")
        }
    }
}

class SwarmSetupTokensCommand : SwarmAction(name = "setup-tokens") {
    override fun help(context: Context) = "Create service tokens after the initial deployment and apply them"
    private val skipPublicCheck by option("--skip-public-check", help = "Skip public HTTPS readiness checks").flag()

    override fun run() = runBlocking {
        val path = sourcePath()
        SwarmConfigFile(path).use { file ->
            val config = try { file.load(deploy = true) }
            catch (e: Exception) { throw CliktError(e.message ?: "Could not load Swarm configuration") }
            val updated = try {
                setupSwarmTokens(config, file::save)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CliktError(e.message ?: "Could not create Swarm recommendation tokens")
            }
            if (updated == config) {
                echo("Service tokens are already configured; applying the site stacks")
            }
            try {
                withDeploymentBundle(updated, path, null) { rendered ->
                    deploySwarm(updated, rendered, skipPublicCheck)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw CliktError(e.message ?: "Could not apply Swarm service tokens")
            }
        }
    }
}

class SwarmStatusCommand : SwarmAction(name = "status") {
    override fun help(context: Context) = "Show services on the configured Swarm manager"
    override fun run() {
        val (_, config) = configuration()
        try {
            echo(swarmStatus(config))
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not read Swarm service status")
        }
    }
}
