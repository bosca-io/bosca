package bosca.workops.model.artifact

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Records a published artifact (Maven, npm, Docker, etc.) tied to a
 * specific project version. The [status] lifecycle drives downstream
 * automation — transitioning to [PublicationStatus.PUBLISHED] fires
 * the `ArtifactPublished` automation trigger.
 */
@Serializable
data class ArtifactPublication(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("version_id")
    @Contextual
    val versionId: UUID,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("artifact_type")
    val artifactType: ArtifactType,
    val coordinates: String,
    @ColumnName("repository_url")
    val repositoryUrl: String? = null,
    @ColumnName("published_at")
    @Contextual
    val publishedAt: OffsetDateTime? = null,
    @ColumnName("published_by_principal_id")
    @Contextual
    val publishedByPrincipalId: UUID? = null,
    @ColumnName("checksum_sha256")
    val checksumSha256: String? = null,
    val status: PublicationStatus = PublicationStatus.PENDING,
    @ColumnName("external_url")
    val externalUrl: String? = null,
    /** Registry namespace the artifact was published to (e.g. `bosca-helm`) — how a deploy target
     *  locates the artifact's blobs in the registry. */
    val namespace: String? = null,
    /** Environments this artifact serves, by environment name; empty = every environment. */
    val environments: List<String> = emptyList(),
    val version: Long = 0,
)

@Serializable
enum class ArtifactType { MAVEN, NPM, DOCKER, HELM, HELM_VALUES, GRAALVM_NATIVE, IOS_FRAMEWORK, ANDROID_AAR, WASM, OTHER }

/** Maps a build-yaml artifact type string (kebab or snake case) to the publication type; unmodelled types map to OTHER. */
fun artifactTypeOfString(type: String): ArtifactType = when (type.lowercase()) {
    "maven" -> ArtifactType.MAVEN
    "npm" -> ArtifactType.NPM
    "docker" -> ArtifactType.DOCKER
    "helm" -> ArtifactType.HELM
    "helm-values" -> ArtifactType.HELM_VALUES
    "android-aar", "android_aar" -> ArtifactType.ANDROID_AAR
    "ios-framework", "ios_framework" -> ArtifactType.IOS_FRAMEWORK
    "graalvm-native", "graalvm_native" -> ArtifactType.GRAALVM_NATIVE
    "wasm" -> ArtifactType.WASM
    else -> ArtifactType.OTHER
}

@Serializable
enum class PublicationStatus { PENDING, PUBLISHED, FAILED, YANKED }

/**
 * Input for registering a new artifact publication, typically called
 * from CI after a build produces a publishable artifact.
 */
@Serializable
data class RegisterArtifactInput(
    @Contextual
    val versionId: UUID,
    @Contextual
    val projectId: UUID,
    val artifactType: ArtifactType,
    val coordinates: String,
    val repositoryUrl: String? = null,
    val checksumSha256: String? = null,
    val externalUrl: String? = null,
    val namespace: String? = null,
    val environments: List<String> = emptyList(),
)

/**
 * An artifact a release WILL publish — declared in a bundled project's CI pipeline, with the
 * coordinate's `${'$'}{{ version }}` / `${'$'}{{ tag }}` tokens resolved against the project's release
 * version. Shown before any build runs; a registered [ArtifactPublication] supersedes it.
 */
@Serializable
data class ReleaseDeclaredArtifact(
    @Contextual
    val projectId: UUID,
    val type: ArtifactType,
    val namespace: String,
    val coordinate: String,
    val environments: List<String> = emptyList(),
)
