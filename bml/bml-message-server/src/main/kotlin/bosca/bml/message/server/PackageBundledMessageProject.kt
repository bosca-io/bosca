package bosca.bml.message.server

import kotlinx.coroutines.runBlocking
import java.io.File

internal suspend fun packageLatestMessageProject(
    client: MessageArtifactClient,
    project: String,
    outputRoot: File,
): BundledMessageProjects.Version {
    val latest = client.latestVersion(project)
        ?: error("No published message artifact with a jar is available for '$project'")
    val jar = requireNotNull(latest.jar)
    val target = File(outputRoot, "$project/${latest.version}/${jar.filename}")
    client.download(project, latest.version, jar.filename, jar.digest, target)
    return BundledMessageProjects.Version(project, latest.version, target)
}

/** Packages the latest first-party message project for the service image release. */
fun main() = runBlocking {
    val registryUrl = System.getenv("BOSCA_REGISTRY_URL")
        ?.takeIf { it.isNotBlank() }
        ?.let(::registryBaseUrl)
        ?: error("BOSCA_REGISTRY_URL is required to package the bundled message fallback")
    val outputRoot = System.getenv("BML_MESSAGE_BUNDLED_PROJECTS_DIR")
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: error("BML_MESSAGE_BUNDLED_PROJECTS_DIR is required to package the bundled message fallback")
    val packaged = packageLatestMessageProject(
        MessageArtifactClient(registryUrl, System.getenv("BOSCA_REGISTRY_TOKEN")?.takeIf { it.isNotBlank() }),
        DEFAULT_BUNDLED_MESSAGE_PROJECT,
        outputRoot,
    )
    println("Packaged ${packaged.project}@${packaged.version} at ${packaged.jar}")
}

internal fun registryBaseUrl(value: String): String =
    if (value.startsWith("http://") || value.startsWith("https://")) value.trimEnd('/')
    else "https://${value.trimEnd('/')}"

internal const val DEFAULT_BUNDLED_MESSAGE_PROJECT = "bosca-messages"
