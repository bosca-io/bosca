package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*

internal fun IArtifactNamespaceFields.view() = ArtifactNamespaceView(id, name, public, created.orEmpty(), repositoryCount)
internal fun IArtifactRepositoryFields.view() = ArtifactRepositoryView(id, namespaceId, namespace?.name.orEmpty(), name, type, created.orEmpty(), modified.orEmpty(), versionCount, tagCount)
internal fun IArtifactVersionFields.view(repository: ArtifactRepositoryView) = ArtifactVersionView(
    id, version, created.orEmpty(), completed, finalized, completedCommitSha.orEmpty(),
    blobs.map { blob ->
        // Maven stores checksum text in filename; its URL uses the corresponding artifact's filename.
        val checksum = when {
            repository.type != "maven" -> null
            blob.role.startsWith("sha1-") -> "sha1"
            blob.role.startsWith("md5-") -> "md5"
            else -> null
        }
        val downloadFilename = if (checksum == null) blob.filename.orEmpty() else {
            blobs.firstOrNull { it.digest == blob.digest && it.role == blob.role.removePrefix("$checksum-") }
                ?.filename?.takeIf(String::isNotBlank)?.let { "$it.$checksum" }.orEmpty()
        }
        ArtifactBlobView(
            blob.digest, blob.role, blob.filename.orEmpty(), blob.mediaType.orEmpty(), blob.size,
            artifactDownload(repository, version, blob.role, downloadFilename),
        )
    },
    metadata,
)
