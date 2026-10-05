package bosca.meilisearch.client.model

import kotlinx.serialization.Serializable

/**
 * Meilisearch server version information.
 */
@Serializable
data class Version(
    val pkgVersion: String = "",
    val commitDate: String? = null,
    val commitSha: String? = null,
)
