package bosca.artifacts.git

import bosca.artifacts.service.ArtifactRepositoryService
import bosca.di.annotation.Provider
import bosca.di.annotation.Providers
import bosca.git.service.ProducedArtifactVerifier
import bosca.git.service.RequiredArtifactVerifier

/** Selected by composition roots that host both Git CI and the artifact registry. */
@Providers
class Configuration {
    /** Resolves produced CI coordinates through the artifact registry. */
    @Provider(singleton = true)
    fun producedArtifactVerifier(artifacts: ArtifactRepositoryService): ProducedArtifactVerifier =
        RegistryProducedArtifactVerifier(artifacts)

    /** Resolves CI artifact requirements through the artifact registry. */
    @Provider(singleton = true)
    fun requiredArtifactVerifier(artifacts: ArtifactRepositoryService): RequiredArtifactVerifier =
        RegistryProducedArtifactVerifier(artifacts)
}
