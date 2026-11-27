package bosca.workops.ui

import kotlinx.serialization.Serializable

/** A publication or a declaration awaiting publication, retaining its project and environment scope. */
@Serializable
data class WorkOpsReleaseArtifactRecord(
    val projectId: String,
    val type: String,
    val namespace: String,
    val coordinate: String,
    val environments: List<String> = emptyList(),
    val versionId: String = "",
    val publicationId: String = "",
    val status: String = "EXPECTED",
    val stale: Boolean = false,
) {
    val identity get() = listOf(projectId, type, namespace, coordinate)
    val environmentLabel get() = environments.joinToString(", ").ifBlank { "All environments" }
    val tone get() = when (status) { "PUBLISHED" -> "success"; "FAILED" -> "error"; "YANKED" -> "warning"; else -> "neutral" }
}
