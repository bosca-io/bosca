package bosca.meilisearch.admin.model

import kotlinx.serialization.Serializable

/**
 * Version information returned by the Meilisearch instance, providing
 * the software version, commit SHA, and commit date for identifying
 * the exact build running on a given node.
 */
@Serializable
data class MeilisearchVersion(
    val pkgVersion: String,
    val commitDate: String? = null,
    val commitSha: String? = null,
)
