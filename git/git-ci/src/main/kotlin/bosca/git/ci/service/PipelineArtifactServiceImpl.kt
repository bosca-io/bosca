package bosca.git.ci.service

import bosca.git.ci.repository.PipelineArtifactRepository
import bosca.git.model.PipelineArtifact
import bosca.git.service.PipelineArtifactService
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.storage.service.ObjectNotFoundException
import bosca.storage.service.ObjectStorageService
import bosca.storage.service.StringObjectPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

@ServiceImplementation
class PipelineArtifactServiceImpl(
    private val objectStorage: ObjectStorageService,
    private val artifactRepository: PipelineArtifactRepository
) : PipelineArtifactService {

    override suspend fun upload(repositoryId: UUID, pipelineRunId: UUID, runNumber: Int, name: String, stream: InputStream, length: Long?) {
        val path = artifactPath(repositoryId, runNumber, name)
        val bytesWritten = withContext(Dispatchers.IO) {
            objectStorage.setInputStream(StringObjectPath(path), stream, length)
        }

        artifactRepository.upsert(
            PipelineArtifact(
                repositoryId = repositoryId,
                pipelineRunId = pipelineRunId,
                runNumber = runNumber,
                name = name,
                sizeBytes = bytesWritten
            )
        )
    }

    override suspend fun download(repositoryId: UUID, runNumber: Int, name: String): InputStream? {
        val path = artifactPath(repositoryId, runNumber, name)
        // Only a missing object means "no such artifact"; any other storage failure is an error, not
        // a 404. getInputStream opens off the caller's thread and closes a stream it cannot hand back.
        return try {
            objectStorage.getInputStream(StringObjectPath(path))
        } catch (_: ObjectNotFoundException) {
            null
        }
    }

    override suspend fun listByRun(pipelineRunId: UUID): List<PipelineArtifact> {
        return artifactRepository.findByRun(pipelineRunId)
    }

    override suspend fun delete(repositoryId: UUID, runNumber: Int, name: String) {
        val path = artifactPath(repositoryId, runNumber, name)
        withContext(Dispatchers.IO) { objectStorage.delete(StringObjectPath(path)) }
    }

    private fun artifactPath(repositoryId: UUID, runNumber: Int, name: String): String {
        return "git/$repositoryId/ci/artifacts/$runNumber/$name.tar.gz"
    }
}
