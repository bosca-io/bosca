package bosca.cli.helm

import bosca.cli.BoscaCliCommand
import bosca.cli.images.ImageRegistry
import bosca.cli.images.BoscaImage
import bosca.cli.images.PUBLIC_IMAGE_REGISTRY
import bosca.cli.images.boscaImages
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.coroutines.runBlocking
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

class HelmCommand : BoscaCliCommand(name = "helm") {
    override fun help(context: Context) = "Prepare Helm deployment settings"
    override fun run() = Unit
}

class HelmImagesCommand : BoscaCliCommand(name = "images") {
    override fun help(context: Context) = "Resolve published Bosca image releases into Helm values"
    private val registry by option("--registry", help = "Image registry and namespace").default(PUBLIC_IMAGE_REGISTRY)
    private val chart by option("--chart", help = "bosca-services or an individual Bosca chart").default("bosca-services")
    private val services by option("--service", help = "Subchart to update in bosca-services; repeat to select services (default: all Bosca services)").multiple()
    private val username by option("--registry-username", help = "Optional registry username").default("")
    private val passwordEnv by option("--registry-password-env", help = "Environment variable containing the registry password").default("BOSCA_REGISTRY_PASSWORD")
    private val output by option("--output", help = "Write Helm values to this file (default: standard output)")

    override fun run() = runBlocking {
        try {
            val images = helmImages(chart, services.toSet())
            val source = registry.trimEnd('/')
            val lookup = ImageRegistry(username, System.getenv(passwordEnv).orEmpty())
            val tags = images.associate { it.repository to lookup.latest("$source/${it.repository}") }
            val values = helmImageValues(chart, source.removePrefix("https://").removePrefix("http://"), tags, services.toSet())
            val yaml = Yaml(DumperOptions().apply { defaultFlowStyle = DumperOptions.FlowStyle.BLOCK }).dump(values)
            val destination = output
            if (destination == null) echo(yaml, trailingNewline = false)
            else {
                Files.writeString(Path.of(destination), yaml)
                echo("Wrote image values to $destination. Pass this file after your deployment values with helm -f.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CliktError(e.message ?: "Could not resolve Helm images")
        }
    }
}

/** Overrides only image settings, including the model loader nested under TF Serving. */
internal fun helmImageValues(chart: String, registry: String, tags: Map<String, String>, services: Set<String> = emptySet()): Map<String, Any> {
    val values = linkedMapOf<String, Any>()
    for (image in helmImages(chart, services)) {
        var nested = values
        val path = (if (chart == "bosca-services") listOf(image.chart) else emptyList()) + image.imagePath.split('.')
        for (key in path.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            nested = nested.getOrPut(key) { linkedMapOf<String, Any>() } as LinkedHashMap<String, Any>
        }
        nested[path.last()] = mapOf("registry" to registry, "repository" to image.repository,
            "tag" to tags.getValue(image.repository), "digest" to "")
    }
    values["global"] = mapOf("imageRegistry" to registry)
    return values
}

/** Selects only requested umbrella services, rejecting unknown services before registry requests. */
private fun helmImages(chart: String, services: Set<String>): List<BoscaImage> {
    val available = boscaImages.filter { chart == "bosca-services" || it.chart == chart }
    require(available.isNotEmpty()) { "Unknown Bosca chart: $chart" }
    require(services.isEmpty() || chart == "bosca-services") { "--service requires --chart bosca-services" }
    require(services.all { service -> available.any { it.chart == service } }) { "Unknown Bosca service; choose ${available.map { it.chart }.distinct().joinToString()}" }
    return available.filter { services.isEmpty() || it.chart in services }
}
