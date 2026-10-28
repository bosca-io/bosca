package bosca.artifacts.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import bosca.artifacts.ui.graphql.*
import bosca.bml.graphql.GraphQLClient
import bosca.bml.graphql.execute

val artifactTypes = listOf("docker", "helm", "maven", "npm", "raw", "ml")
fun artifactEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
fun artifactOffset(value: String?): Long = value?.toLongOrNull()?.coerceAtLeast(0) ?: 0
fun artifactSize(value: Long?): String = when {
    value == null -> "—"
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, value / 1024.0)
    value < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, value / (1024.0 * 1024))
    else -> "%.2f GB".format(java.util.Locale.ROOT, value / (1024.0 * 1024 * 1024))
}
internal suspend fun artifactAdministrator(gql: GraphQLClient): Boolean = gql.execute(ArtifactsAccess, Unit).security.principals.current.groups.any { it.name == "administrators" }

/** Reproduces each registry protocol's addressable file layout; unaddressable roles have no URL. */
fun artifactDownload(repository: ArtifactRepositoryView, version: String, role: String, filename: String): String {
    val ns = artifactEncode(repository.namespace)
    val name = artifactEncode(repository.name)
    val v = artifactEncode(version)
    val file = artifactEncode(filename)
    val path = when (repository.type) {
        "raw" -> filename.takeIf(String::isNotBlank)?.let { "/raw/$ns/$name/$v/$file" }
        "maven" -> filename.takeIf(String::isNotBlank)?.let { "/maven/$ns/${repository.name.split('.').joinToString("/") { artifactEncode(it) }}/$v/$file" }
        "npm" -> if (role == "tarball") {
            val tar = artifactEncode(filename.ifBlank { "${repository.name}-$version.tgz" })
            if (repository.namespace == "_unscoped") "/npm/$name/-/$tar" else "/npm/@${artifactEncode(repository.namespace.removePrefix("@"))}/$name/-/$tar"
        } else null
        "helm" -> if (role == "chart") "/helm/$ns/charts/$name/$v" else null
        "ml" -> if (role == "model") "/ml/$ns/$name/$v" else null
        else -> null
    } ?: return ""
    return System.getenv("ATLAS_ARTIFACTS_URL").orEmpty().trimEnd('/') + path
}

/** Registry badge colors used by Studio's artifact screens. */
fun artifactTypeColor(type: String): String = when (type) {
    "docker" -> "#5ec5ff"
    "helm" -> "#0f7fff"
    "maven" -> "#ffb547"
    "npm" -> "#34d99a"
    "raw" -> "#c084fc"
    "ml" -> "#f472b6"
    else -> "var(--fg-2)"
}
