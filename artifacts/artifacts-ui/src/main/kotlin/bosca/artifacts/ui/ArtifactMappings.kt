package bosca.artifacts.ui

import bosca.artifacts.ui.graphql.*

internal fun IArtifactNamespaceFields.view() = ArtifactNamespaceView(id, name, public, created.orEmpty(), repositoryCount)
internal fun IArtifactRepositoryFields.view() = ArtifactRepositoryView(id, namespaceId, namespace?.name.orEmpty(), name, type, created.orEmpty(), modified.orEmpty(), versionCount, tagCount)
internal fun IArtifactVersionFields.view(repository: ArtifactRepositoryView) = ArtifactVersionView(id, version, created.orEmpty(), completed, finalized, completedCommitSha.orEmpty(), blobs.map {
    ArtifactBlobView(it.digest, it.role, it.filename.orEmpty(), it.mediaType.orEmpty(), it.size, artifactDownload(repository, version, it.role, it.filename.orEmpty()))
}, metadata)
