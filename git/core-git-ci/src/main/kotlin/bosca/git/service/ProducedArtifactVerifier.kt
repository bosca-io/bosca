package bosca.git.service

import bosca.git.model.ArtifactDefinition
import bosca.serialization.UUID
import bosca.service.Service

/**
 * Verifies that a CI job's DECLARED produced artifacts actually landed in the artifact registry.
 * A build yaml that declares an artifact promises it; a job whose steps all
 * succeeded but whose artifact never arrived is a broken build and must FAIL — downstream releases
 * select publications by what the build declared, and a declared-but-absent artifact would otherwise
 * surface far away (or not at all: relays skip channels with nothing to deploy).
 *
 * The registry lives in another module. The job service resolves this SPI before saving success;
 * declared outputs require a verifier. Once every producer succeeds, the registry announces completion.
 */
interface ProducedArtifactVerifier : Service {

    /** The subset of [artifacts] that are NOT present in the registry (empty = all landed). */
    suspend fun missing(artifacts: List<ArtifactDefinition>): List<ArtifactDefinition>

    /** Notifies the registry that every [jobIds] producer of [artifact] has finished uploading. */
    suspend fun completed(artifact: ArtifactDefinition, commitSha: String, jobIds: List<UUID>, principalId: UUID?)
}
