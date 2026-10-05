package bosca.git.ci.service

import bosca.db.transaction
import bosca.git.ci.parser.PipelineYamlParser
import bosca.git.ci.repository.PipelineRepository
import bosca.git.model.ArtifactDefinition
import bosca.git.model.Pipeline
import bosca.git.model.PipelineConcurrency
import bosca.git.model.PipelineDefinition
import bosca.git.model.PipelineEnvironmentsSynced
import bosca.git.model.PipelineTrigger
import bosca.git.model.dispatch
import bosca.git.service.PipelineScheduleService
import bosca.git.service.PipelineService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Reconciles the current default-branch catalog and returns immutable trigger snapshots.
 * Removed catalog entries retain their IDs and history for delayed jobs and redelivery.
 */
@ServiceImplementation
class PipelineServiceImpl(
    private val pipelineRepository: PipelineRepository,
    private val browseService: RepositoryBrowseService,
    private val parser: PipelineYamlParser,
    private val json: Json,
    private val repositoryService: RepositoryService,
    private val scheduleService: PipelineScheduleService,
) : PipelineService {

    override suspend fun syncPipelines(repositoryId: UUID, ref: String, commitSha: String): List<Pipeline> = transaction {
        pipelineRepository.lockForSync(repositoryId)
        val repository = repositoryService.findById(repositoryId)
            ?: throw NoSuchElementException("Repository not found: $repositoryId")
        val defaultBranchRef = "refs/heads/${repository.defaultBranch}"
        // Resolve after acquiring the lock so a delayed worker always reconciles the current head.
        val catalogSha = browseService.resolveRef(repositoryId, defaultBranchRef)
        val catalogSnapshot = catalogSha?.let { parsePipelines(repositoryId, it) }.orEmpty()
        val catalog = mutableMapOf<String, Pipeline>()

        for ((snapshot, definition) in catalogSnapshot) {
            val existing = pipelineRepository.findByRepositoryAndFilePath(repositoryId, snapshot.filePath)
            val pipeline = when {
                existing == null -> pipelineRepository.create(snapshot)
                existing.configHash == snapshot.configHash && existing.deletedAt == null -> existing
                else -> pipelineRepository.update(snapshot.copy(id = existing.id, created = existing.created))
                    ?: throw NoSuchElementException("Pipeline not found: ${existing.id}")
            }
            catalog[pipeline.filePath] = pipeline
            if (ref == defaultBranchRef) {
                scheduleService.sync(pipeline, definition.triggers)
                // Environments come from the current default branch, including when a job is redelivered.
                if (definition.environments.isNotEmpty()) {
                    PipelineEnvironmentsSynced(repositoryId, pipeline.id, definition.environments).dispatch()
                }
            }
        }

        for (pipeline in pipelineRepository.findByRepository(repositoryId)) {
            if (pipeline.filePath !in catalog) {
                scheduleService.deleteByPipeline(pipeline.id)
                pipelineRepository.archive(pipeline.id)
            }
        }

        if (catalogSha == commitSha) return@transaction catalog.values.toList()
        parsePipelines(repositoryId, commitSha).map { (snapshot, _) ->
            val identity = catalog[snapshot.filePath]
                ?: pipelineRepository.findByRepositoryAndFilePath(repositoryId, snapshot.filePath)
                ?: pipelineRepository.create(snapshot.copy(deletedAt = OffsetDateTime.now()))
            // Trigger evaluation uses the immutable snapshot while persistence keeps live metadata.
            snapshot.copy(id = identity.id, created = identity.created, deletedAt = identity.deletedAt)
        }
    }

    override suspend fun findByRepository(repositoryId: UUID): List<Pipeline> {
        return pipelineRepository.findByRepository(repositoryId)
    }

    override suspend fun findById(id: UUID): Pipeline? {
        return pipelineRepository.findById(id)
    }

    override suspend fun findByRepositoryAndName(repositoryId: UUID, name: String): Pipeline? {
        return pipelineRepository.findByRepositoryAndName(repositoryId, name)
    }

    override suspend fun all(): List<Pipeline> = pipelineRepository.findAll()

    override suspend fun declaredArtifacts(pipelineId: UUID): List<ArtifactDefinition> {
        val pipeline = pipelineRepository.findById(pipelineId) ?: return emptyList()
        val repository = repositoryService.findById(pipeline.repositoryId) ?: return emptyList()
        val definition = parseDefinition(pipeline.repositoryId, repository.defaultBranch, pipeline.filePath)
            ?: return emptyList()
        return definition.jobs.values.flatMap { it.artifacts }.distinct()
    }

    override suspend fun parseDefinition(repositoryId: UUID, ref: String, filePath: String): PipelineDefinition? {
        val blob = browseService.readBlob(repositoryId, ref, filePath) ?: return null
        val content = blob.content ?: return null
        return parser.parse(content, filePath)
    }

    override suspend fun delete(id: UUID) {
        scheduleService.deleteByPipeline(id)
        pipelineRepository.delete(id)
    }

    private suspend fun discoverPipelineFiles(repositoryId: UUID, ref: String): List<Pair<String, String>> {
        val entries = browseService.listTree(repositoryId, ref, ".bosca/pipelines")

        return entries
            .filter { it.name.endsWith(".yaml") || it.name.endsWith(".yml") }
            .mapNotNull { entry ->
                val path = ".bosca/pipelines/${entry.name}"
                val blob = browseService.readBlob(repositoryId, ref, path)
                val content = blob?.content
                if (content != null) path to content else null
            }
    }

    private suspend fun parsePipelines(repositoryId: UUID, commitSha: String): List<Pair<Pipeline, PipelineDefinition>> =
        discoverPipelineFiles(repositoryId, commitSha).mapNotNull { (filePath, content) ->
            val definition = try {
                parser.parse(content, filePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to parse pipeline {}: {}", filePath, e.message)
                return@mapNotNull null
            }
            val errors = parser.validate(definition)
            if (errors.isNotEmpty()) {
                log.warn("Skipping invalid pipeline {}: {}", filePath, errors)
                return@mapNotNull null
            }
            Pipeline(
                repositoryId = repositoryId,
                filePath = filePath,
                name = definition.name,
                triggers = json.encodeToJsonElement(ListSerializer(PipelineTrigger.serializer()), definition.triggers),
                concurrency = definition.concurrency?.let { json.encodeToJsonElement(PipelineConcurrency.serializer(), it) },
                configHash = sha256(content),
            ) to definition
        }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PipelineServiceImpl::class.java)
    }
}
