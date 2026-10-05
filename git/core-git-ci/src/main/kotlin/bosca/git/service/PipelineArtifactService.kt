package bosca.git.service

import bosca.git.model.PipelineArtifact
import bosca.serialization.UUID
import bosca.service.Service
import java.io.InputStream

/**
 * Manages pipeline build artifacts stored in ObjectStorageService.
 * Artifacts are stored at:
 *   `git/{repositoryId}/ci/artifacts/{runNumber}/{name}.tar.gz`
 */
interface PipelineArtifactService : Service {

    suspend fun upload(repositoryId: UUID, pipelineRunId: UUID, runNumber: Int, name: String, stream: InputStream, length: Long?)

    suspend fun download(repositoryId: UUID, runNumber: Int, name: String): InputStream?

    suspend fun listByRun(pipelineRunId: UUID): List<PipelineArtifact>

    suspend fun delete(repositoryId: UUID, runNumber: Int, name: String)
}
