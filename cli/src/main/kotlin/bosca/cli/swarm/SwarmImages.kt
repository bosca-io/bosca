package bosca.cli.swarm

import bosca.cli.images.ImageRegistry
import bosca.cli.images.boscaImages
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException

class SwarmUpdateImagesCommand : SwarmAction(name = "update-images") {
    override fun help(context: Context) = "Pin Bosca images to their newest published stable releases"
    private val registry by option("--registry", help = "Override the image registry and namespace; default: each configured repository")
    private val images by option("--image", help = "Swarm image key to update; repeat to select images (default: all Bosca images)").multiple()
    private val check by option("--check", help = "Show resolved images without saving the configuration").flag()

    override fun run() = runBlocking {
        try {
            SwarmConfigFile(sourcePath()).use { file ->
                val config = file.load(saveDefaults = false)
                val updated = updateSwarmImages(config, registry, images.toSet()) { repository ->
                    val authority = java.net.URI(if (repository.contains("://")) repository else "https://$repository").rawAuthority
                    val auth = registryAuths(config).firstOrNull { it.server == authority }
                    val password = auth?.let { registryPassword(it) }.orEmpty()
                    require(auth == null || password.isNotBlank()) { "Set the registry password in the config or ${auth?.passwordEnv}" }
                    ImageRegistry(auth?.username.orEmpty(), password).latest(repository)
                }
                updated.images.filter { (key, value) -> value != config.images[key] }.forEach { (key, value) ->
                    echo("$key: ${config.images[key]} -> $value")
                }
                if (!check) file.save(updated)
                echo(if (check) "Image check finished; image pins were not saved." else "Image pins saved. Run bosca swarm deploy to apply them.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not update Swarm images")
        }
    }
}

/** Resolves all requested releases before changing any image pins; infrastructure and site images stay configured. */
internal suspend fun updateSwarmImages(
    config: SwarmConfig,
    registry: String?,
    keys: Set<String>,
    latest: suspend (String) -> String,
): SwarmConfig {
    val available = boscaImages.filter { it.swarmKey != null }
    require(keys.all { key -> available.any { it.swarmKey == key } }) { "Unknown Bosca image key; choose ${available.mapNotNull { it.swarmKey }.joinToString()}" }
    val updated = config.images.toMutableMap()
    val schemes = config.registrySchemes.toMutableMap()
    for (image in available.filter { keys.isEmpty() || it.swarmKey in keys }) {
        val key = requireNotNull(image.swarmKey)
        val configured = config.images.getValue(key)
        val repository = registry?.let { "${it.trimEnd('/')}/${image.repository}" } ?: configured.substringBefore('@').let {
            if (it.substringAfterLast('/').contains(':')) it.substringBeforeLast(':') else it
        }
        val uri = java.net.URI(if (repository.contains("://")) repository else "https://$repository")
        val authority = requireNotNull(uri.rawAuthority) { "Invalid image repository" }
        if (repository.contains("://")) {
            require(uri.scheme in setOf("http", "https")) { "Image registry scheme must be http or https" }
            schemes[authority] = uri.scheme
        }
        val reference = repository.removePrefix("https://").removePrefix("http://")
        val lookup = if (schemes[authority] == "http") "http://$reference" else reference
        val tag = latest(lookup)
        updated[key] = "$reference:$tag"
    }
    return config.copy(images = updated, registrySchemes = schemes)
}
