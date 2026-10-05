package bosca.artifacts.helm.routes

import bosca.artifacts.model.ArtifactAction
import bosca.artifacts.model.ArtifactType
import bosca.artifacts.service.ArtifactPermissionEvaluator
import bosca.artifacts.service.ArtifactRepositoryService
import bosca.security.service.AuthenticationContext
import bosca.server.HttpHeaders
import bosca.server.HttpStatusCode
import bosca.server.ServerCall
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.yaml.snakeyaml.Yaml
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/**
 * Parsed metadata from a Chart.yaml inside a Helm chart tarball.
 */
internal data class ChartMetadata(
    val name: String,
    val version: String,
    val appVersion: String?,
    val description: String?,
    val apiVersion: String?,
)

/**
 * Checks Helm artifact permissions. Returns true if allowed, false if the
 * response has already been sent (401/403).
 */
internal suspend fun helmRequirePermission(
    call: ServerCall,
    permissionEvaluator: ArtifactPermissionEvaluator,
    authenticationContext: AuthenticationContext,
    namespace: String,
    repository: String = "*",
    version: String? = null,
    action: ArtifactAction,
    isPublic: Boolean = false,
): Boolean {
    if (permissionEvaluator.evaluate(authenticationContext, "helm", namespace, repository, version, action, isPublic)) {
        return true
    }
    if (authenticationContext.principal() == null) {
        call.response.header(HttpHeaders.WWWAuthenticate, """Basic realm="Bosca Helm Registry"""")
        call.respond(HttpStatusCode.Unauthorized, "")
    } else {
        call.respond(HttpStatusCode.Forbidden, "")
    }
    return false
}

/**
 * Extracts Chart.yaml content from a `.tgz` Helm chart archive.
 *
 * Helm charts are packaged as `<name>-<version>.tgz` where the tarball
 * contains a top-level directory named `<name>/` with `Chart.yaml` inside.
 */
internal fun parseChartArchive(input: InputStream): ChartMetadata? {
    TarArchiveInputStream(GZIPInputStream(input)).use { tar ->
        var entry = tar.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!entry.isDirectory && name.endsWith("/Chart.yaml") && name.count { it == '/' } == 1) {
                val yaml = tar.readBytes().decodeToString()
                return parseChartYaml(yaml)
            }
            entry = tar.nextEntry
        }
    }
    return null
}

/**
 * Parses Chart.yaml YAML content into [ChartMetadata].
 */
internal fun parseChartYaml(yaml: String): ChartMetadata? {
    val parsed = Yaml().load<Map<String, Any?>>(yaml) ?: return null
    val name = parsed["name"]?.toString() ?: return null
    val version = parsed["version"]?.toString() ?: return null
    return ChartMetadata(
        name = name,
        version = version,
        appVersion = parsed["appVersion"]?.toString(),
        description = parsed["description"]?.toString(),
        apiVersion = parsed["apiVersion"]?.toString(),
    )
}

/**
 * Builds a Helm repository index.yaml for all charts in a namespace.
 *
 * Format conforms to the Helm repository index spec:
 * `apiVersion: v1`, with an `entries` map keyed by chart name,
 * each containing a list of version objects.
 */
internal suspend fun buildIndexYaml(
    namespace: String,
    baseUrl: String,
    repoService: ArtifactRepositoryService,
): String {
    val ns = repoService.getNamespaceByName(namespace)
    val repos = if (ns != null) repoService.listRepositories(ns.id, ArtifactType.HELM) else emptyList()
    val generated = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))

    val index = linkedMapOf<String, Any>(
        "apiVersion" to "v1",
        "generated" to generated,
    )

    val entries = linkedMapOf<String, List<Map<String, Any>>>()
    for (repo in repos) {
        val chartEntries = mutableListOf<Map<String, Any>>()
        var offset = 0L
        val pageSize = 500
        while (true) {
            val versions = repoService.listVersions(repo.id, pageSize, offset)
            if (versions.isEmpty()) break

            for (version in versions) {
                val entry = linkedMapOf<String, Any>(
                    "name" to repo.name,
                    "version" to version.version,
                )
                val metadata = version.metadata
                metadata?.get("appVersion")?.jsonPrimitive?.content?.let { entry["appVersion"] = it }
                metadata?.get("description")?.jsonPrimitive?.content?.let { entry["description"] = it }
                metadata?.get("digest")?.jsonPrimitive?.content?.let { entry["digest"] = it }
                entry["urls"] = listOf("${baseUrl}/helm/$namespace/charts/${repo.name}/${version.version}")
                entry["created"] = version.created.toString()
                chartEntries.add(entry)
            }

            if (versions.size < pageSize) break
            offset += pageSize
        }
        entries[repo.name] = chartEntries
    }

    index["entries"] = entries
    return Yaml().dump(index)
}
